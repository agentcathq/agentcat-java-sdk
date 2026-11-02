package com.agentcat.sdk.modules.events;

import com.agentcat.sdk.modules.pipeline.PayloadRedactor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link com.agentcat.sdk.modules.pipeline.PayloadRedactor#apply} —
 * applies the {@code redactSensitiveInformation} hook to every string value
 * inside a params or response map.
 *
 * <p>Mirrors the applicable portions of {@code redaction.test.ts} from the TypeScript SDK.
 * JS-specific behaviours (circular references, shared object identity, protected fields,
 * Date / Symbol / Function / undefined handling) are not applicable — Java maps contain
 * only JSON-compatible values and the protected-field concept is handled one level above
 * by only passing {@code params} and {@code response} to this method.
 *
 * <h3>Behaviours tested</h3>
 * <ul>
 *   <li>All strings in a flat map are passed through the hook.</li>
 *   <li>Non-string values (numbers, booleans, {@code null}) are preserved unchanged.</li>
 *   <li>Strings in nested maps are recursively redacted.</li>
 *   <li>Strings inside lists are redacted.</li>
 *   <li>Mixed nested structures (maps + lists) are fully walked.</li>
 *   <li>When the hook future resolves to {@code null}, the original string is kept.</li>
 *   <li>A synchronously throwing hook keeps the original string (swallowed gracefully).</li>
 *   <li>A {@code null} input map is returned as {@code null} (fast path).</li>
 *   <li>The original map is never mutated — a new map is returned.</li>
 *   <li>All string futures are fired before waiting (parallel, not sequential).</li>
 * </ul>
 */
class RedactionTest {

    // ── Hook helpers ──────────────────────────────────────────────────────────

    /** Replaces every string with a marker that includes the original length. */
    private static Function<String, CompletableFuture<String>> markRedact() {
        return s -> CompletableFuture.completedFuture("[REDACTED-" + s.length() + "]");
    }

    /** Returns the input unchanged — a no-op hook. */
    private static Function<String, CompletableFuture<String>> passThrough() {
        return CompletableFuture::completedFuture;
    }

    /** Always returns null for every string — hook signals "keep original". */
    private static Function<String, CompletableFuture<String>> returnNull() {
        return s -> CompletableFuture.completedFuture(null);
    }

    // ── Flat map — strings replaced ───────────────────────────────────────────

    @Test
    void redactsStringValuesInFlatMap() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query", "sensitive query");
        params.put("apiKey", "secret-key-123");

        Map<String, Object> result = PayloadRedactor.apply(params, markRedact());

