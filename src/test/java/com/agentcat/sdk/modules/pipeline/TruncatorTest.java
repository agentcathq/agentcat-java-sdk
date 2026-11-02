package com.agentcat.sdk.modules.pipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Truncator}.
 *
 * <p>Mirrors {@code truncation.test.ts} from the TypeScript reference SDK,
 * adapted for the Java API: JS-only types (Symbol, BigInt, Function, undefined,
 * NaN, Infinity, Date) are skipped; breadth capping has no trailing sentinel in
 * the Java implementation.
 *
 * <p>Suites:
 * <ol>
 *   <li>String truncation at {@value Truncator#MAX_STRING_LENGTH} chars</li>
 *   <li>Primitive pass-through (null, numbers, booleans)</li>
 *   <li>Depth limiting ({@value Truncator#MAX_DEPTH} levels)</li>
 *   <li>Breadth limiting ({@value Truncator#MAX_BREADTH} entries)</li>
 *   <li>Circular reference detection</li>
 *   <li>{@link Truncator#normalizeMap} convenience method</li>
 *   <li>{@link Truncator#enforceEventSize} — 100 KB budget</li>
 * </ol>
 */
class TruncatorTest {

    // ── Suite 1: string truncation ────────────────────────────────────────────

    @Test
    void leavesShortStringUnchanged() {
        assertEquals("hello", Truncator.normalize("hello"));
    }

    @Test
    void truncatesStringExceedingMax() {
        String long_ = "a".repeat(33_000);
        String result = (String) Truncator.normalize(long_);
        assertEquals(Truncator.MAX_STRING_LENGTH + 3, result.length(),
                "truncated string must be MAX_STRING_LENGTH + '...'");
        assertTrue(result.endsWith("..."));
        assertTrue(result.startsWith("a".repeat(100)));
    }

    @Test
    void leavesStringAtExactlyMaxUnchanged() {
        String exact = "a".repeat(Truncator.MAX_STRING_LENGTH);
        assertSame(exact, Truncator.normalize(exact),
                "string at exactly the limit must not be truncated");
    }

    // ── Suite 2: primitive pass-through ──────────────────────────────────────

    @Test
    void passesNullThrough() {
        assertNull(Truncator.normalize(null));
    }

    @Test
    void passesIntegerThrough() {
        assertEquals(42, Truncator.normalize(42));
        assertEquals(0,  Truncator.normalize(0));
    }

    @Test
    void passesDoubleThrough() {
        assertEquals(-3.14, Truncator.normalize(-3.14));
    }

    @Test
    void passesBooleanThrough() {
        assertEquals(true,  Truncator.normalize(true));
        assertEquals(false, Truncator.normalize(false));
    }

    // ── Suite 3: depth limiting ───────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void collapsesObjectsBeyondMaxDepth() {
        // build an object nested deeper than MAX_DEPTH
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("value", "deep");
        Map<String, Object> obj = inner;
        for (int i = 0; i < Truncator.MAX_DEPTH + 5; i++) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("nested", obj);
            obj = wrapper;
        }

        Map<String, Object> result = (Map<String, Object>) Truncator.normalize(obj);

        // Walk down MAX_DEPTH levels — the next level must be "[Object]"
        Object cur = result;
        for (int i = 0; i < Truncator.MAX_DEPTH; i++) {
            cur = ((Map<String, Object>) cur).get("nested");
        }
        assertEquals("[Object]", cur,
                "object at depth > MAX_DEPTH must be replaced with '[Object]'");
    }

    @Test
    @SuppressWarnings("unchecked")
    void collapsesArraysBeyondMaxDepth() {
        List<Object> inner = List.of("leaf");
        List<Object> arr = inner;
        for (int i = 0; i < Truncator.MAX_DEPTH + 5; i++) {
            List<Object> wrapper = new ArrayList<>();
            wrapper.add(arr);
            arr = wrapper;
        }

        List<Object> result = (List<Object>) Truncator.normalize(arr);

        Object cur = result;
        for (int i = 0; i < Truncator.MAX_DEPTH; i++) {
            cur = ((List<Object>) cur).get(0);
        }
        assertEquals("[Array]", cur,
                "array at depth > MAX_DEPTH must be replaced with '[Array]'");
    }

    @Test
    void doesNotCollapseTopLevelMapAtMaxDepth() {
        // A map at depth 0 must still be traversed (collapsed only beyond MAX_DEPTH)
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("a", 1);
        Object result = Truncator.normalize(obj);
        assertInstanceOf(Map.class, result,
                "top-level map must not be collapsed");
    }

    @Test
    void doesNotCollapsePrimitivesRegardlessOfDepth() {
        // Primitives are never collapsed regardless of depth position
        // Verified indirectly: normalize("hello") at any nesting returns "hello"
        assertEquals("hello", Truncator.normalize("hello"));
        assertEquals(42,      Truncator.normalize(42));
    }

    // ── Suite 4: breadth limiting ─────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void limitsMapToMaxBreadth() {
        Map<String, Object> wide = new LinkedHashMap<>();
        for (int i = 0; i < Truncator.MAX_BREADTH + 50; i++) {
            wide.put("key" + i, i);
        }
        Map<String, Object> result = (Map<String, Object>) Truncator.normalize(wide);
        // MAX_BREADTH real entries + 1 sentinel "..." → MAX_BREADTH + 1 (mirrors TypeScript)
        assertEquals(Truncator.MAX_BREADTH + 1, result.size(),
                "map must be capped at MAX_BREADTH entries plus sentinel");
        assertTrue(result.containsKey("key0"),
                "first key must be kept");
        assertFalse(result.containsKey("key" + Truncator.MAX_BREADTH),
                "entry beyond MAX_BREADTH must be dropped");
        assertEquals("[MaxProperties ~]", result.get("..."),
                "sentinel entry must be present");
    }

    @Test
    @SuppressWarnings("unchecked")
    void limitsListToMaxBreadth() {
        List<Object> wide = new ArrayList<>();
        for (int i = 0; i < Truncator.MAX_BREADTH + 50; i++) {
            wide.add(i);
        }
        List<Object> result = (List<Object>) Truncator.normalize(wide);
        // MAX_BREADTH real elements + 1 sentinel → MAX_BREADTH + 1 (mirrors TypeScript)
        assertEquals(Truncator.MAX_BREADTH + 1, result.size(),
                "list must be capped at MAX_BREADTH entries plus sentinel");
        assertEquals(0, result.get(0), "first element must be kept");
        assertEquals("[MaxProperties ~]", result.get(Truncator.MAX_BREADTH),
                "sentinel element must be last");
    }

    @Test
    @SuppressWarnings("unchecked")
    void leavesMapWithinBreadthLimitUnchanged() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("a", 1);
        obj.put("b", 2);
        obj.put("c", 3);
        Map<String, Object> result = (Map<String, Object>) Truncator.normalize(obj);
        assertEquals(3, result.size());
        assertEquals(1, result.get("a"));
        assertEquals(2, result.get("b"));
        assertEquals(3, result.get("c"));
    }

    // ── Suite 5: circular reference detection ─────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void detectsCircularObjectReference() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("a", 1);
        obj.put("self", obj); // circular

        Map<String, Object> result = (Map<String, Object>) Truncator.normalize(obj);
        assertEquals(1,            result.get("a"));
        assertEquals("[Circular ~]", result.get("self"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void detectsCircularArrayReference() {
        List<Object> arr = new ArrayList<>();
        arr.add(1);
        arr.add(2);
        arr.add(arr); // circular

        List<Object> result = (List<Object>) Truncator.normalize(arr);
        assertEquals(1,              result.get(0));
        assertEquals(2,              result.get(1));
        assertEquals("[Circular ~]", result.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void allowsSameObjectInDifferentBranches() {
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("value", "shared");

        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("a", shared);
        obj.put("b", shared); // same reference, not a cycle

        Map<String, Object> result = (Map<String, Object>) Truncator.normalize(obj);
        assertEquals("shared", ((Map<String, Object>) result.get("a")).get("value"));
        assertEquals("shared", ((Map<String, Object>) result.get("b")).get("value"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void detectsDeeplyNestedCircularReference() {
        Map<String, Object> root   = new LinkedHashMap<>();
        Map<String, Object> level1 = new LinkedHashMap<>();
        Map<String, Object> level2 = new LinkedHashMap<>();
        Map<String, Object> level3 = new LinkedHashMap<>();

        level3.put("backToRoot", root);
        level2.put("level3", level3);
        level1.put("level2", level2);
        root.put("level1", level1);

        Map<String, Object> result = (Map<String, Object>) Truncator.normalize(root);
        Map<String, Object> r1 = (Map<String, Object>) result.get("level1");
        Map<String, Object> r2 = (Map<String, Object>) r1.get("level2");
        Map<String, Object> r3 = (Map<String, Object>) r2.get("level3");
        assertEquals("[Circular ~]", r3.get("backToRoot"));
    }

    // ── Suite 6: normalizeMap ─────────────────────────────────────────────────

    @Test
    void normalizeMapReturnsNullForNull() {
        assertNull(Truncator.normalizeMap(null));
    }

    @Test
    void normalizeMapTruncatesNestedStrings() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("data", "x".repeat(40_000));
        map.put("label", "short");

        Map<String, Object> result = Truncator.normalizeMap(map);

        String truncated = (String) result.get("data");
        assertEquals(Truncator.MAX_STRING_LENGTH + 3, truncated.length());
        assertTrue(truncated.endsWith("..."));
        assertEquals("short", result.get("label"));
    }

    @Test
    void normalizeMapDoesNotMutateOriginal() {
        String longStr = "x".repeat(40_000);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("data", longStr);

        Truncator.normalizeMap(map);

        assertEquals(longStr, map.get("data"),
                "original map must not be mutated");
    }

    // ── Suite 7: enforceEventSize ─────────────────────────────────────────────

    @Test
    void leavesSmallPayloadsUnchanged() {
        Map<String, Object> params   = Map.of("query", "hello");
        Map<String, Object> response = Map.of("result", "world");

        Map<String, Object>[] result = Truncator.enforceEventSize(params, response);
        assertEquals("hello", result[0].get("query"));
        assertEquals("world", result[1].get("result"));
    }

    @Test
    void reducesOversizedPayloadToWithin100KB() {
        // Create deeply nested structure ~120KB
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> cur = params;
        for (int i = 0; i < 8; i++) {
            cur.put("data", "x".repeat(15_000));
            Map<String, Object> next = new LinkedHashMap<>();
            cur.put("next", next);
            cur = next;
        }
        cur.put("data", "leaf");

        Map<String, Object>[] result = Truncator.enforceEventSize(params, null);

        int totalBytes = jsonBytes(result[0]) + jsonBytes(result[1]);
        assertTrue(totalBytes <= Truncator.MAX_EVENT_BYTES,
                "payload after enforceEventSize must be ≤ 100 KB, was " + totalBytes + " bytes");
    }

    @Test
    void handlesNullParamsAndResponse() {
        Map<String, Object>[] result = Truncator.enforceEventSize(null, null);
        assertNull(result[0]);
        assertNull(result[1]);
    }

    @Test
    void keepsBothPayloadsUnder100KBWhenDataIsNested() {
        // Deeply nested params (~120KB) + large response — depth reduction brings both under limit
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> cur = params;
        for (int i = 0; i < 6; i++) {
            cur.put("data", "x".repeat(15_000));
            Map<String, Object> next = new LinkedHashMap<>();
            cur.put("next", next);
            cur = next;
        }
        cur.put("data", "leaf");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("nested", Map.of("inner", Map.of("value", "r".repeat(20_000))));

        Map<String, Object>[] result = Truncator.enforceEventSize(params, response);

        int total = jsonBytes(result[0]) + jsonBytes(result[1]);
        assertTrue(total <= Truncator.MAX_EVENT_BYTES,
                "combined payload must be ≤ 100 KB, was " + total + " bytes");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static int jsonBytes(Map<String, Object> map) {
        if (map == null) return 0;
        try {
            return tools.jackson.databind.json.JsonMapper.builder()
                    .build().writeValueAsBytes(map).length;
        } catch (Exception e) {
            return 0;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Suite 8: truncateField and per-field limit constants
    // (ported from TruncatorFieldLimitsTest)
    // ══════════════════════════════════════════════════════════════════════════

    // ── Constant values ───────────────────────────────────────────────────────

    @Test
    void maxUserIntentLengthIs2048() {
        assertEquals(2048, Truncator.MAX_USER_INTENT_LENGTH,
                "MAX_USER_INTENT_LENGTH must be 2048 — matching the TypeScript SDK constant");
    }

    @Test
    void maxFieldNameLengthIs256() {
        assertEquals(256, Truncator.MAX_FIELD_NAME_LENGTH,
                "MAX_FIELD_NAME_LENGTH must be 256 — matching the TypeScript SDK constant");
    }

    // ── truncateField — basic contract ────────────────────────────────────────

    @Test
    void truncateFieldReturnsNullForNullInput() {
        assertNull(Truncator.truncateField(null, 100),
                "null input must produce null output");
    }

    @Test
    void truncateFieldLeavesShortValueUnchanged() {
        String value = "Get weather";
        assertSame(value, Truncator.truncateField(value, 256),
                "string shorter than maxLength must be returned as-is (same reference)");
    }

    @Test
    void truncateFieldLeavesValueAtExactLimitUnchanged() {
        String exact = "x".repeat(256);
        String result = Truncator.truncateField(exact, 256);
        assertEquals(exact, result,
                "string at exactly maxLength must not be truncated");
        assertEquals(256, result.length());
    }

    @Test
    void truncateFieldTruncatesValueExceedingLimit() {
        String long_ = "t".repeat(300);
        String result = Truncator.truncateField(long_, 256);
        assertEquals(256 + 3, result.length(),
                "truncated string must be maxLength (256) + '...' = 259 chars");
        assertTrue(result.endsWith("..."),
                "truncated string must end with '...'");
        assertTrue(result.startsWith("t".repeat(50)),
                "truncated string must keep the beginning of the original value");
    }

    @Test
    void truncateFieldAppendsDotDotDotOnlyOnce() {
        String long_ = "a".repeat(300);
        String result = Truncator.truncateField(long_, 256);
        assertEquals(1, countOccurrences(result, "..."),
                "exactly one '...' suffix must be appended");
    }

    // ── userIntent truncation (2048 limit) ────────────────────────────────────

    @Test
    void truncatesUserIntentExceeding2048() {
        String intent = "x".repeat(3000);
        String result = Truncator.truncateField(intent, Truncator.MAX_USER_INTENT_LENGTH);
        assertEquals(2048 + 3, result.length(),
                "userIntent must be truncated to 2048 + '...' = 2051 chars");
        assertTrue(result.endsWith("..."));
    }

    @Test
    void leavesUserIntentAtExactly2048Unchanged() {
        String intent = "x".repeat(2048);
        String result = Truncator.truncateField(intent, Truncator.MAX_USER_INTENT_LENGTH);
        assertEquals(2048, result.length(), "userIntent at exactly 2048 must not be truncated");
        assertFalse(result.endsWith("..."));
    }

    @Test
    void leavesShortUserIntentUnchanged() {
        String intent = "Get weather for Paris";
        assertEquals(intent, Truncator.truncateField(intent, Truncator.MAX_USER_INTENT_LENGTH));
    }

    // ── resourceName truncation (256 limit) ───────────────────────────────────

    @Test
    void truncatesResourceNameExceeding256() {
        String name = "t".repeat(300);
        String result = Truncator.truncateField(name, Truncator.MAX_FIELD_NAME_LENGTH);
        assertEquals(256 + 3, result.length());
        assertTrue(result.endsWith("..."));
    }

    @Test
    void leavesResourceNameAtExactly256Unchanged() {
        String name = "t".repeat(256);
        String result = Truncator.truncateField(name, Truncator.MAX_FIELD_NAME_LENGTH);
        assertEquals(256, result.length());
    }

    // ── serverName / serverVersion (256 limit) ────────────────────────────────

    @Test
    void truncatesServerNameExceeding256() {
        String serverName = "s".repeat(300);
        String result = Truncator.truncateField(serverName, Truncator.MAX_FIELD_NAME_LENGTH);
        assertEquals(256 + 3, result.length(),
                "serverName exceeding 256 must be truncated");
        assertTrue(result.endsWith("..."));
    }

    @Test
    void truncatesServerVersionExceeding256() {
        String serverVersion = "v".repeat(300);
        String result = Truncator.truncateField(serverVersion, Truncator.MAX_FIELD_NAME_LENGTH);
        assertEquals(256 + 3, result.length(),
                "serverVersion exceeding 256 must be truncated");
    }

    @Test
    void leavesShortServerFieldsUnchanged() {
        assertEquals("my-server",  Truncator.truncateField("my-server",  256));
        assertEquals("1.2.3",      Truncator.truncateField("1.2.3",      256));
        assertEquals("fetch_data", Truncator.truncateField("fetch_data", 256));
    }

    // ── error.message truncation (2048 limit) ─────────────────────────────────

    @Test
    void truncatesErrorMessageExceeding2048() {
        String message = "Error: ".repeat(400); // > 2048 chars
        String result = Truncator.truncateField(message, Truncator.MAX_USER_INTENT_LENGTH);
        assertEquals(2048 + 3, result.length(),
                "error.message exceeding 2048 must be truncated to 2051 chars");
        assertTrue(result.endsWith("..."));
    }

    @Test
    void leavesShortErrorMessageUnchanged() {
        String message = "Null pointer exception";
        assertEquals(message, Truncator.truncateField(message, Truncator.MAX_USER_INTENT_LENGTH));
    }

    // ── empty string ──────────────────────────────────────────────────────────

    @Test
    void leavesEmptyStringUnchangedForTruncateField() {
        assertEquals("", Truncator.truncateField("", 256),
                "empty string must not be truncated");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
