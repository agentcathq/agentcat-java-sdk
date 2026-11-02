package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.integration.TestUtils;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/v2/options.test.ts
 *
 * Tests for v2 options (enableTracing, enableReportMissing, enableToolCallContext,
 * customContextDescription) on a tracked v2 McpServer.
 * Unit tests via ParameterInjector are active; integration tests use McpTestContext.
 */
class OptionsTest {

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

    // ── Helper ─────────────────────────────────────────────────────────────────

    private McpTestContext setupEchoServer(AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("v2-options", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("echo",
                                        Map.of("type", "object",
                                               "properties", Map.of("msg", Map.of("type", "string"))))
                                .description("Echo")
                                .build(),
                        (exchange, req) -> McpSchema.CallToolResult.builder()
                                .addTextContent("echo: " + req.arguments().get("msg"))
                                .build())
                .build();
        AgentCat.track(server, "proj_test", opts);
        return McpTestContext.start(transport, server);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unit tests — option combinations via ParameterInjector
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "injects no handle params into the listing" when enableTracing is false.
     * Handle params are session_id and agent_id — both require enableTracing.
     * Context injection is controlled independently by enableToolCallContext and is
     * NOT gated on enableTracing, so context may still be present.
     */
    @Test
    @SuppressWarnings("unchecked")
    void injectsNoHandleParamsWhenEnableTracingIsFalse() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(false);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(Map.of("msg", Map.of("type", "string"))));

        McpSchema.Tool tool = McpSchema.Tool.builder("echo", schema).build();
        McpSchema.Tool result = ParameterInjector.injectParams(tool, opts);

