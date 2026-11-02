package com.agentcat.sdk.modules.pipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Sanitizer}.
 *
 * <p>Mirrors {@code sanitization.test.ts} from the TypeScript reference SDK.
 * Tests are grouped into three suites matching the TS describe-blocks:
 * <ol>
 *   <li>Response content blocks — image/audio/resource/unknown type handling</li>
 *   <li>Parameter scanning — large base64 detection and redaction</li>
 *   <li>Integration — both fields together; immutability</li>
 * </ol>
 */
class SanitizerTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Generates a valid base64 string of {@code sizeInChars} characters. */
    private static String makeLargeBase64(int sizeInChars) {
        // Produce raw bytes, encode, then trim / pad to exact length
        byte[] raw = new byte[(sizeInChars * 3) / 4 + 4];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) ('A' + i % 26);
        String encoded = Base64.getEncoder().encodeToString(raw);
        // Trim to requested length, keeping trailing '=' padding valid
        return encoded.substring(0, sizeInChars - 1) + "=";
    }

    private static String makeLargeBase64() { return makeLargeBase64(12_000); }

    /** A long string of plain text — NOT base64. */
    private static String makeLargeNonBase64(int size) {
        return "Hello, world! This is NOT base64. ".repeat((size / 34) + 1).substring(0, size);
    }

    private static Map<String, Object> contentBlock(String type, Object... kvPairs) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", type);
        for (int i = 0; i < kvPairs.length; i += 2) {
            block.put((String) kvPairs[i], kvPairs[i + 1]);
        }
        return block;
    }

    private static Map<String, Object> textBlock(String text) {
        return contentBlock("text", "text", text);
    }

    private static Map<String, Object> response(List<Object> content) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("content", content);
        return r;
    }

    // ── Suite 1: response content blocks ─────────────────────────────────────

    @Test
    void replacesImageAndAudioButKeepsTextBlocks() {
        List<Object> content = List.of(
                contentBlock("text", "text", "Hello world"),
                contentBlock("image", "data", "base64imagedata...", "mimeType", "image/png"),
                contentBlock("audio", "data", "base64audiodata...", "mimeType", "audio/wav")
        );
        Map<String, Object> result = Sanitizer.sanitizeResponse(response(content));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) result.get("content");

        assertEquals(3, out.size());
        assertEquals("text",        out.get(0).get("type"));
        assertEquals("Hello world", out.get(0).get("text"));
        assertEquals("text", out.get(1).get("type"));
        assertTrue(out.get(1).get("text").toString().contains("image"));
        assertEquals("text", out.get(2).get("type"));
        assertTrue(out.get(2).get("text").toString().contains("audio"));
    }

    @Test
    void leavesTextOnlyContentUnchanged() {
        List<Object> content = List.of(
                contentBlock("text", "text", "First"),
                contentBlock("text", "text", "Second")
        );
        Map<String, Object> result = Sanitizer.sanitizeResponse(response(content));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) result.get("content");

        assertEquals(2, out.size());
        assertEquals("First",  out.get(0).get("text"));
        assertEquals("Second", out.get(1).get("text"));
    }

    @Test
    void redactsEmbeddedResourceWithBlob() {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("uri", "file:///data.bin");
        resource.put("blob", "base64blobdata...");
        resource.put("mimeType", "application/octet-stream");

        List<Object> content = List.of(contentBlock("resource", "resource", resource));
        Map<String, Object> result = Sanitizer.sanitizeResponse(response(content));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) result.get("content");

        assertEquals("text", out.get(0).get("type"));
        assertTrue(out.get(0).get("text").toString().contains("binary resource content redacted"));
    }

    @Test
    void passesThroughEmbeddedResourceWithText() {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("uri", "file:///readme.txt");
        resource.put("text", "This is a text resource");
        resource.put("mimeType", "text/plain");

        Map<String, Object> block = contentBlock("resource", "resource", resource);
        List<Object> content = List.of(block);
        Map<String, Object> result = Sanitizer.sanitizeResponse(response(content));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) result.get("content");

        assertEquals("resource", out.get(0).get("type"));
    }

    @Test
    void redactsUnknownContentTypeWithTypeName() {
        List<Object> content = List.of(
                contentBlock("video", "data", "somestuff", "mimeType", "video/mp4")
        );
        Map<String, Object> result = Sanitizer.sanitizeResponse(response(content));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) result.get("content");

        assertEquals("text", out.get(0).get("type"));
        assertTrue(out.get(0).get("text").toString().contains("video"));
        assertTrue(out.get(0).get("text").toString().contains("unsupported content type"));
    }

    @Test
    void passesThroughResourceLink() {
        Map<String, Object> block = contentBlock("resource_link",
                "uri", "file:///some/resource", "name", "My Resource");
        List<Object> content = List.of(block);
        Map<String, Object> result = Sanitizer.sanitizeResponse(response(content));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) result.get("content");

        assertEquals("resource_link", out.get(0).get("type"));
    }

    @Test
    void redactsLargeBase64InsideStructuredContent() {
        String largeBase64 = makeLargeBase64();

        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("data", largeBase64);
        sc.put("label", "some label");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("structuredContent", sc);

        Map<String, Object> result = Sanitizer.sanitizeResponse(resp);

        @SuppressWarnings("unchecked")
        Map<String, Object> outSc = (Map<String, Object>) result.get("structuredContent");

        assertNotEquals(largeBase64, outSc.get("data"), "large base64 must be redacted");
        assertEquals("some label", outSc.get("label"), "non-base64 field must be preserved");
    }

    @Test
    void handlesResponseWithoutContentArray() {
        Map<String, Object> resp = Map.of("result", "success");
        Map<String, Object> result = Sanitizer.sanitizeResponse(resp);
        assertEquals("success", result.get("result"));
    }

    @Test
    void handlesNullResponseWithoutError() {
        assertNull(Sanitizer.sanitizeResponse(null));
    }

    // ── Suite 2: parameter scanning ───────────────────────────────────────────

    @Test
    void leavesSmallStringsUnchanged() {
        Map<String, Object> params = Map.of("name", "hello", "query", "some query text");
        Map<String, Object> result = Sanitizer.sanitizeParams(params);
        assertEquals("hello",           result.get("name"));
        assertEquals("some query text", result.get("query"));
    }

    @Test
    void redactsLargeBase64InParams() {
        String largeBase64 = makeLargeBase64();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("imageData", largeBase64);

        Map<String, Object> result = Sanitizer.sanitizeParams(params);

        assertNotEquals(largeBase64, result.get("imageData"), "large base64 must be redacted");
        assertTrue(result.get("imageData").toString().contains("redacted"));
    }

    @Test
    void leavesLargeNonBase64StringUnchanged() {
        String largeText = makeLargeNonBase64(12_000);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("longText", largeText);

        Map<String, Object> result = Sanitizer.sanitizeParams(params);

        assertEquals(largeText, result.get("longText"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redactsDeeplyNestedBase64() {
        String largeBase64 = makeLargeBase64();

        Map<String, Object> level3 = new LinkedHashMap<>();
        level3.put("data", largeBase64);
        Map<String, Object> level2 = new LinkedHashMap<>();
        level2.put("level3", level3);
        Map<String, Object> level1 = new LinkedHashMap<>();
        level1.put("level2", level2);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("level1", level1);

        Map<String, Object> result = Sanitizer.sanitizeParams(params);

        String actual = ((Map<String, Object>)
                ((Map<String, Object>)
                ((Map<String, Object>) result.get("level1")).get("level2"))
                .get("level3")).get("data").toString();

        assertTrue(actual.contains("redacted"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyRedactsBase64InMixedParams() {
        String largeBase64 = makeLargeBase64();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("count",      42);
        params.put("active",     true);
        params.put("name",       "short string");
        params.put("binaryData", largeBase64);
        params.put("tags",       List.of("a", "b", "c"));

        Map<String, Object> result = Sanitizer.sanitizeParams(params);

        assertEquals(42,                       result.get("count"));
        assertEquals(true,                     result.get("active"));
        assertEquals("short string",           result.get("name"));
        assertNotEquals(largeBase64,           result.get("binaryData"));
        assertEquals(List.of("a", "b", "c"),  result.get("tags"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redactsBase64InsideArray() {
        String largeBase64 = makeLargeBase64();

        List<Object> items = new ArrayList<>();
        items.add("small string");
        items.add(largeBase64);
        items.add("another small");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("items", items);

        Map<String, Object> result = Sanitizer.sanitizeParams(params);

        List<Object> outItems = (List<Object>) result.get("items");
        assertEquals("small string",   outItems.get(0));
        assertTrue(outItems.get(1).toString().contains("redacted"));
        assertEquals("another small",  outItems.get(2));
    }

    @Test
    void respectsBase64SizeGateBoundary() {
        // exactly 10240 chars → redacted; 10239 chars → kept
        String atGate    = makeLargeBase64(10_240);
        String belowGate = makeLargeBase64(10_239);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("atGate",    atGate);
        params.put("belowGate", belowGate);

        Map<String, Object> result = Sanitizer.sanitizeParams(params);

        assertTrue(result.get("atGate").toString().contains("redacted"),
                "string at gate (10240) must be redacted");
        assertEquals(belowGate, result.get("belowGate"),
                "string below gate (10239) must pass through");
    }

    @Test
    void handlesNullParamsWithoutError() {
        assertNull(Sanitizer.sanitizeParams(null));
    }

    // ── Suite 3: integration ──────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void sanitizesBothParamsAndResponseTogether() {
        String largeBase64 = makeLargeBase64();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("imageData", largeBase64);
        params.put("query",     "hello");

        List<Object> content = List.of(
                contentBlock("text",  "text", "Result text"),
                contentBlock("image", "data", "base64img", "mimeType", "image/png")
        );

        Map<String, Object> sanitizedParams  = Sanitizer.sanitizeParams(params);
        Map<String, Object> sanitizedResponse = Sanitizer.sanitizeResponse(response(content));

        // params
        assertTrue(sanitizedParams.get("imageData").toString().contains("redacted"));
        assertEquals("hello", sanitizedParams.get("query"));

        // response content
        List<Map<String, Object>> outContent =
                (List<Map<String, Object>>) sanitizedResponse.get("content");
        assertEquals("text",        outContent.get(0).get("type"));
        assertEquals("Result text", outContent.get(0).get("text"));
        assertEquals("text",        outContent.get(1).get("type"));
        assertTrue(outContent.get(1).get("text").toString().contains("image"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotMutateOriginalParams() {
        String largeBase64 = makeLargeBase64();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("imageData", largeBase64);

        Map<String, Object> result = Sanitizer.sanitizeParams(params);

        // original unchanged
        assertEquals(largeBase64, params.get("imageData"));
        // result is different
        assertTrue(result.get("imageData").toString().contains("redacted"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotMutateOriginalResponse() {
        String largeBase64 = makeLargeBase64();

        List<Object> content = new ArrayList<>();
        content.add(contentBlock("image", "data", "base64img", "mimeType", "image/png"));
        Map<String, Object> resp = response(content);

        Map<String, Object> result = Sanitizer.sanitizeResponse(resp);

        // original content block still "image"
        Map<String, Object> origBlock = (Map<String, Object>) content.get(0);
        assertEquals("image", origBlock.get("type"));
        assertEquals("base64img", origBlock.get("data"));

        // result is redacted
        List<Map<String, Object>> outContent =
                (List<Map<String, Object>>) result.get("content");
        assertEquals("text", outContent.get(0).get("type"));
    }
}
