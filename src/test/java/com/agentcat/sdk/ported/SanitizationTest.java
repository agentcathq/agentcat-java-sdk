package com.agentcat.sdk.ported;

import com.agentcat.sdk.modules.pipeline.Sanitizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/sanitization.test.ts
 *
 * TypeScript {@code sanitizeEvent(event)} → Java {@link Sanitizer#sanitizeParams} and
 * {@link Sanitizer#sanitizeResponse}.
 *
 * The Java Sanitizer works on Maps rather than a typed Event object.
 */
class SanitizationTest {

    private static final int SIZE_GATE = 10_240; // 10 KB

    private static String makeLargeBase64(int sizeInChars) {
        return "A".repeat(sizeInChars - 1) + "=";
    }

    private static String makeLargeNonBase64(int sizeInChars) {
        String unit = "Hello, world! This is NOT base64. ";
        int reps = (int) Math.ceil((double) sizeInChars / unit.length());
        return unit.repeat(reps);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // sanitizeResponse — content blocks
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void replacesImageAndAudioBlocksButKeepsTextBlocks() {
        // ported from: "should replace image and audio blocks but keep text blocks"
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", "Hello world"));
        content.add(Map.of("type", "image", "data", "base64imagedata...", "mimeType", "image/png"));
        content.add(Map.of("type", "audio", "data", "base64audiodata...", "mimeType", "audio/wav"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", content);
        Map<String, Object> result = Sanitizer.sanitizeResponse(response);

        List<Map<String, Object>> resultContent = (List<Map<String, Object>>) result.get("content");
        assertEquals(3, resultContent.size());
        assertEquals("Hello world", resultContent.get(0).get("text"));
        assertEquals("text", resultContent.get(1).get("type"));
        assertTrue(resultContent.get(1).get("text").toString().contains("image"));
        assertEquals("text", resultContent.get(2).get("type"));
        assertTrue(resultContent.get(2).get("text").toString().contains("audio"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void leavesTextOnlyContentUnchanged() {
        // ported from: "should leave text-only content unchanged"
        List<Map<String, Object>> content = List.of(
                Map.of("type", "text", "text", "First"),
                Map.of("type", "text", "text", "Second")
        );
        Map<String, Object> response = Map.of("content", content);
        Map<String, Object> result = Sanitizer.sanitizeResponse(response);
        List<Map<String, Object>> resultContent = (List<Map<String, Object>>) result.get("content");
        assertEquals("First", resultContent.get(0).get("text"));
        assertEquals("Second", resultContent.get(1).get("text"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redactsEmbeddedResourceWithBlob() {
        // ported from: "should redact EmbeddedResource with blob"
        Map<String, Object> resource = Map.of(
                "uri", "file:///data.bin",
                "blob", "base64blobdata...",
                "mimeType", "application/octet-stream"
        );
        List<Map<String, Object>> content = List.of(Map.of("type", "resource", "resource", resource));
        Map<String, Object> response = Map.of("content", content);
        Map<String, Object> result = Sanitizer.sanitizeResponse(response);
        List<Map<String, Object>> resultContent = (List<Map<String, Object>>) result.get("content");
        assertEquals("text", resultContent.get(0).get("type"));
        assertTrue(resultContent.get(0).get("text").toString().contains("binary resource"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void passesEmbeddedResourceWithTextThrough() {
        // ported from: "should pass through EmbeddedResource with text"
        Map<String, Object> resource = Map.of(
                "uri", "file:///readme.txt",
                "text", "This is a text resource",
                "mimeType", "text/plain"
        );
        Map<String, Object> block = Map.of("type", "resource", "resource", resource);
        Map<String, Object> response = Map.of("content", List.of(block));
        Map<String, Object> result = Sanitizer.sanitizeResponse(response);
        List<Map<String, Object>> resultContent = (List<Map<String, Object>>) result.get("content");
        // Text resource should be passed through (not redacted)
        assertEquals("resource", resultContent.get(0).get("type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redactsUnknownContentTypesWithTypeName() {
        // ported from: "should redact unknown content types with type name in message"
        List<Map<String, Object>> content = List.of(
                Map.of("type", "video", "data", "somestuff", "mimeType", "video/mp4")
        );
        Map<String, Object> response = Map.of("content", content);
        Map<String, Object> result = Sanitizer.sanitizeResponse(response);
        List<Map<String, Object>> resultContent = (List<Map<String, Object>>) result.get("content");
        assertEquals("text", resultContent.get(0).get("type"));
        assertTrue(resultContent.get(0).get("text").toString().contains("video"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void passesResourceLinkUnchanged() {
        // ported from: "should pass through resource_link unchanged"
        Map<String, Object> resourceLink = Map.of(
                "type", "resource_link",
                "uri", "file:///some/resource",
                "name", "My Resource"
        );
        Map<String, Object> response = Map.of("content", List.of(resourceLink));
        Map<String, Object> result = Sanitizer.sanitizeResponse(response);
        List<Map<String, Object>> resultContent = (List<Map<String, Object>>) result.get("content");
        assertEquals("resource_link", resultContent.get(0).get("type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redactsLargeBase64InsideStructuredContent() {
        // ported from: "should redact large base64 inside structuredContent"
        String largeBase64 = makeLargeBase64(12000);
        Map<String, Object> structuredContent = new LinkedHashMap<>();
        structuredContent.put("data", largeBase64);
        structuredContent.put("label", "some label");
        Map<String, Object> response = Map.of("structuredContent", structuredContent);
        Map<String, Object> result = Sanitizer.sanitizeResponse(response);
        Map<String, Object> sc = (Map<String, Object>) result.get("structuredContent");
        assertTrue(sc.get("data").toString().contains("binary data redacted") ||
                   sc.get("data").toString().contains("base64") ||
                   !sc.get("data").toString().startsWith("A"),
                "Large base64 should be redacted");
        assertEquals("some label", sc.get("label"));
    }

    @Test
    void handlesResponseWithoutContentArray() {
        // ported from: "should handle response without content array"
        Map<String, Object> response = Map.of("result", "success");
        Map<String, Object> result = Sanitizer.sanitizeResponse(response);
        assertEquals("success", result.get("result"));
    }

    @Test
    void handlesNullResponse() {
        // ported from: "should handle null and undefined response without error"
        assertNull(Sanitizer.sanitizeResponse(null));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // sanitizeParams — parameter scanning
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void leavesSmallStringsUnchanged() {
        // ported from: "should leave small strings unchanged"
        Map<String, Object> params = Map.of("name", "hello", "query", "some query text");
        Map<String, Object> result = Sanitizer.sanitizeParams(params);
        assertEquals("hello", result.get("name"));
        assertEquals("some query text", result.get("query"));
    }

    @Test
    void redactsLargeBase64Strings() {
        // ported from: "should redact large base64 strings (>10KB)"
        String largeBase64 = makeLargeBase64(12000);
        Map<String, Object> params = Map.of("imageData", largeBase64);
        Map<String, Object> result = Sanitizer.sanitizeParams(params);
        assertNotEquals(largeBase64, result.get("imageData"));
        assertTrue(result.get("imageData").toString().contains("redacted") ||
                   result.get("imageData").toString().contains("binary"),
                "Large base64 should be redacted");
    }

    @Test
    void leavesLargeNonBase64StringsUnchanged() {
        // ported from: "should leave large non-base64 strings unchanged"
        String largeText = makeLargeNonBase64(12000);
        Map<String, Object> params = Map.of("longText", largeText);
        Map<String, Object> result = Sanitizer.sanitizeParams(params);
        assertEquals(largeText, result.get("longText"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findsDeeplyNestedLargeBase64() {
        // ported from: "should find and redact deeply nested large base64"
        String largeBase64 = makeLargeBase64(12000);
        Map<String, Object> level3 = new LinkedHashMap<>();
        level3.put("data", largeBase64);
        Map<String, Object> level2 = new LinkedHashMap<>();
        level2.put("level3", level3);
        Map<String, Object> level1 = new LinkedHashMap<>();
        level1.put("level2", level2);
        Map<String, Object> params = Map.of("level1", level1);

        Map<String, Object> result = Sanitizer.sanitizeParams(params);
        Map<String, Object> res1 = (Map<String, Object>) result.get("level1");
        Map<String, Object> res2 = (Map<String, Object>) res1.get("level2");
        Map<String, Object> res3 = (Map<String, Object>) res2.get("level3");
        assertNotEquals(largeBase64, res3.get("data"),
                "Deeply nested large base64 should be redacted");
    }

    @Test
    void redactsBase64ExactlyAtSizeGateBoundary() {
        // ported from: "should redact base64 string at exactly SIZE_GATE boundary (10240 chars)"
        String atGate = makeLargeBase64(SIZE_GATE);
        String belowGate = makeLargeBase64(SIZE_GATE - 1);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("atGate", atGate);
        params.put("belowGate", belowGate);

        Map<String, Object> result = Sanitizer.sanitizeParams(params);
        assertNotEquals(atGate, result.get("atGate"),
                "String at exactly SIZE_GATE should be redacted");
        assertEquals(belowGate, result.get("belowGate"),
                "String just below SIZE_GATE should NOT be redacted");
    }

    @Test
    void doesNotMutateOriginalParamsMap() {
        // ported from: "should not mutate the original event"
        String largeBase64 = makeLargeBase64(12000);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("imageData", largeBase64);
        Sanitizer.sanitizeParams(params);
        assertEquals(largeBase64, params.get("imageData"),
                "Original params map must not be mutated");
    }
}
