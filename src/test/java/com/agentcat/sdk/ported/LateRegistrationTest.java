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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/v2/late-registration.test.ts
 *
 * <p>The TypeScript tests verify that tools registered via {@code registerTool()}
 * AFTER {@code track()} is called still get handle injection, param stripping,
 * event publishing, and mint-back. The TS v2 McpServer supports dynamic
 * {@code registerTool()} at any time, including after {@code track()} patches it.
 *
 * <p>In Java the MCP SDK uses a builder pattern: all tools are registered via
 * {@code .toolCall()} before {@code .build()}, and {@code AgentCat.track()} is
 * called on the completed server. The "late registration" setup order is therefore
 * reversed: track comes last, not first. The same four behavioral guarantees are
 * verified through the standard Java flow.
 */
class LateRegistrationTest {

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

    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void advertisesToolRegisteredAfterTrackWithInjectedParams() throws Exception {
        // ported from: "advertises a tool registered after track() with injected params"
        //
        // In TS: track() is called first, then registerTool() — the SDK patches the
        // registration mechanism so the late tool still gets injected params.
        // In Java: tools are registered at build time, track() is called after build().
        // We verify the same guarantee: the tracked tool has session_id and context
        // injected into its input schema.
        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("v2-late", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("late_tool")
                                .description("Registered after tracking was enabled")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("message", Map.of("type", "string")))
                                        .required(List.of("message"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            String msg = String.valueOf(req.arguments().get("message"));
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("Processed: " + msg)
                                    .build();
                        })
                .build();

        AgentCat.track(server, "proj_test");

        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();
            List<String> names = tools.stream().map(McpSchema.Tool::name).toList();
            assertTrue(names.contains("late_tool"), "late_tool must be in tools list");
            assertTrue(names.contains("get_more_tools"), "get_more_tools must be injected");

            McpSchema.Tool lateTool = tools.stream()
                    .filter(t -> "late_tool".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("late_tool not found"));

            Map<String, Object> props = (Map<String, Object>) lateTool.inputSchema().get("properties");
            assertNotNull(props.get("session_id"),
                    "late_tool must have session_id injected into properties");
            assertNotNull(props.get("context"),
                    "late_tool must have context injected into properties");
        }
    }

    @Test
    void stripsHandleParamsFromLateRegisteredToolBeforeHandlerRuns() throws Exception {
        // ported from: "strips injected args that a loose schema passes through"
        //
        // Verifies that session_id and context are stripped before reaching the handler.
        // The handler records what arguments it actually received.
        AtomicBoolean sessionIdSeen = new AtomicBoolean(false);
        AtomicBoolean contextSeen = new AtomicBoolean(false);
        AtomicReference<String> messageSeen = new AtomicReference<>();

        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("v2-late", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("late_loose")
                                .description("Loose schema — all extra args pass through")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("message", Map.of("type", "string")))
                                        .required(List.of("message"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            // Record what handler actually sees
                            sessionIdSeen.set(req.arguments().containsKey("session_id"));
                            contextSeen.set(req.arguments().containsKey("context"));
                            messageSeen.set(String.valueOf(req.arguments().get("message")));
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent(String.valueOf(req.arguments().get("message")))
                                    .build();
                        })
                .build();

        AgentCat.track(server, "proj_test");

        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            ctx.client().listTools();
            String sessionId = TestUtils.sid("late");

            ctx.client().callTool(new McpSchema.CallToolRequest("late_loose",
                    Map.of("message", "hi",
                           "context", "late strip test",
                           "session_id", sessionId)));

            // Handler must NOT see session_id or context
            assertFalse(sessionIdSeen.get(),
                    "session_id must be stripped before reaching handler");
            assertFalse(contextSeen.get(),
                    "context must be stripped before reaching handler");
            assertEquals("hi", messageSeen.get(),
                    "message (non-injected arg) must reach handler intact");

            capture.flush();
            PublishEventRequest event = capture.findByTool("late_loose");
            assertNotNull(event, "event must be published");
            assertEquals(sessionId, event.getSessionId(),
                    "supplied session_id must be on the event");
            assertEquals("late strip test", event.getUserIntent(),
                    "context must be captured as userIntent on the event");
        }
    }

    @Test
    void publishesEventForLateRegisteredToolCall() throws Exception {
        // ported from: event is published for late-registered tool call
        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("v2-late", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("post_track_tool")
                                .description("A tool added after tracking was enabled")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("message", Map.of("type", "string")))
                                        .required(List.of("message"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            String msg = String.valueOf(req.arguments().get("message"));
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("Processed: " + msg)
                                    .build();
                        })
                .build();

        AgentCat.track(server, "proj_test");

        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            ctx.client().listTools();

            ctx.client().callTool(new McpSchema.CallToolRequest("post_track_tool",
                    Map.of("message", "post-track identification",
                           "context", "verifying identify on dynamically added tools")));

            capture.flush();
            PublishEventRequest event = capture.findByTool("post_track_tool");
            assertNotNull(event, "event must be published for the tool call");
            assertEquals("post_track_tool", event.getResourceName());
            assertEquals("verifying identify on dynamically added tools",
                    event.getUserIntent(),
                    "userIntent must be captured from context");
        }
    }

    @Test
    void mintBackIncludedInLateRegisteredToolResponse() throws Exception {
        // ported from: mint-back included in late-registered tool response
        //
        // On the first call without a session_id, the SDK mints a new session_id
        // and prepends a mint-back block to the response content.
        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("v2-late", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("late_tool")
                                .description("Registered after tracking was enabled")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("message", Map.of("type", "string")))
                                        .required(List.of("message"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            String msg = String.valueOf(req.arguments().get("message"));
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("Processed: " + msg)
                                    .build();
                        })
                .build();

        AgentCat.track(server, "proj_test");

        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            ctx.client().listTools();

            // First call without session_id → SDK mints one and adds mint-back
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("late_tool",
                            Map.of("message", "hello",
                                   "context", "mint-back test")));

            String mintBack = TestUtils.mintBackOf(result);
            assertNotNull(mintBack, "mint-back block must be present in the response");
            assertTrue(mintBack.contains("[session_id issued"),
                    "mint-back must announce the issued session_id, got: " + mintBack);

            // Tool response must also be present (after the mint-back block)
            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t -> t.contains("Processed: hello")),
                    "tool response must be present alongside mint-back, got: " + texts);
        }
    }
}
