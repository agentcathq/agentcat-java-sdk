package com.agentcat.sdk.ported;

import com.agentcat.sdk.modules.events.TagValidator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/validation.test.ts
 *
 * Tests for {@link TagValidator#validate}.
 */
class ValidationTest {

    @Test
    void passesValidTagsUnchanged() {
        Map<String, Object> tags = Map.of(
                "env", "production",
                "trace_id", "abc-123",
                "region", "us-east-1"
        );
        Map<String, String> result = TagValidator.validate(tags);
        assertNotNull(result);
        assertEquals("production", result.get("env"));
        assertEquals("abc-123", result.get("trace_id"));
        assertEquals("us-east-1", result.get("region"));
    }

    @Test
    void returnsNullForEmptyObject() {
        assertNull(TagValidator.validate(Map.of()));
    }

    @Test
    void dropsKeysWithInvalidCharacters() {
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("valid_key", "value");
        tags.put("invalid!key", "value");
        tags.put("good.key", "value");
        Map<String, String> result = TagValidator.validate(tags);
        assertNotNull(result);
        assertTrue(result.containsKey("valid_key"));
        assertFalse(result.containsKey("invalid!key"),
                "Key with '!' should be dropped");
        assertTrue(result.containsKey("good.key"));
    }

    @Test
    void dropsKeysLongerThan32Characters() {
        String longKey = "a".repeat(33);
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put(longKey, "value");
        tags.put("short", "value");
        Map<String, String> result = TagValidator.validate(tags);
        assertNotNull(result);
        assertFalse(result.containsKey(longKey), "Key > 32 chars should be dropped");
        assertTrue(result.containsKey("short"));
    }

    @Test
    void dropsValuesLongerThan200Characters() {
        String longValue = "a".repeat(201);
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("key1", longValue);
        tags.put("key2", "short");
        Map<String, String> result = TagValidator.validate(tags);
        assertNotNull(result);
        assertFalse(result.containsKey("key1"), "Value > 200 chars should be dropped");
        assertTrue(result.containsKey("key2"));
    }

    @Test
    void dropsValuesContainingNewlines() {
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("key1", "has\nnewline");
        tags.put("key2", "clean");
        Map<String, String> result = TagValidator.validate(tags);
        assertNotNull(result);
        assertFalse(result.containsKey("key1"), "Value with newline should be dropped");
        assertTrue(result.containsKey("key2"));
    }

    @Test
    void dropsNonStringValues() {
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("key1", 123);
        tags.put("key2", "valid");
        Map<String, String> result = TagValidator.validate(tags);
        assertNotNull(result);
        assertFalse(result.containsKey("key1"), "Non-string value should be dropped");
        assertTrue(result.containsKey("key2"));
    }

    @Test
    void dropsEmptyStringKeys() {
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("", "empty-key-value");
        tags.put("valid", "value");
        Map<String, String> result = TagValidator.validate(tags);
        assertNotNull(result);
        assertFalse(result.containsKey(""), "Empty key should be dropped");
        assertTrue(result.containsKey("valid"));
    }

    @Test
    void keepsOnlyFirst50EntriesWhenExceedingLimit() {
        Map<String, Object> tags = new LinkedHashMap<>();
        for (int i = 0; i < 60; i++) {
            tags.put(String.format("key%03d", i), "value" + i);
        }
        Map<String, String> result = TagValidator.validate(tags);
        assertNotNull(result);
        assertEquals(50, result.size(), "Should cap at 50 entries");
    }

    @Test
    void returnsNullWhenAllEntriesAreInvalid() {
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("!!!", "value");
        tags.put("###", "value");
        assertNull(TagValidator.validate(tags));
    }

    @Test
    void allowsKeysWithPeriodsColonsDashesSpacesAndDollarSigns() {
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("my.tag", "value");
        tags.put("my:tag", "value");
        tags.put("my-tag", "value");
        tags.put("my tag", "value");
        tags.put("$ai_trace_id", "trace-1");
        tags.put("my$key", "value");
        Map<String, String> result = TagValidator.validate(tags);
        assertNotNull(result);
        assertEquals(6, result.size(), "All 6 special-char keys should be accepted");
    }
}