        Map<String, Object> props = (Map<String, Object>) result.inputSchema().get("properties");
        assertFalse(props.containsKey(ParameterInjector.SESSION_ID_PARAM),
                "session_id must not be injected when enableTracing is false");
        assertFalse(props.containsKey(ParameterInjector.AGENT_ID_PARAM),
                "agent_id must not be injected when enableTracing is false");
    }

    /**
     * Context injection is skipped when enableToolCallContext is false
     */
    @Test
    @SuppressWarnings("unchecked")
    void doesNotInjectContextParamWhenEnableToolCallContextIsFalse() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableToolCallContext(false);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());

        McpSchema.Tool tool = McpSchema.Tool.builder("echo", schema).build();
        McpSchema.Tool result = ParameterInjector.injectParams(tool, opts);

        Map<String, Object> props = (Map<String, Object>) result.inputSchema().get("properties");
        assertNull(props.get(ParameterInjector.CONTEXT_PARAM),
                "context must not be injected when enableToolCallContext is false");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integration tests — enableTracing false
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "passes tool calls through unwrapped-behavior: no event, no mint-back"
     *
     * <p>Ported from: src/tests/v2/options.test.ts — describe "v2 options: enableTracing false"
     */
    @Test
    void passesToolCallsThroughUnwrappedWhenTracingIsOff() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(false);

        try (McpTestContext ctx = setupEchoServer(opts)) {
            ctx.client().listTools();

            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo", Map.of("msg", "hi")));

            // Tool returns normal content
            assertNotNull(result);
            List<String> text = TestUtils.textContents(result);
            assertFalse(text.isEmpty(), "tool must return content");
            assertTrue(text.get(0).contains("hi"), "content must echo the message");

            // No mint-back when tracing is off
            assertNull(TestUtils.mintBackOf(result), "no mint-back when tracing is off");

            // No events published
            Thread.sleep(150);
            assertTrue(capture.getEvents().isEmpty(),
                    "no events must be published when tracing is off");
        }
    }

    /**
     * "still answers get_more_tools directly, publishing nothing"
     *
     * <p>Even with tracing off, get_more_tools is listed and its canned response is returned.
     * No analytics event is published.
     *
     * <p>Ported from: src/tests/v2/options.test.ts — describe "v2 options: enableTracing false"
     */
    @Test
    void stillAnswersGetMoreToolsDirectlyPublishingNothing() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(false);

        try (McpTestContext ctx = setupEchoServer(opts)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();
            assertNotNull(tools.stream().filter(t -> "get_more_tools".equals(t.name())).findFirst().orElse(null),
                    "get_more_tools must still be listed when tracing is off");

            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("get_more_tools",
                            Map.of("context", "need a search tool")));

            assertNotNull(result);
            List<String> text = TestUtils.textContents(result);
            assertTrue(text.stream().anyMatch(t -> t.contains("we have shown you the full tool list")),
                    "get_more_tools must return the canned response");
            assertNull(TestUtils.mintBackOf(result), "no mint-back when tracing is off");

            Thread.sleep(150);
            assertTrue(capture.getEvents().isEmpty(),
                    "no events must be published for get_more_tools when tracing is off");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integration tests — enableReportMissing false
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "omits get_more_tools; handle injection is unaffected"
     *
     * <p>Ported from: src/tests/v2/options.test.ts — describe "v2 options: enableReportMissing false"
     */
    @Test
    @SuppressWarnings("unchecked")
    void enableReportMissingFalseExcludesGetMoreToolsFromListing() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(false);

        try (McpTestContext ctx = setupEchoServer(opts)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();

            assertNull(tools.stream().filter(t -> "get_more_tools".equals(t.name())).findFirst().orElse(null),
                    "get_more_tools must be absent when enableReportMissing is false");

            McpSchema.Tool echo = tools.stream()
                    .filter(t -> "echo".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("echo not found"));

            Map<String, Object> props = (Map<String, Object>) echo.inputSchema().get("properties");
            assertNotNull(props.get("session_id"),
                    "handle injection is unaffected by enableReportMissing");
            assertNotNull(props.get("context"),
                    "context injection is unaffected by enableReportMissing");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integration tests — enableToolCallContext false
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "injects no context param; events carry no userIntent"
     *
     * <p>Ported from: src/tests/v2/options.test.ts — describe "v2 options: enableToolCallContext false"
     */
    @Test
    @SuppressWarnings("unchecked")
    void enableToolCallContextFalseOmitsContextFromListing() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(false);

        try (McpTestContext ctx = setupEchoServer(opts)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();

            McpSchema.Tool echo = tools.stream()
                    .filter(t -> "echo".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("echo not found"));

            Map<String, Object> props = (Map<String, Object>) echo.inputSchema().get("properties");
            assertNull(props.get("context"),
                    "context must not be injected when enableToolCallContext is false");
            // session_id is independent of enableToolCallContext
            assertNotNull(props.get("session_id"),
                    "session_id must still be injected regardless of enableToolCallContext");

            String sessionId = TestUtils.sid("ctxoff");
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("msg", "hi", "session_id", sessionId)));

            capture.flush();
            List<PublishEventRequest> events = capture.getEvents();
            assertFalse(events.isEmpty());

            PublishEventRequest event = events.get(0);
            assertEquals(sessionId, event.getSessionId());
            assertNull(event.getUserIntent(),
                    "userIntent must be null when enableToolCallContext is false");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integration tests — customContextDescription
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "lands the custom text in the advertised context param description"
     *
     * <p>Ported from: src/tests/v2/options.test.ts — describe "v2 options: customContextDescription"
     */
    @Test
    @SuppressWarnings("unchecked")
    void customContextDescriptionIsUsedInListing() throws Exception {
        String customDescription = "Explain your reasoning for this action";

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(true);
        opts.setCustomContextDescription(customDescription);

        try (McpTestContext ctx = setupEchoServer(opts)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();

            McpSchema.Tool echo = tools.stream()
                    .filter(t -> "echo".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("echo not found"));

            Map<String, Object> props = (Map<String, Object>) echo.inputSchema().get("properties");
            Map<String, Object> contextProp = (Map<String, Object>) props.get("context");
            assertNotNull(contextProp, "context must be in schema");
            assertEquals(customDescription, contextProp.get("description"),
                    "custom context description must appear in the advertised schema");

            // get_more_tools keeps its bespoke context parameter untouched
            McpSchema.Tool gmt = tools.stream()
                    .filter(t -> "get_more_tools".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("get_more_tools not found"));
            Map<String, Object> gmtProps = (Map<String, Object>) gmt.inputSchema().get("properties");
            Map<String, Object> gmtContext = (Map<String, Object>) gmtProps.get("context");
            assertNotNull(gmtContext);
            assertNotEquals(customDescription, gmtContext.get("description"),
                    "get_more_tools must keep its own context description");
        }
    }

}
