package com.agentcat.sdk.modules.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JsonTree} gives the tree that the MCP JSON mapper sends to the client,
 * so redaction and sanitization see the same strings the client got.
 */
class JsonTreeTest {

    @Test
    void charArrayBecomesOneString() {
        // Jackson writes char[] as a JSON string, so an email-redaction hook must see it whole.
        Object tree = JsonTree.toTree(Map.of("email", "jane@example.com".toCharArray()));
        assertEquals(Map.of("email", "jane@example.com"), tree);
    }

    @Test
    void byteArrayBecomesBase64() {
        // Jackson writes byte[] as base64, which the sanitizer then removes when large.
        byte[] bytes = "binary payload".getBytes(StandardCharsets.UTF_8);
        Object tree = JsonTree.toTree(Map.of("blob", bytes));
        assertEquals(Map.of("blob", Base64.getEncoder().encodeToString(bytes)), tree);
    }

    @Test
    void otherArraysStayLists() {
        assertEquals(Map.of("n", java.util.List.of(1, 2)), JsonTree.toTree(Map.of("n", new int[] {1, 2})));
    }
}
