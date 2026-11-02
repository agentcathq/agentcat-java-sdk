package com.agentcat.sdk.modules.events;

import com.agentcat.sdk.model.EventData;
import com.agentcat.sdk.model.PendingHooks;
import com.agentcat.sdk.model.QueuedEvent;
import com.agentcat.sdk.modules.events.TagValidator;
import com.agentcat.sdk.modules.pipeline.PayloadRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TagValidator#validate} — the tag validation logic
 * that runs in Stage 0 of the event pipeline before the event is sent to the API.
 *
 * <p>Rules under test:
 * <ul>
 *   <li>Key: non-empty, max 32 chars, pattern {@code [a-zA-Z0-9$_.:- ]+}</li>
 *   <li>Value: non-null string, max 200 chars, no newline/carriage-return</li>
 *   <li>Total entries: at most 50 (first 50 kept in insertion order)</li>
 * </ul>
 */
class EventQueueTest {

    @BeforeEach
    void resetQueue() throws InterruptedException {
        EventQueue.getInstance().resetForTesting(10_000);
    }

    /**
     * Valid tags must pass through unchanged.
     */
    @Test
    void acceptsValidTags() {
        Map<String, String> tags = Map.of("env", "prod", "region", "us-east-1");
        Map<String, String> validated = TagValidator.validate(tags);
        assertNotNull(validated, "valid tags map must not be nulled out");
        assertEquals("prod", validated.get("env"));
        assertEquals("us-east-1", validated.get("region"));
    }

    /**
     * A tag key exceeding 32 characters must be silently dropped.
     */
    @Test
    void dropsKeyTooLong() {
        String longKey = "k".repeat(33);
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(longKey, "value");
        tags.put("ok", "v");
        Map<String, String> validated = TagValidator.validate(tags);
        assertNotNull(validated);
        assertFalse(validated.containsKey(longKey), "key longer than 32 chars must be dropped");
        assertTrue(validated.containsKey("ok"), "valid key must remain");
    }

    /**
     * A tag key at exactly the 32-character limit must be accepted.
     */
    @Test
    void acceptsKeyAtMaxLength() {
        String exactKey = "k".repeat(32);
        Map<String, String> tags = Map.of(exactKey, "value");
        Map<String, String> validated = TagValidator.validate(tags);
        assertNotNull(validated);
        assertTrue(validated.containsKey(exactKey), "key of exactly 32 chars must be accepted");
    }

