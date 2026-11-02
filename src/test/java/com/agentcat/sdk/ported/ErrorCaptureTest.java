package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.integration.TestUtils;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/error-capture.test.ts
 *
 * Integration tests for error capture: when a tool handler throws or returns
 * {@code isError: true}, the SDK should publish an event with {@code isError=true}
 * and the error should still propagate to the MCP client.
 *
 * <p>Note: the TypeScript SDK captures rich error details (type, message, stack, frames,
 * chained_errors). Many of those tests are TS-specific (TypeError, non-Error throws, zod
 * validation). The Java tests focus on observable behavior: isError flag, event publishing,
 * session_id propagation, and non-blocking guarantee.
 */
class ErrorCaptureTest {

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a server with two tools: "echo" (always succeeds) and "explode"
     * (always returns isError: true with error text).
     */
    private McpTestContext setupServer(AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("error-capture-test", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool — always succeeds")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("text", Map.of("type", "string")))
                                        .required(List.of("text"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            String text = String.valueOf(req.arguments().get("text"));
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("echo: " + text)
                                    .build();
                        })
                .toolCall(
                        McpSchema.Tool.builder("explode")
                                .description("Always returns isError")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("reason", Map.of("type", "string")))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            String reason = req.arguments().containsKey("reason")
                                    ? String.valueOf(req.arguments().get("reason"))
                                    : "boom";
                            return McpSchema.CallToolResult.builder()
                                    .isError(true)
                                    .addTextContent("Error: " + reason)
                                    .build();
                        })
                .build();

        AgentCat.track(server, "test-project", opts);
        McpTestContext ctx = McpTestContext.start(transport, server);
        ctx.client().listTools();
        return ctx;
    }

    private PublishEventRequest findToolEvent(String toolName) {
        return capture.getEvents().stream()
                .filter(e -> toolName.equals(e.getResourceName()))
                .findFirst()
                .orElse(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void capturesIsErrorTrueWhenToolReturnsIsError() throws Exception {
        // ported from: tool returns isError: true → event.isError = true
        // Corresponds to TS: "should capture stack traces when tool throws Error" (error-path behavior)
        try (McpTestContext ctx = setupServer(new AgentCatOptions())) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("explode",
                            Map.of("reason", "not found")));

            // MCP returns errors as tool results with isError: true
            assertTrue(Boolean.TRUE.equals(result.isError()), "result must be isError");

            capture.flush();
            PublishEventRequest event = findToolEvent("explode");
            assertNotNull(event, "error event must be published");
            assertTrue(Boolean.TRUE.equals(event.getIsError()), "event must have isError=true");
        }
    }

    @Test
    void stillPublishesToolCallEventEvenOnError() throws Exception {
        // ported from: tool call event still published on error
        // Corresponds to TS: "should still propagate errors to MCP client" + event publishing
        try (McpTestContext ctx = setupServer(new AgentCatOptions())) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("explode",
                            Map.of("reason", "test failure")));

            assertTrue(Boolean.TRUE.equals(result.isError()), "result must be isError");

            // Error text is propagated to client
            boolean hasErrorText = result.content().stream()
                    .filter(c -> c instanceof McpSchema.TextContent)
                    .map(c -> ((McpSchema.TextContent) c).text())
                    .anyMatch(t -> t != null && t.contains("test failure"));
            assertTrue(hasErrorText, "error text must be propagated to MCP client");

            capture.flush();
            PublishEventRequest event = findToolEvent("explode");
            assertNotNull(event, "event must still be published on error");
            assertEquals("mcp:tools/call", event.getEventType());
        }
    }

    @Test
    void errorEventIncludesSessionIdAndToolName() throws Exception {
        // ported from: error event carries session_id and tool name
        try (McpTestContext ctx = setupServer(new AgentCatOptions())) {
            String sessionId = TestUtils.sid("error_session");

            ctx.client().callTool(new McpSchema.CallToolRequest("explode",
                    Map.of("reason", "session test",
                           "session_id", sessionId)));

            capture.flush();
            PublishEventRequest event = findToolEvent("explode");
            assertNotNull(event, "error event must be published");
            assertTrue(Boolean.TRUE.equals(event.getIsError()), "must be isError");
            assertEquals(sessionId, event.getSessionId(), "session_id must be on error event");
            assertEquals("explode", event.getResourceName(), "tool name must be on error event");
        }
    }

    @Test
    void doesNotBlockMcpResponseWhenToolReturnsError() throws Exception {
        // ported from: MCP response is returned even when analytics error event is captured
        try (McpTestContext ctx = setupServer(new AgentCatOptions())) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("explode",
                            Map.of("reason", "non-blocking test")));

            // Response is returned to client — the tool call is not blocked.
            assertNotNull(result);
            assertTrue(Boolean.TRUE.equals(result.isError()));

            // Verify the error text was received (proves MCP response propagated).
            boolean hasText = result.content().stream()
                    .filter(c -> c instanceof McpSchema.TextContent)
                    .map(c -> ((McpSchema.TextContent) c).text())
                    .anyMatch(t -> t != null && t.contains("non-blocking test"));
            assertTrue(hasText, "MCP response must be propagated to client");
        }
    }

    @Test
    void successfulToolCallHasNoError() throws Exception {
        // ported from: "should handle successful tool calls without errors"
        try (McpTestContext ctx = setupServer(new AgentCatOptions())) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("text", "Test todo")));

            assertNotNull(result);

            capture.flush();
            PublishEventRequest event = findToolEvent("echo");
            assertNotNull(event, "event must be published");
            assertFalse(Boolean.TRUE.equals(event.getIsError()),
                    "successful event must not have isError=true");
            assertNull(event.getError(), "successful event must not have error object");
        }
    }

    @Test
    void doesNotPublishIdentifyEventWhenIdentifyFunctionThrows() throws Exception {
        // ported from: "should NOT publish identify events when identify function throws"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) -> {
            throw new RuntimeException("Identify error");
        });

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Test")));

            capture.flush();

            // No identify event published
            boolean hasIdentifyEvent = capture.getEvents().stream()
                    .anyMatch(e -> "agentcat:identify".equals(e.getEventType()));
            assertFalse(hasIdentifyEvent, "no identify event when identify throws");

            // Tool call event still published
            PublishEventRequest event = findToolEvent("echo");
            assertNotNull(event, "tool call event must still be published");
        }
    }

    @Test
    void publishesExactlyOneEventPerToolCall() throws Exception {
        // ported from: "should publish exactly one event per tool call"
        try (McpTestContext ctx = setupServer(new AgentCatOptions())) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Single event")));

            capture.flush();
            Thread.sleep(50); // extra wait to ensure no duplicate events

            long echoEvents = capture.getEvents().stream()
                    .filter(e -> "echo".equals(e.getResourceName()))
                    .count();
            assertEquals(1, echoEvents, "exactly one event per tool call");
        }
    }
}
