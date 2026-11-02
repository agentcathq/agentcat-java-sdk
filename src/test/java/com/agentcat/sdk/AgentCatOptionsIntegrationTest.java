package com.agentcat.sdk;

import com.agentcat.sdk.engine.ReflectionUtils;
import com.agentcat.sdk.model.UserIdentity;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import com.agentcat.sdk.modules.events.EventQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying that each {@link AgentCatOptions} setter
 * actually changes SDK behaviour when passed to {@link AgentCat#track}.
 */
class AgentCatOptionsIntegrationTest {

    @BeforeEach
    void resetQueue() throws InterruptedException {
        EventQueue.getInstance().resetForTesting(10_000);
    }

    // ── setEnableTracing ──────────────────────────────────────────────────────

    @Test
    void setEnableTracing_false_sessionIdNotInjectedIntoSchema() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(false);

        McpAsyncServer server = buildServer();
        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) echo.tool().inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());

        assertFalse(props.containsKey("session_id"),
                "setEnableTracing(false) must disable handle injection — no session_id in schema");
        assertFalse(props.containsKey("agent_id"),
                "setEnableTracing(false) must disable handle injection — no agent_id in schema");
        // context injection is controlled independently by enableToolCallContext, not enableTracing
    }

    // ── setEnableAgentTracking ────────────────────────────────────────────────

    @Test
    void setEnableAgentTracking_true_agentIdInjectedIntoSchema() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);

        McpAsyncServer server = buildServer();
        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) echo.tool().inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");

        assertTrue(props.containsKey("agent_id"),
                "setEnableAgentTracking(true) must inject agent_id into tool schema");
    }

    // ── setEnableToolCallContext ───────────────────────────────────────────────

    @Test
    void setEnableToolCallContext_false_contextNotInjectedIntoSchema() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(false);

        McpAsyncServer server = buildServer();
        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) echo.tool().inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");

        assertFalse(props.containsKey("context"),
                "setEnableToolCallContext(false) must suppress context injection into tool schema");
    }

    // ── setEnableReportMissing ────────────────────────────────────────────────

    @Test
    void setEnableReportMissing_false_getMoreToolsNotRegistered() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(false);

        McpAsyncServer server = buildServer();
        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        boolean hasGetMoreTools = tools.stream()
                .anyMatch(t -> "get_more_tools".equals(t.tool().name()));

        assertFalse(hasGetMoreTools,
                "setEnableReportMissing(false) must suppress get_more_tools registration");
    }

    @Test
    void setEnableReportMissing_true_getMoreToolsRegistered() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(true);

        McpAsyncServer server = buildServer();
        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        boolean hasGetMoreTools = tools.stream()
                .anyMatch(t -> "get_more_tools".equals(t.tool().name()));

        assertTrue(hasGetMoreTools,
                "setEnableReportMissing(true) must register get_more_tools");
    }

    // ── setCustomContextDescription ──────────────────────────────────────────

    @Test
    void setCustomContextDescription_appearsInInjectedContextParam() throws Exception {
        String customDesc = "Custom context description for testing purposes only.";

        AgentCatOptions opts = new AgentCatOptions();
        opts.setCustomContextDescription(customDesc);

        McpAsyncServer server = buildServer();
        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) echo.tool().inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> contextProp = (Map<String, Object>) props.get("context");

        assertNotNull(contextProp, "context parameter must be injected");
        assertEquals(customDesc, contextProp.get("description"),
                "setCustomContextDescription must replace the default context description");
    }

    // ── setIdentify ──────────────────────────────────────────────────────────

    @Test
    void setIdentify_hookIsInvokedOnToolCall() throws Exception {
        AtomicBoolean hookCalled = new AtomicBoolean(false);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) -> {
            hookCalled.set(true);
            return CompletableFuture.completedFuture(new UserIdentity("user-123"));
        });

        McpAsyncServer server = buildServer();
        AgentCat.track(server, "proj_test", opts);

        invokeEchoTool(server);

        assertTrue(hookCalled.get(), "setIdentify hook must be invoked on tool call");
    }

    // ── setResolveSessionId ──────────────────────────────────────────────────

    @Test
    void setResolveSessionId_sessionIdNotInjectedIntoSchema() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setResolveSessionId((request, extra) ->
                CompletableFuture.completedFuture("user@example.com"));

        McpAsyncServer server = buildServer();
        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) echo.tool().inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");

        assertFalse(props.containsKey("session_id"),
                "setResolveSessionId (hook mode) must suppress session_id injection into schema");
    }

    // ── setRedactSensitiveInformation ────────────────────────────────────────

    @Test
    void setRedactSensitiveInformation_hookIsInvokedForStringValues() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setRedactSensitiveInformation(s -> {
            latch.countDown();
            return CompletableFuture.completedFuture(s);
        });

        McpAsyncServer server = buildServerWithStringArg();
        AgentCat.track(server, "proj_test", opts);

        invokeEchoToolWithArgs(server, Map.of("msg", "hello"));

        assertTrue(latch.await(3, TimeUnit.SECONDS),
                "setRedactSensitiveInformation hook must be invoked for string values in the event");
    }

    // ── setRedactEvent ───────────────────────────────────────────────────────

    @Test
    void setRedactEvent_hookIsInvokedBeforeEventIsSent() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setRedactEvent(event -> {
            latch.countDown();
            return CompletableFuture.completedFuture(event);
        });

        McpAsyncServer server = buildServer();
        AgentCat.track(server, "proj_test", opts);

        invokeEchoTool(server);

        assertTrue(latch.await(3, TimeUnit.SECONDS),
                "setRedactEvent hook must be invoked before the event is dispatched");
    }

    // ── setEventTags ─────────────────────────────────────────────────────────

    @Test
    void setEventTags_hookIsInvokedOnToolCall() throws Exception {
        AtomicBoolean hookCalled = new AtomicBoolean(false);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventTags((request, extra) -> {
            hookCalled.set(true);
            return CompletableFuture.completedFuture(Map.of("env", "test"));
        });

        McpAsyncServer server = buildServer();
        AgentCat.track(server, "proj_test", opts);

        invokeEchoTool(server);

        assertTrue(hookCalled.get(), "setEventTags hook must be invoked on tool call");
    }

    // ── setEventProperties ───────────────────────────────────────────────────

    @Test
    void setEventProperties_hookIsInvokedOnToolCall() throws Exception {
        AtomicBoolean hookCalled = new AtomicBoolean(false);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventProperties((request, extra) -> {
            hookCalled.set(true);
            return CompletableFuture.completedFuture(Map.of("version", "1.0"));
        });

        McpAsyncServer server = buildServer();
        AgentCat.track(server, "proj_test", opts);

        invokeEchoTool(server);

        assertTrue(hookCalled.get(), "setEventProperties hook must be invoked on tool call");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static StdioServerTransportProvider transport() {
        return new StdioServerTransportProvider(new JacksonMcpJsonMapper(new JsonMapper()));
    }

    private static McpAsyncServer buildServer() {
        return McpServer.async(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        (exchange, request) ->
                                Mono.just(McpSchema.CallToolResult.builder().addTextContent("ok").build())
                )
                .build();
    }

    private static McpAsyncServer buildServerWithStringArg() {
        return McpServer.async(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("msg", Map.of("type", "string")))
                                        .build())
                                .build(),
                        (exchange, request) ->
                                Mono.just(McpSchema.CallToolResult.builder().addTextContent("ok").build())
                )
                .build();
    }

    private static void invokeEchoTool(McpAsyncServer server) throws Exception {
        invokeEchoToolWithArgs(server, Map.of());
    }

    private static void invokeEchoToolWithArgs(McpAsyncServer server,
                                               Map<String, Object> args) throws Exception {
        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();
        echo.callHandler().apply(null, new McpSchema.CallToolRequest("echo", args)).block();
    }
}