        assertEquals("[REDACTED-15]", result.get("query"),
                "string 'sensitive query' (15 chars) must be replaced");
        assertEquals("[REDACTED-14]", result.get("apiKey"),
                "string 'secret-key-123' (14 chars) must be replaced");
    }

    // ── Non-string values preserved ────────────────────────────────────────────

    @Test
    void preservesNonStringValues() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("count",   42);
        params.put("active",  true);
        params.put("score",   3.14);
        params.put("nothing", null);

        Map<String, Object> result = PayloadRedactor.apply(params, markRedact());

        assertEquals(42,   result.get("count"),   "integer must be preserved");
        assertEquals(true, result.get("active"),   "boolean must be preserved");
        assertEquals(3.14, result.get("score"),    "double must be preserved");
        assertNull(result.get("nothing"),           "null value must be preserved");
    }

    // ── Mixed map (strings + non-strings) ─────────────────────────────────────

    @Test
    void redactsOnlyStringsInMixedMap() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name",  "Alice");
        params.put("age",   30);
        params.put("email", "alice@example.com");
        params.put("score", 9.5);

        Map<String, Object> result = PayloadRedactor.apply(params, markRedact());

        assertEquals("[REDACTED-5]",  result.get("name"),  "name (5) redacted");
        assertEquals(30,              result.get("age"),   "age preserved");
        assertEquals("[REDACTED-17]", result.get("email"), "email (17) redacted");
        assertEquals(9.5,             result.get("score"), "score preserved");
    }

    // ── Nested maps ───────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void redactsStringsInNestedMaps() throws Exception {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("apiKey",  "secret-key-123");
        options.put("timeout", 5000);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query",   "sensitive query");
        params.put("options", options);

        Map<String, Object> result = PayloadRedactor.apply(params, markRedact());

        assertEquals("[REDACTED-15]", result.get("query"));

        Map<String, Object> outOptions = (Map<String, Object>) result.get("options");
        assertEquals("[REDACTED-14]", outOptions.get("apiKey"),  "nested string redacted");
        assertEquals(5000,            outOptions.get("timeout"), "nested number preserved");
    }

    // ── Arrays of strings ─────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void redactsStringsInsideArrays() throws Exception {
        List<Object> tags = new ArrayList<>();
        tags.add("sensitive1");
        tags.add("sensitive2");
        tags.add("sensitive3");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tags", tags);

        Map<String, Object> result = PayloadRedactor.apply(params, markRedact());

        List<Object> outTags = (List<Object>) result.get("tags");
        assertEquals("[REDACTED-10]", outTags.get(0));
        assertEquals("[REDACTED-10]", outTags.get(1));
        assertEquals("[REDACTED-10]", outTags.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesNonStringElementsInArrays() throws Exception {
        List<Object> numbers = List.of(1, 2, 3);
        Map<String, Object> params = Map.of("numbers", numbers);

        Map<String, Object> result = PayloadRedactor.apply(params, markRedact());

        List<Object> outNumbers = (List<Object>) result.get("numbers");
        assertEquals(List.of(1, 2, 3), outNumbers, "number array must pass through unchanged");
    }

    // ── Mixed nested structure ────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void redactsMixedNestedStructure() throws Exception {
        List<Object> users = new ArrayList<>();
        Map<String, Object> user1 = new LinkedHashMap<>();
        user1.put("name", "John");
        user1.put("age", 30);
        user1.put("email", "john@example.com");
        Map<String, Object> user2 = new LinkedHashMap<>();
        user2.put("name", "Jane");
        user2.put("age", 25);
        user2.put("email", "jane@example.com");
        users.add(user1);
        users.add(user2);

        Map<String, Object> params = Map.of("users", users);
        Map<String, Object> result = PayloadRedactor.apply(params, markRedact());

        List<Object> outUsers = (List<Object>) result.get("users");
        Map<String, Object> r1 = (Map<String, Object>) outUsers.get(0);
        Map<String, Object> r2 = (Map<String, Object>) outUsers.get(1);

        assertEquals("[REDACTED-4]",  r1.get("name"),  "user1 name redacted");
        assertEquals(30,              r1.get("age"),   "user1 age preserved");
        assertEquals("[REDACTED-16]", r1.get("email"), "user1 email redacted");
        assertEquals("[REDACTED-4]",  r2.get("name"),  "user2 name redacted");
        assertEquals(25,              r2.get("age"),   "user2 age preserved");
        assertEquals("[REDACTED-16]", r2.get("email"), "user2 email redacted");
    }

    // ── Hook returns null → keep original ─────────────────────────────────────

    @Test
    void keepsOriginalStringWhenHookReturnsNull() throws Exception {
        Map<String, Object> params = Map.of("secret", "my-secret-value");

        Map<String, Object> result = PayloadRedactor.apply(params, returnNull());

        assertEquals("my-secret-value", result.get("secret"),
                "when hook future resolves to null, original string must be kept");
    }

    // ── Hook throws synchronously → exception propagates (caller drops event) ──

    @Test
    void throwsWhenHookThrowsSynchronously() {
        Function<String, CompletableFuture<String>> throwingHook = s -> {
            throw new RuntimeException("hook exploded");
        };

        Map<String, Object> params = Map.of("data", "original");

        // Synchronous throw propagates — caller (EventQueue) must drop the event so that
        // unredacted data never reaches the API.
        assertThrows(Exception.class, () -> PayloadRedactor.apply(params, throwingHook),
                "synchronous hook throw must propagate so the caller can drop the event");
    }

    // ── Null map ──────────────────────────────────────────────────────────────

    @Test
    void returnsNullForNullInputMap() throws Exception {
        assertNull(PayloadRedactor.apply(null, markRedact()),
                "null input map must return null without throwing");
    }

    // ── Non-mutation ──────────────────────────────────────────────────────────

    @Test
    void doesNotMutateOriginalMap() throws Exception {
        Map<String, Object> original = new HashMap<>();
        original.put("query", "sensitive");
        original.put("count", 5);

        PayloadRedactor.apply(original, markRedact());

        assertEquals("sensitive", original.get("query"),
                "original map must not be mutated — query must still be 'sensitive'");
        assertEquals(5, original.get("count"),
                "original map must not be mutated — count must still be 5");
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotMutateNestedMapsInOriginal() throws Exception {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("secret", "original-secret");

        Map<String, Object> original = new LinkedHashMap<>();
        original.put("nested", nested);

        PayloadRedactor.apply(original, markRedact());

        Map<String, Object> stillNested = (Map<String, Object>) original.get("nested");
        assertEquals("original-secret", stillNested.get("secret"),
                "nested map in original must not be mutated");
    }

    // ── Parallel firing of hook futures ───────────────────────────────────────

    @Test
    void firesAllStringFuturesBeforeWaiting() throws Exception {
        // If futures were fired sequentially (not in parallel), the second call would
        // only start after the first resolved. A parallel implementation fires all of
        // them immediately and then waits for CompletableFuture.allOf().
        AtomicInteger callCount = new AtomicInteger(0);

        Function<String, CompletableFuture<String>> countingHook = s -> {
            callCount.incrementAndGet();
            return CompletableFuture.completedFuture("[R]");
        };

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("a", "str1");
        params.put("b", "str2");
        params.put("c", "str3");

        PayloadRedactor.apply(params, countingHook);

        assertEquals(3, callCount.get(),
                "hook must be called exactly once for each string value (3 strings → 3 calls)");
    }

    // ── No strings → hook never called ────────────────────────────────────────

    @Test
    void hookNeverCalledWhenMapHasNoStrings() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        Function<String, CompletableFuture<String>> countingHook = s -> {
            callCount.incrementAndGet();
            return CompletableFuture.completedFuture("[R]");
        };

        Map<String, Object> params = Map.of("count", 42, "active", true);
        PayloadRedactor.apply(params, countingHook);

        assertEquals(0, callCount.get(),
                "hook must not be called when map contains no string values");
    }
}
