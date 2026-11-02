package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.integration.TestUtils;
import com.agentcat.sdk.modules.pipeline.Truncator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/v2/pipeline.test.ts
 *
 * Tests for the redact → sanitize → truncate pipeline on captured events.
 */
class PipelineTest {

    private EventCapture capture;

    @BeforeEach
    void startCapture() throws Exception {
        capture = new EventCapture();
        capture.start();
    }

    @AfterEach
    void stopCapture() throws Exception {
        capture.stop();
    }

    // ── Unit tests — pipeline constant values ────────────────────────────────

    @Test
    void truncatorMaxStringLengthIs32768() {
        assertEquals(32768, Truncator.MAX_STRING_LENGTH);
    }

    @Test
    void truncatorMaxDepthIs10() {
        assertEquals(10, Truncator.MAX_DEPTH);
    }

    @Test
    void truncatorMaxBreadthIs100() {
        assertEquals(100, Truncator.MAX_BREADTH);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integration: string redaction on captured event
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void appliesStringRedactionHookToV2CapturedEvent() throws Exception {
        // ported from: "applies the string-redaction hook to a v2-captured event"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setRedactSensitiveInformation(text ->
                CompletableFuture.completedFuture(text.replace("secret-sauce", "[REDACTED]")));

        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("v2-pipeline", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("leaky")
                                .description("Leaks")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("query", Map.of("type", "string")))
                                        .required(List.of("query"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            String query = String.valueOf(req.arguments().get("query"));
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("result for " + query + ": secret-sauce")
                                    .build();
                        })
                .build();
        AgentCat.track(server, "proj_test", opts);
        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            ctx.client().listTools();
            String sessionId = TestUtils.sid("redact");

            ctx.client().callTool(new McpSchema.CallToolRequest("leaky",
                    Map.of("query", "find the secret-sauce",
                           "context", "pipeline redaction test",
                           "session_id", sessionId)));

            capture.flush();
            PublishEventRequest event = capture.findByTool("leaky");
            assertNotNull(event, "event must be published");

            // Response text must be redacted.
            Map<String, Object> response = (Map<String, Object>) event.getResponse();
            assertNotNull(response);
            List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
            assertNotNull(content);
            // Find the user-generated text content (skip mint-back blocks)
            String responseText = content.stream()
                    .filter(c -> "text".equals(c.get("type")))
                    .map(c -> (String) c.get("text"))
                    .filter(t -> t != null && !t.startsWith("[session_id"))
                    .findFirst()
                    .orElse(null);
            assertNotNull(responseText, "response text must be present");
            assertTrue(responseText.contains("[REDACTED]"),
                    "response must contain [REDACTED], got: " + responseText);
            assertFalse(responseText.contains("secret-sauce"),
                    "response must not contain secret-sauce after redaction");

            // Parameters pass through the same hook.
            Map<String, Object> params = (Map<String, Object>) event.getParameters();
            assertNotNull(params);
            String queryValue = findStringInMap(params, "query");
            assertNotNull(queryValue, "query param must be present in event");
            assertEquals("find the [REDACTED]", queryValue,
                    "query param must be redacted");

            // Protected fields survive redaction untouched.
            assertEquals(sessionId, event.getSessionId());
            assertEquals("leaky", event.getResourceName());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sanitizesImageContentBlocksInV2ToolResponses() throws Exception {
        // ported from: "sanitizes image content blocks in v2 tool responses"
        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("v2-pipeline", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("get_attachment")
                                .description("Attachment")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("id", Map.of("type", "string")))
                                        .required(List.of("id"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            // Return text + image content using builders
                            return McpSchema.CallToolResult.builder()
                                    .addContent(new McpSchema.TextContent("Here is the attachment:"))
                                    .addContent(new McpSchema.ImageContent(null,
                                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ",
                                            "image/png"))
                                    .build();
                        })
                .build();
        AgentCat.track(server, "proj_test", new AgentCatOptions());
        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            ctx.client().listTools();

            ctx.client().callTool(new McpSchema.CallToolRequest("get_attachment",
                    Map.of("id", "att_1", "context", "sanitization test")));

            capture.flush();
            PublishEventRequest event = capture.findByTool("get_attachment");
            assertNotNull(event, "event must be published");

            Map<String, Object> response = (Map<String, Object>) event.getResponse();
            assertNotNull(response);
            List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
            assertNotNull(content);

            // Filter out mint-back blocks to find the tool's own content
            List<Map<String, Object>> toolContent = content.stream()
                    .filter(c -> {
                        Object text = c.get("text");
                        return !(text instanceof String s && s.startsWith("[session_id"));
                    })
                    .toList();

            assertEquals(2, toolContent.size(), "must have 2 content blocks (text + sanitized image)");

            // First block: text, untouched
            assertEquals("text", toolContent.get(0).get("type"));
            assertEquals("Here is the attachment:", toolContent.get(0).get("text"));

            // Second block: image replaced with text placeholder
            assertEquals("text", toolContent.get(1).get("type"));
            String sanitized = (String) toolContent.get(1).get("text");
            assertNotNull(sanitized);
            assertTrue(sanitized.contains("image content redacted"),
                    "image must be replaced with redaction text, got: " + sanitized);
        }
    }

    @Test
    void redactEventHookCanDropAnEventBeforePublishing() throws Exception {
        // ported from: "redactEvent returning null drops the event"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setRedactEvent(event -> CompletableFuture.completedFuture(null)); // drop all events

        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("v2-pipeline", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("text", Map.of("type", "string")))
                                        .required(List.of("text"))
                                        .build())
                                .build(),
                        (exchange, req) -> McpSchema.CallToolResult.builder()
                                .addTextContent("echo: " + req.arguments().get("text"))
                                .build())
                .build();
        AgentCat.track(server, "proj_test", opts);
        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            ctx.client().listTools();

            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("text", "should be dropped")));

            // Tool call must succeed.
            assertNotNull(result);

            // Give pipeline time to process.
            Thread.sleep(200);

            // No events should be published — redactEvent dropped them.
            assertTrue(capture.getEvents().isEmpty(),
                    "no events must be published when redactEvent returns null");
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static String findStringInMap(Map<String, Object> map, String key) {
        if (map.containsKey(key)) {
            Object v = map.get(key);
            return v instanceof String s ? s : null;
        }
        for (Object value : map.values()) {
            if (value instanceof Map<?, ?> nested) {
                String found = findStringInMap((Map<String, Object>) nested, key);
                if (found != null) return found;
            }
        }
        return null;
    }
}