    /**
     * A tag key with characters outside [a-zA-Z0-9$_.:- ] must be dropped.
     */
    @Test
    void dropsKeyWithInvalidChars() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("valid-key", "value");
        tags.put("inv@lid!", "value");
        Map<String, String> validated = TagValidator.validate(tags);
        assertNotNull(validated);
        assertTrue(validated.containsKey("valid-key"), "valid key must remain");
        assertFalse(validated.containsKey("inv@lid!"), "key with @ and ! must be dropped");
    }

    /**
     * Allowed special characters in key ($, _, ., :, -, space) must be accepted.
     */
    @Test
    void acceptsAllowedSpecialCharsInKey() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("$_.-: key", "value");
        Map<String, String> validated = TagValidator.validate(tags);
        assertNotNull(validated);
        assertTrue(validated.containsKey("$_.-: key"), "allowed special chars in key must be accepted");
    }

    /**
     * A tag value exceeding 200 characters must be dropped.
     */
    @Test
    void dropsValueTooLong() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("long-val", "v".repeat(201));
        tags.put("ok", "short");
        Map<String, String> validated = TagValidator.validate(tags);
        assertNotNull(validated);
        assertFalse(validated.containsKey("long-val"), "value longer than 200 chars must be dropped");
        assertTrue(validated.containsKey("ok"), "valid entry must remain");
    }

    /**
     * A tag value at exactly the 200-character limit must be accepted.
     */
    @Test
    void acceptsValueAtMaxLength() {
        String exactValue = "v".repeat(200);
        Map<String, String> tags = Map.of("key", exactValue);
        Map<String, String> validated = TagValidator.validate(tags);
        assertNotNull(validated);
        assertEquals(exactValue, validated.get("key"), "value of exactly 200 chars must be accepted");
    }

    /**
     * A tag value containing a newline (LF) must be dropped.
     */
    @Test
    void dropsValueWithLineFeed() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("has-lf", "value\nwith newline");
        tags.put("ok", "clean");
        Map<String, String> validated = TagValidator.validate(tags);
        assertNotNull(validated);
        assertFalse(validated.containsKey("has-lf"), "value with LF must be dropped");
        assertTrue(validated.containsKey("ok"), "clean entry must remain");
    }

    /**
     * A tag value containing a carriage return (CR) must be dropped.
     */
    @Test
    void dropsValueWithCarriageReturn() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("has-cr", "value\rwith cr");
        tags.put("ok", "clean");
        Map<String, String> validated = TagValidator.validate(tags);
        assertNotNull(validated);
        assertFalse(validated.containsKey("has-cr"), "value with CR must be dropped");
    }

    /**
     * More than 50 valid tag entries must be truncated to exactly 50 (insertion-order).
     */
    @Test
    void truncatesTo50Entries() {
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 0; i < 60; i++) {
            tags.put("key" + i, "val" + i);
        }
        Map<String, String> validated = TagValidator.validate(tags);
        assertNotNull(validated);
        assertEquals(50, validated.size(), "must keep at most 50 tag entries");
        // First 50 keys must be present, last 10 must be dropped
        assertTrue(validated.containsKey("key0"),  "key0 (first) must be kept");
        assertTrue(validated.containsKey("key49"), "key49 (50th) must be kept");
        assertFalse(validated.containsKey("key50"), "key50 (51st) must be dropped");
    }

    /**
     * An empty tags map must return null (no data to send).
     */
    @Test
    void emptyMapReturnsNull() {
        assertNull(TagValidator.validate(Map.of()),
                "empty tags map must yield null");
    }

    /**
     * A null input must return null without throwing.
     */
    @Test
    void nullInputReturnsNull() {
        assertNull(TagValidator.validate(null),
                "null tags input must yield null without throwing");
    }

    /**
     * All entries invalid → returns null (nothing left after filtering).
     */
    @Test
    void allInvalidEntriesYieldNull() {
        Map<String, String> tags = Map.of("inv@lid", "ok");
        assertNull(TagValidator.validate(tags),
                "all-invalid tags must yield null");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // submit()
    // ══════════════════════════════════════════════════════════════════════════

    /** Builds a minimal valid {@link QueuedEvent} for submit tests. */
    private static QueuedEvent minimalEvent(String toolName) {
        return new QueuedEvent(
                new EventData("proj", "ses_test", toolName, null, null, 0L, false, null, null, null, null),
                null, null);
    }

    @Test
    void submitDoesNotThrow() {
        assertDoesNotThrow(() ->
                EventQueue.getInstance().submit(minimalEvent("submit_test")),
                "submit() must never throw for a valid event");
    }

    @Test
    void submitAcceptsNullParams() {
        // QueuedEvent with null params/response must be accepted without exception
        assertDoesNotThrow(() ->
                EventQueue.getInstance().submit(minimalEvent("null_params_tool")));
    }

    @Test
    void submitWhenShutdownDropsEventSilently() throws Exception {
        // Use reflection to set shutdown=true on the singleton, submit an event,
        // then restore shutdown=false so the queue remains functional for other tests.
        EventQueue eq = EventQueue.getInstance();
        Field shutdownField = EventQueue.class.getDeclaredField("shutdown");
        shutdownField.setAccessible(true);

        shutdownField.set(eq, true);
        try {
            assertDoesNotThrow(() -> eq.submit(minimalEvent("dropped_event")),
                    "submit() when shutdown must silently drop the event without throwing");
        } finally {
            shutdownField.set(eq, false); // restore for other tests
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // applyStringRedaction()  — package-private static, fully testable
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void applyStringRedactionReturnsNullForNullInput() throws Exception {
        assertNull(PayloadRedactor.apply(null, s -> CompletableFuture.completedFuture("[REDACTED]")),
                "null input map must produce null output");
    }

    @Test
    void applyStringRedactionReturnsEmptyMapForEmptyInput() throws Exception {
        Map<String, Object> result = PayloadRedactor.apply(
                Map.of(), s -> CompletableFuture.completedFuture("[X]"));
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void applyStringRedactionReplacesStringValues() throws Exception {
        Map<String, Object> input = Map.of("key", "secret_value");
        Map<String, Object> result = PayloadRedactor.apply(
                input, s -> CompletableFuture.completedFuture("[REDACTED]"));
        assertEquals("[REDACTED]", result.get("key"),
                "string values must be replaced by the redaction hook");
    }

    @Test
    void applyStringRedactionPreservesNonStringValues() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("number", 42);
        input.put("flag",   true);
        input.put("text",   "redact_me");
        Map<String, Object> result = PayloadRedactor.apply(
                input, s -> CompletableFuture.completedFuture("[X]"));
        assertEquals(42,   result.get("number"), "integer values must pass through unchanged");
        assertEquals(true, result.get("flag"),   "boolean values must pass through unchanged");
    }

    @Test
    void applyStringRedactionThrowsWhenHookThrowsSynchronously() {
        Map<String, Object> input = Map.of("key", "original");
        // Hook throws synchronously — exception propagates so the caller can drop the event.
        // This is the safe behavior: unredacted data must never reach the API.
        assertThrows(Exception.class, () -> PayloadRedactor.apply(
                input, s -> { throw new RuntimeException("redact hook exploded"); }),
                "applyStringRedaction must propagate synchronous hook throws so the caller drops the event");
    }

    @Test
    void applyStringRedactionThrowsWhenHookFutureCompletesExceptionally() {
        Map<String, Object> input = Map.of("key", "original_value");
        // Hook returns a future that completes exceptionally — must also propagate.
        assertThrows(Exception.class, () -> PayloadRedactor.apply(
                input, s -> CompletableFuture.failedFuture(new RuntimeException("boom"))),
                "applyStringRedaction must propagate exceptional futures so the caller drops the event");
    }

    @Test
    void applyStringRedactionFallsBackToOriginalWhenHookReturnsNull() throws Exception {
        Map<String, Object> input = Map.of("text", "keep_me");
        Map<String, Object> result = PayloadRedactor.apply(
                input, s -> CompletableFuture.completedFuture(null));
        // Null from hook → original preserved (line: resolved != null ? resolved : originals.get(i))
        assertEquals("keep_me", result.get("text"),
                "null returned by hook must fall back to the original string");
    }

    @Test
    void applyStringRedactionHandlesNestedMaps() throws Exception {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("inner", "inner_value");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("outer", nested);
        Map<String, Object> result = PayloadRedactor.apply(
                input, s -> CompletableFuture.completedFuture("*"));
        @SuppressWarnings("unchecked")
        Map<String, Object> resultNested = (Map<String, Object>) result.get("outer");
        assertNotNull(resultNested);
        assertEquals("*", resultNested.get("inner"),
                "redaction must recurse into nested map values");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // drain()  — private, tested via reflection
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Invokes the private {@code awaitPendingEventsOnShutdown()} method, then
     * restores the singleton via {@code resetForTesting()}.
     */
    private static void invokeDrain() throws Exception {
        EventQueue eq = EventQueue.getInstance();
        Method drain = EventQueue.class.getDeclaredMethod("awaitPendingEventsOnShutdown");
        drain.setAccessible(true);
        try {
            drain.invoke(eq);
        } finally {
            eq.resetForTesting(5_000);
        }
    }

    @Test
    void drainDoesNotThrowOnEmptyQueue() {
        assertDoesNotThrow(EventQueueTest::invokeDrain,
                "drain() must not propagate any exception");
    }

    @Test
    void drainSetsShutdownTrue() throws Exception {
        EventQueue eq = EventQueue.getInstance();
        Field shutdownField = EventQueue.class.getDeclaredField("shutdown");
        shutdownField.setAccessible(true);
        Method drain = EventQueue.class.getDeclaredMethod("awaitPendingEventsOnShutdown");
        drain.setAccessible(true);
        try {
            drain.invoke(eq);
            assertTrue((Boolean) shutdownField.get(eq),
                    "drain() must set shutdown=true so new submits are rejected");
        } finally {
            eq.resetForTesting(5_000);
        }
    }

    @Test
    void drainReturnsImmediatelyWhenQueueIsEmpty() throws Exception {
        // Ensure clean state — no stale workers holding permits from prior tests.
        EventQueue.getInstance().resetForTesting(10_000);

        // Empty queue + all concurrency permits available → while-loop body never executes.
        // Must complete well within the 5 s drain timeout.
        long start = System.currentTimeMillis();
        assertDoesNotThrow(EventQueueTest::invokeDrain);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 2_000,
                "drain() on an empty queue must complete quickly, not wait for the full 5 s timeout");
    }

    @Test
    void drainCanBeInterrupted() throws Exception {
        EventQueue eq = EventQueue.getInstance();
        Field shutdownField = EventQueue.class.getDeclaredField("shutdown");
        shutdownField.setAccessible(true);
        Method drain = EventQueue.class.getDeclaredMethod("awaitPendingEventsOnShutdown");
        drain.setAccessible(true);
        Field semField = EventQueue.class.getDeclaredField("concurrency");
        semField.setAccessible(true);
        java.util.concurrent.Semaphore sem =
                (java.util.concurrent.Semaphore) semField.get(eq);

        sem.acquire(); // simulate one in-flight event so drain() enters its wait loop
        Thread drainThread = new Thread(() -> {
            try { drain.invoke(eq); } catch (Exception ignored) {}
        });
        try {
            drainThread.start();
            Thread.sleep(60); // let it enter the loop
            drainThread.interrupt();
            drainThread.join(2_000);
            assertFalse(drainThread.isAlive(), "drain thread must have exited after interrupt");
        } finally {
            sem.release();
            eq.resetForTesting(5_000);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // processEvent()  — private, tested via reflection
    // Exception-handling paths must swallow errors without propagating them
    // ══════════════════════════════════════════════════════════════════════════

    /** Invokes the private {@code processEvent(QueuedEvent)} method via reflection. */
    private static void invokeProcessEvent(QueuedEvent ev) throws Exception {
        Method m = EventQueue.class.getDeclaredMethod("processEvent", QueuedEvent.class);
        m.setAccessible(true);
        m.invoke(EventQueue.getInstance(), ev);
    }

    @Test
    void processEventDoesNotThrowForMinimalEvent() {
        assertDoesNotThrow(() -> invokeProcessEvent(minimalEvent("process_test")),
                "processEvent must never propagate an exception to the dispatcher thread");
    }

    @Test
    void processEventDoesNotThrowWhenRedactEventHookThrows() {
        QueuedEvent ev = new QueuedEvent(
                new EventData("proj", "ses_test", "redact_throw_tool",
                              Map.of("k", "v"), null, 0L, false, null, null, null, null),
                null,
                new PendingHooks(null,
                        m -> { throw new RuntimeException("redactEvent exploded"); },
                        null, null, null, null));
        assertDoesNotThrow(() -> invokeProcessEvent(ev),
                "processEvent must catch and swallow exceptions from the redactEvent hook");
    }

    @Test
    void processEventDropsEventWhenRedactEventReturnsNull() {
        AtomicBoolean hookCalled = new AtomicBoolean(false);
        QueuedEvent ev = new QueuedEvent(
                new EventData("proj", "ses_test", "drop_tool", null, null, 0L, false, null, null, null, null),
                null,
                new PendingHooks(null,
                        m -> {
                            hookCalled.set(true);
                            return CompletableFuture.completedFuture(null); // null → drop
                        },
                        null, null, null, null));
        assertDoesNotThrow(() -> invokeProcessEvent(ev),
                "processEvent must not throw when redactEvent hook returns null (event dropped)");
        assertTrue(hookCalled.get(), "the redactEvent hook must have been called");
    }

    @Test
    void processEventHandlesExceptionFromRedactSensitiveInformationHook() {
        QueuedEvent ev = new QueuedEvent(
                new EventData("proj", "ses_test", "redact_sensitive_throw",
                              Map.of("secret", "value"), null, 0L, false, null, null, null, null),
                null,
                new PendingHooks(
                        s -> { throw new RuntimeException("redactSensitive exploded"); },
                        null, null, null, null, null));
        assertDoesNotThrow(() -> invokeProcessEvent(ev),
                "processEvent must catch synchronous throws from redactSensitiveInformation");
    }
}
