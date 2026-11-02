package com.agentcat.sdk.ported;

import com.agentcat.sdk.modules.pipeline.Truncator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/truncation.test.ts
 *
 * TypeScript {@code normalize(value, depth, breadth)} → Java {@link Truncator#normalize(Object, int, int, Set)}
 * TypeScript {@code truncateEvent(event)} → Java field-level truncation via {@link Truncator#truncateField}
 * and normalize.
 */
class TruncationTest {

    // ── Helpers delegating to public API ─────────────────────────────────────

    private static Object normalize(Object value) {
        return Truncator.normalize(value);
    }

    private static Object normalize(Object value, int maxDepth) {
        return Truncator.normalize(value, maxDepth);
    }

    private static Object normalize(Object value, int maxDepth, int maxBreadth) {
        return Truncator.normalize(value, maxDepth, maxBreadth);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // normalize — string truncation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void leavesShortStringsUnchanged() throws Exception {
        assertEquals("hello", normalize("hello"));
    }

    @Test
    void truncatesStringsExceedingMaxStringLength() throws Exception {
        String longStr = "a".repeat(33000);
        Object result = normalize(longStr);
        assertTrue(result instanceof String);
        String s = (String) result;
        assertEquals(Truncator.MAX_STRING_LENGTH + 3, s.length());
        assertTrue(s.endsWith("..."));
    }

    @Test
    void leavesStringsAtExactMaxStringLengthUnchanged() throws Exception {
        String exact = "a".repeat(Truncator.MAX_STRING_LENGTH);
        assertEquals(exact, normalize(exact));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // normalize — depth limiting
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void collapsesObjectsBeyondMaxDepth() throws Exception {
        // ported from: "should collapse objects beyond max depth to '[Object]'"
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("value", "deep");
        for (int i = 0; i < 15; i++) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("nested", obj);
            obj = wrapper;
        }
        Object result = normalize(obj, 5);
        assertTrue(result instanceof Map);
        Map<String, Object> r = (Map<String, Object>) result;
        // Dive 5 levels
        Object cur = r;
        for (int i = 0; i < 5; i++) {
            cur = ((Map<String, Object>) cur).get("nested");
        }
        assertEquals("[Object]", cur);
    }

    @Test
    @SuppressWarnings("unchecked")
    void collapsesArraysBeyondMaxDepth() throws Exception {
        // ported from: "should collapse arrays beyond max depth to '[Array]'"
        Object arr = List.of("leaf");
        for (int i = 0; i < 15; i++) {
            arr = new ArrayList<>(List.of(arr));
        }
        Object result = normalize(arr, 3);
        assertTrue(result instanceof List);
        List<?> r = (List<?>) result;
        // Dive 3 levels (index 0 each)
        Object cur = ((List<?>) r).get(0);
        cur = ((List<?>) cur).get(0);
        cur = ((List<?>) cur).get(0);
        assertEquals("[Array]", cur);
    }

    @Test
    void doesNotCollapsePrimitivesRegardlessOfDepth() throws Exception {
        // ported from: "should not collapse primitives regardless of depth"
        assertEquals("hello", normalize("hello", 0));
        assertEquals(42, normalize(42, 0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // normalize — breadth limiting
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void limitsObjectPropertiesToMaxBreadth() throws Exception {
        // ported from: "should limit object properties to maxBreadth"
        Map<String, Object> wide = new LinkedHashMap<>();
        for (int i = 0; i < 150; i++) wide.put("key" + i, i);
        Object result = normalize(wide, 10, 5);
        assertTrue(result instanceof Map);
        Map<String, Object> r = (Map<String, Object>) result;
        assertEquals(6, r.size(), "5 real keys + 1 sentinel"); // 5 + sentinel
        assertEquals("[MaxProperties ~]", r.get("..."));
    }

    @Test
    @SuppressWarnings("unchecked")
    void limitsArrayElementsToMaxBreadth() throws Exception {
        // ported from: "should limit array elements to maxBreadth"
        List<Integer> wide = new ArrayList<>();
        for (int i = 0; i < 150; i++) wide.add(i);
        Object result = normalize(wide, 10, 5);
        assertTrue(result instanceof List);
        List<?> r = (List<?>) result;
        assertEquals(6, r.size(), "5 real elements + 1 sentinel");
        assertEquals("[MaxProperties ~]", r.get(5));
    }

    @Test
    @SuppressWarnings("unchecked")
    void leavesObjectsWithinBreadthLimitUnchanged() throws Exception {
        // ported from: "should leave objects within breadth limit unchanged"
        Map<String, Object> obj = Map.of("a", 1, "b", 2, "c", 3);
        Object result = normalize(obj, 10, 100);
        assertTrue(result instanceof Map);
        Map<String, Object> r = (Map<String, Object>) result;
        assertEquals(3, r.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // normalize — circular reference detection
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void detectsCircularObjectReferences() throws Exception {
        // ported from: "should detect circular object references"
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("a", 1);
        obj.put("self", obj); // circular!
        Object result = normalize(obj);
        assertTrue(result instanceof Map);
        Map<String, Object> r = (Map<String, Object>) result;
        assertEquals(1, r.get("a"));
        assertEquals("[Circular ~]", r.get("self"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void detectsCircularArrayReferences() throws Exception {
        // ported from: "should detect circular array references"
        List<Object> arr = new ArrayList<>();
        arr.add(1);
        arr.add(2);
        arr.add(arr); // circular!
        Object result = normalize(arr);
        assertTrue(result instanceof List);
        List<?> r = (List<?>) result;
        assertEquals(1, r.get(0));
        assertEquals(2, r.get(1));
        assertEquals("[Circular ~]", r.get(2));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // truncateField (mirrors TypeScript field-level truncation)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void truncatesUserIntentExceeding2048Chars() {
        // ported from: "should truncate userIntent exceeding 2048 chars"
        String longIntent = "x".repeat(3000);
        String result = Truncator.truncateField(longIntent, Truncator.MAX_USER_INTENT_LENGTH);
        assertNotNull(result);
        assertEquals(Truncator.MAX_USER_INTENT_LENGTH + 3, result.length());
        assertTrue(result.endsWith("..."));
    }

    @Test
    void truncatesResourceNameExceeding256Chars() {
        // ported from: "should truncate resourceName exceeding 256 chars"
        String longName = "t".repeat(300);
        String result = Truncator.truncateField(longName, Truncator.MAX_FIELD_NAME_LENGTH);
        assertNotNull(result);
        assertEquals(Truncator.MAX_FIELD_NAME_LENGTH + 3, result.length());
        assertTrue(result.endsWith("..."));
    }

    @Test
    void leavesShortFieldValuesUnchanged() {
        // ported from: "should leave short field values unchanged"
        assertEquals("Get weather", Truncator.truncateField("Get weather", Truncator.MAX_USER_INTENT_LENGTH));
        assertEquals("fetch_weather", Truncator.truncateField("fetch_weather", Truncator.MAX_FIELD_NAME_LENGTH));
    }

    @Test
    void handlesNullFieldGracefully() {
        // ported from: "should handle undefined/null fields gracefully"
        assertNull(Truncator.truncateField(null, Truncator.MAX_USER_INTENT_LENGTH));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // More normalize edge cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void handlesDepthZeroByCollapsingTopLevelObjects() throws Exception {
        // ported from: "should handle depth=0 by collapsing top-level objects"
        assertEquals("[Object]", normalize(Map.of("a", 1), 0));
        assertEquals("[Array]", normalize(List.of(1, 2), 0));
    }

    @Test
    void passesNumbersUnchanged() throws Exception {
        assertEquals(42, normalize(42));
        assertEquals(0, normalize(0));
    }

    @Test
    void passesBooleansUnchanged() throws Exception {
        assertEquals(true, normalize(true));
        assertEquals(false, normalize(false));
    }

    @Test
    void passesNullAsNull() throws Exception {
        assertNull(normalize(null));
    }
}
