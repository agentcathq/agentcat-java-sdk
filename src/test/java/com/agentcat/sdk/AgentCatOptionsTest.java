package com.agentcat.sdk;

import com.agentcat.sdk.engine.ReflectionUtils;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import com.agentcat.sdk.modules.events.EventQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: agentcat-typescript-sdk/src/tests/v2/options.test.ts
 *
 * <p>Tests SDK behaviour changes driven by {@link AgentCatOptions}.
 * Schema-injection tests are in {@link AgentCatOptionsIntegrationTest};
 * this file covers defaults, no-op double-track, and behavioural guards.
 */
class AgentCatOptionsTest {

    @BeforeEach
    void resetQueue() throws InterruptedException {
        EventQueue.getInstance().resetForTesting(10_000);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static StdioServerTransportProvider transport() {
        return new StdioServerTransportProvider(new JacksonMcpJsonMapper(new JsonMapper()));
    }

    private static McpSyncServer buildSyncServer() {
        return McpServer.sync(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object")
                                        .properties(Map.of("msg", Map.of("type", "string")))
                                        .build())
                                .build(),
                        (exchange, request) ->
                                McpSchema.CallToolResult.builder().addTextContent("ok").build()
                )
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaProps(McpSyncServer server, String toolName)
            throws Exception {
        List<McpServerFeatures.AsyncToolSpecification> tools =
                ReflectionUtils.readField(server.getAsyncServer(), "tools");
        McpServerFeatures.AsyncToolSpecification spec = tools.stream()
                .filter(t -> toolName.equals(t.tool().name())).findFirst().orElseThrow();
        Map<String, Object> schema = (Map<String, Object>) spec.tool().inputSchema();
        return (Map<String, Object>) schema.getOrDefault("properties", Map.of());
    }

    // ── Default values ────────────────────────────────────────────────────────

    @Test
    void defaultOptions_tracingOnContextOnAgentOff() {
        // The SDK has safe defaults: tracing on, context on, agent-id off, everything else off/null.
        // If these defaults drift the entire injection behaviour silently changes.
        AgentCatOptions opts = new AgentCatOptions();
        assertTrue(opts.isEnableTracing(),           "enableTracing must default to true");
        assertTrue(opts.isEnableToolCallContext(),   "enableToolCallContext must default to true");
        assertFalse(opts.isEnableAgentTracking(),    "enableAgentTracking must default to false");
        assertTrue(opts.isEnableReportMissing(),     "enableReportMissing must default to true");
        assertNull(opts.getIdentify(),               "identify hook must default to null");
        assertNull(opts.getResolveSessionId(),       "resolveSessionId hook must default to null");
        assertNull(opts.getRedactSensitiveInformation(), "redactSensitiveInformation must default to null");
        assertNull(opts.getRedactEvent(),            "redactEvent must default to null");
        assertNull(opts.getEventTags(),              "eventTags hook must default to null");
        assertNull(opts.getEventProperties(),        "eventProperties hook must default to null");
        assertNull(opts.getCustomContextDescription(), "customContextDescription must default to null");
    }

    // ── enableTracing: false ──────────────────────────────────────────────────

    @Test
    void enableTracingFalse_toolCallPassesThroughUnwrapped() throws Exception {
        // ported from: "passes tool calls through unwrapped-behavior: no event, no mint-back"
        // When enableTracing=false, the tool result must not have any mint-back prepended.
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(false);

        McpSyncServer server = buildSyncServer();
        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools =
                ReflectionUtils.readField(server.getAsyncServer(), "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        McpSchema.CallToolResult result = echo.callHandler()
                .apply(null, new McpSchema.CallToolRequest("echo", Map.of("msg", "hi")))
                .block();

        assertNotNull(result);
        assertFalse(result.isError());
        // With enableTracing=false there is no wrapping, so no mint-back text block is prepended.
        boolean hasMintBack = result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .anyMatch(t -> t.startsWith("[session_id"));
        assertFalse(hasMintBack, "enableTracing=false must not produce a mint-back text block");
    }

    // ── enableReportMissing: false ────────────────────────────────────────────

    @Test
    void enableReportMissingFalse_sessionIdStillInjected() throws Exception {
        // ported from: "omits get_more_tools; handle injection is unaffected"
        // Disabling get_more_tools must not suppress session_id or context injection.
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(false);

        McpSyncServer server = buildSyncServer();
        AgentCat.track(server, "proj_test", opts);

        Map<String, Object> props = schemaProps(server, "echo");

        assertTrue(props.containsKey("session_id"),
                "session_id must still be injected when enableReportMissing=false");
        assertTrue(props.containsKey("context"),
                "context must still be injected when enableReportMissing=false");
        assertFalse(schemaProps_hasGetMoreTools(server),
                "get_more_tools must not be registered when enableReportMissing=false");
    }

    // ── Second track() is a no-op ─────────────────────────────────────────────

    @Test
    void secondTrackOnSameSyncServerIsNoOp() throws Exception {
        // ported from: "second track() on the same server is a no-op: no double wrap, one event per call"
        McpSyncServer server = buildSyncServer();
        AgentCat.track(server, "proj_test");
        AgentCat.track(server, "proj_test"); // second call must be a no-op

        List<McpServerFeatures.AsyncToolSpecification> tools =
                ReflectionUtils.readField(server.getAsyncServer(), "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) echo.tool().inputSchema();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());

        long sessionIdOccurrences = required.stream().filter("session_id"::equals).count();
        assertEquals(1, sessionIdOccurrences,
                "second track() must not duplicate session_id in required array");

        // get_more_tools must appear exactly once
        long gmtCount = tools.stream().filter(t -> "get_more_tools".equals(t.tool().name())).count();
        assertEquals(1, gmtCount, "second track() must not register get_more_tools twice");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static boolean schemaProps_hasGetMoreTools(McpSyncServer server) throws Exception {
        List<McpServerFeatures.AsyncToolSpecification> tools =
                ReflectionUtils.readField(server.getAsyncServer(), "tools");
        return tools.stream().anyMatch(t -> "get_more_tools".equals(t.tool().name()));
    }
}
