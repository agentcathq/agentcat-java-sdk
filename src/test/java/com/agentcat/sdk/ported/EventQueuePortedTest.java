package com.agentcat.sdk.ported;

import com.agentcat.sdk.modules.events.TagValidator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/eventQueue.test.ts
 *
 * Tests for the event queue: publishing, batching, session info building.
 * Most tests require mocked API clients and complex module wiring that map to
 * Java's {@link EventQueue} — all are {@code @Disabled} until the Java event
 * queue test harness is available.
 *
 * The {@code validateTags} unit test uses {@link com.agentcat.sdk.modules.events.TagValidator} directly.
 */
class EventQueuePortedTest {

    // ─────────────────────────────────────────────────────────────────────────
    // validateTags — unit test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void validateTagsDropsInvalidEntries() {
        java.util.LinkedHashMap<String, String> input = new java.util.LinkedHashMap<>();
        input.put("valid_key", "valid_value");
        input.put("invalid key!", "value");    // invalid chars in key
        input.put("k", "v\nnewline");          // newline in value

        Map<String, String> result = TagValidator.validate(input);

        assertNotNull(result);
        assertTrue(result.containsKey("valid_key"));
        assertFalse(result.containsKey("invalid key!"), "key with space+exclamation must be dropped");
        assertFalse(result.containsKey("k"), "value with newline must be dropped");
    }

    @Test
    void validateTagsReturnsNullWhenAllEntriesAreInvalid() {
        java.util.LinkedHashMap<String, String> input = new java.util.LinkedHashMap<>();
        input.put("bad key!", "value");
        input.put("another bad!", "value2");
        assertNull(TagValidator.validate(input));
    }

}
