package com.agentcat.sdk;

import com.agentcat.sdk.engine.ReflectionUtils;
import com.agentcat.sdk.engine.StatefulToolCallWrapper;
import com.agentcat.sdk.engine.StatelessToolCallWrapper;
import com.agentcat.sdk.model.HandleResolution;
import com.agentcat.sdk.model.UserIdentity;
import com.agentcat.sdk.modules.handles.SessionManager;
import com.agentcat.sdk.modules.handles.MintBackHandler;
import com.agentcat.sdk.modules.handles.SessionIdProvider;
import com.agentcat.sdk.model.SessionSource;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import com.agentcat.sdk.modules.events.EventQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class AgentCatTest {

    @BeforeEach
    void resetQueue() throws InterruptedException {
        EventQueue.getInstance().resetForTesting(10_000);
    }

    // ── Sync server tests ────────────────────────────────────────────────────

    /**
     * track() must return the same server instance it received.
     */
    @Test
    void trackReturnsSameServerInstance() {
        McpSyncServer server = buildSyncServer();
        McpSyncServer returned = AgentCat.track(server, "proj_test");
        assertSame(server, returned);
    }

    /**
     * track() must not throw even when called with a blank project ID.
     */
    @Test
    void trackDoesNotThrowOnBlankProjectId() {
        McpSyncServer server = buildSyncServer();
        assertDoesNotThrow(() -> AgentCat.track(server, ""));
    }

    /**
     * track() must not throw even when called with a null project ID.
     */
    @Test
    void trackDoesNotThrowOnNullProjectId() {
        McpSyncServer server = buildSyncServer();
        assertDoesNotThrow(() -> AgentCat.track(server, null));
    }

    /**
     * The original tool handler must still be called after track() wraps it.
     */
    @Test
    void originalHandlerIsCalledAfterTracking() throws Exception {
        AtomicBoolean handlerCalled = new AtomicBoolean(false);

        McpSyncServer server = McpServer.sync(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("ping")
                                .description("Test tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        new RecordingHandler(handlerCalled)
                )
                .build();

        AgentCat.track(server, "proj_test");

        List<McpServerFeatures.AsyncToolSpecification> tools =
                ReflectionUtils.readField(server.getAsyncServer(), "tools");

        McpServerFeatures.AsyncToolSpecification ping = tools.stream()
                .filter(t -> "ping".equals(t.tool().name()))
                .findFirst()
                .orElseThrow();

        McpSchema.CallToolResult result = ping.callHandler()
                .apply(null, new McpSchema.CallToolRequest("ping", Map.of()))
                .block();

        assertTrue(handlerCalled.get(), "Original handler should have been called");
        assertNotNull(result);
        assertFalse(Boolean.TRUE.equals(result.isError()));
    }

    /**
     * get_more_tools must be present in the server's tool list after track().
     */
    @Test
    void getMoreToolsIsRegisteredAfterTracking() throws Exception {
        McpSyncServer server = buildSyncServer();
        AgentCat.track(server, "proj_test");

        List<McpServerFeatures.AsyncToolSpecification> tools =
                ReflectionUtils.readField(server.getAsyncServer(), "tools");

        boolean found = tools.stream()
                .anyMatch(t -> "get_more_tools".equals(t.tool().name()));

        assertTrue(found, "get_more_tools should be registered after track()");
    }

    // ── Async server tests ───────────────────────────────────────────────────

    /**
     * track() must return the same async server instance it received.
     */
    @Test
    void trackAsyncReturnsSameInstance() {
        McpAsyncServer server = buildAsyncServer();
        McpAsyncServer returned = AgentCat.track(server, "proj_test");
        assertSame(server, returned);
    }

    /**
     * track() must not throw even when called with a blank project ID on async server.
     */
    @Test
    void trackAsyncDoesNotThrowOnBlankProjectId() {
        McpAsyncServer server = buildAsyncServer();
        assertDoesNotThrow(() -> AgentCat.track(server, ""));
    }

    /**
     * track() must not throw even when called with a null project ID on async server.
     */
    @Test
    void trackAsyncDoesNotThrowOnNullProjectId() {
        McpAsyncServer server = buildAsyncServer();
        assertDoesNotThrow(() -> AgentCat.track(server, null));
    }

    /**
     * The original async tool handler must still be called after track() wraps it.
     */
    @Test
    void asyncOriginalHandlerIsCalledAfterTracking() throws Exception {
        AtomicBoolean handlerCalled = new AtomicBoolean(false);

        McpAsyncServer server = McpServer.async(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("ping")
                                .description("Test tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        new AsyncRecordingHandler(handlerCalled)
                )
                .build();

        AgentCat.track(server, "proj_test");

        List<McpServerFeatures.AsyncToolSpecification> tools =
                ReflectionUtils.readField(server, "tools");

        McpServerFeatures.AsyncToolSpecification ping = tools.stream()
                .filter(t -> "ping".equals(t.tool().name()))
                .findFirst()
                .orElseThrow();

        McpSchema.CallToolResult result = ping.callHandler()
                .apply(null, new McpSchema.CallToolRequest("ping", Map.of()))
                .block();

        assertTrue(handlerCalled.get(), "Original async handler should have been called");
        assertNotNull(result);
        assertFalse(Boolean.TRUE.equals(result.isError()));
    }

    /**
     * get_more_tools must be present in the async server's tool list after track().
     */
    @Test
    void getMoreToolsIsRegisteredOnAsyncServer() throws Exception {
        McpAsyncServer server = buildAsyncServer();
        AgentCat.track(server, "proj_test");

        List<McpServerFeatures.AsyncToolSpecification> tools =
                ReflectionUtils.readField(server, "tools");

        boolean found = tools.stream()
                .anyMatch(t -> "get_more_tools".equals(t.tool().name()));

        assertTrue(found, "get_more_tools should be registered on async server after track()");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static StdioServerTransportProvider transport() {
        return new StdioServerTransportProvider(
                new JacksonMcpJsonMapper(new JsonMapper()));
    }

    /**
     * Minimal no-op {@link McpStatelessServerTransport} for unit tests.
     * No HTTP stack needed — we only test tool wrapping, not request routing.
     */
    private static McpStatelessServerTransport statelessTransport() {
        return new McpStatelessServerTransport() {
            @Override
            public void setMcpHandler(McpStatelessServerHandler handler) {}

            @Override
            public Mono<Void> closeGracefully() {
                return Mono.empty();
            }
        };
    }

    private static McpStatelessSyncServer buildStatelessSyncServer() {
        return McpServer.sync(statelessTransport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        new StatelessEchoHandler()
                )
                .build();
    }

    private static McpStatelessAsyncServer buildStatelessAsyncServer() {
        return McpServer.async(statelessTransport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        new StatelessAsyncEchoHandler()
                )
                .build();
    }

    private static McpSyncServer buildSyncServer() {
        return McpServer.sync(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        new EchoHandler()
                )
                .build();
    }

    private static McpAsyncServer buildAsyncServer() {
        return McpServer.async(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        new AsyncEchoHandler()
                )
                .build();
    }

    // ── Stateless sync server tests ──────────────────────────────────────────

    /**
     * track() must return the same stateless sync server instance it received.
     */
    @Test
    void trackStatelessSyncReturnsSameInstance() {
        McpStatelessSyncServer server = buildStatelessSyncServer();
        McpStatelessSyncServer returned = AgentCat.track(server, "proj_test");
        assertSame(server, returned);
    }

    /**
     * track() must not throw on a blank project ID for stateless sync server.
     */
    @Test
    void trackStatelessSyncDoesNotThrowOnBlankProjectId() {
        assertDoesNotThrow(() -> AgentCat.track(buildStatelessSyncServer(), ""));
    }

    /**
     * track() must not throw on null project ID for stateless sync server.
     */
    @Test
    void trackStatelessSyncDoesNotThrowOnNullProjectId() {
        assertDoesNotThrow(() -> AgentCat.track(buildStatelessSyncServer(), null));
    }

    /**
     * get_more_tools must be present after track() on a stateless sync server.
     */
    @Test
    void getMoreToolsRegisteredOnStatelessSyncServer() throws Exception {
        McpStatelessSyncServer server = buildStatelessSyncServer();
        AgentCat.track(server, "proj_test");

        McpStatelessAsyncServer asyncServer = ReflectionUtils.readField(server, "asyncServer");
        List<McpStatelessServerFeatures.AsyncToolSpecification> tools =
                ReflectionUtils.readField(asyncServer, "tools");

        boolean found = tools.stream().anyMatch(t -> "get_more_tools".equals(t.tool().name()));
        assertTrue(found, "get_more_tools should be registered on stateless sync server after track()");
    }

    /**
     * The original handler must still be called after track() on a stateless sync server.
     */
    @Test
    void statelessSyncOriginalHandlerCalledAfterTracking() throws Exception {
        AtomicBoolean handlerCalled = new AtomicBoolean(false);

        McpStatelessSyncServer server = McpServer.sync(statelessTransport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("ping")
                                .description("Test tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        new StatelessRecordingHandler(handlerCalled)
                )
                .build();

        AgentCat.track(server, "proj_test");

        McpStatelessAsyncServer asyncServer = ReflectionUtils.readField(server, "asyncServer");
        List<McpStatelessServerFeatures.AsyncToolSpecification> tools =
                ReflectionUtils.readField(asyncServer, "tools");

        McpStatelessServerFeatures.AsyncToolSpecification ping = tools.stream()
                .filter(t -> "ping".equals(t.tool().name()))
                .findFirst()
                .orElseThrow();

        McpSchema.CallToolResult result = ping.callHandler()
                .apply(null, new McpSchema.CallToolRequest("ping", Map.of()))
                .block();

        assertTrue(handlerCalled.get(), "Original stateless sync handler should have been called");
        assertNotNull(result);
        assertFalse(Boolean.TRUE.equals(result.isError()));
    }

    // ── Stateless async server tests ─────────────────────────────────────────

    /**
     * track() must return the same stateless async server instance it received.
     */
    @Test
    void trackStatelessAsyncReturnsSameInstance() {
        McpStatelessAsyncServer server = buildStatelessAsyncServer();
        McpStatelessAsyncServer returned = AgentCat.track(server, "proj_test");
        assertSame(server, returned);
    }

    /**
     * track() must not throw on a blank project ID for stateless async server.
     */
    @Test
    void trackStatelessAsyncDoesNotThrowOnBlankProjectId() {
        assertDoesNotThrow(() -> AgentCat.track(buildStatelessAsyncServer(), ""));
    }

    /**
     * track() must not throw on null project ID for stateless async server.
     */
    @Test
    void trackStatelessAsyncDoesNotThrowOnNullProjectId() {
        assertDoesNotThrow(() -> AgentCat.track(buildStatelessAsyncServer(), null));
    }

    /**
     * get_more_tools must be present after track() on a stateless async server.
     */
    @Test
    void getMoreToolsRegisteredOnStatelessAsyncServer() throws Exception {
        McpStatelessAsyncServer server = buildStatelessAsyncServer();
        AgentCat.track(server, "proj_test");

        List<McpStatelessServerFeatures.AsyncToolSpecification> tools =
                ReflectionUtils.readField(server, "tools");

        boolean found = tools.stream().anyMatch(t -> "get_more_tools".equals(t.tool().name()));
        assertTrue(found, "get_more_tools should be registered on stateless async server after track()");
    }

    /**
     * The original handler must still be called after track() on a stateless async server.
     */
    @Test
    void statelessAsyncOriginalHandlerCalledAfterTracking() throws Exception {
        AtomicBoolean handlerCalled = new AtomicBoolean(false);

        McpStatelessAsyncServer server = McpServer.async(statelessTransport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("ping")
                                .description("Test tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        new StatelessAsyncRecordingHandler(handlerCalled)
                )
                .build();

        AgentCat.track(server, "proj_test");

        List<McpStatelessServerFeatures.AsyncToolSpecification> tools =
                ReflectionUtils.readField(server, "tools");

        McpStatelessServerFeatures.AsyncToolSpecification ping = tools.stream()
                .filter(t -> "ping".equals(t.tool().name()))
                .findFirst()
                .orElseThrow();

        McpSchema.CallToolResult result = ping.callHandler()
                .apply(null, new McpSchema.CallToolRequest("ping", Map.of()))
                .block();

        assertTrue(handlerCalled.get(), "Original stateless async handler should have been called");
        assertNotNull(result);
        assertFalse(Boolean.TRUE.equals(result.isError()));
    }

    // ── Registration patch tests (2.3) ───────────────────────────────────────

    /**
     * A tool added via addTool() AFTER track() must also be wrapped (async server).
     */
    @Test
    void toolAddedAfterTrackIsWrappedOnAsyncServer() throws Exception {
        McpAsyncServer server = buildAsyncServer();
        AgentCat.track(server, "proj_test");

        // Add a new tool AFTER track() has been called
        McpSchema.Tool lateToolSchema = McpSchema.Tool.builder("late_tool")
                .description("Added after track")
                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                .build();
        server.addTool(new McpServerFeatures.AsyncToolSpecification(lateToolSchema,
                new AsyncEchoHandler())).toFuture().get();

        List<McpServerFeatures.AsyncToolSpecification> tools =
                ReflectionUtils.readField(server, "tools");

        McpServerFeatures.AsyncToolSpecification lateTool = tools.stream()
                .filter(t -> "late_tool".equals(t.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("late_tool not found in tool list"));

        assertTrue(StatefulToolCallWrapper.isWrapped(lateTool),
                "Tool added after track() should be auto-wrapped by WrappingToolList");
    }

    /**
     * A tool added via addTool() AFTER track() must also be wrapped (stateless async server).
     */
    @Test
    void toolAddedAfterTrackIsWrappedOnStatelessAsyncServer() throws Exception {
        McpStatelessAsyncServer server = buildStatelessAsyncServer();
        AgentCat.track(server, "proj_test");

        McpSchema.Tool lateToolSchema = McpSchema.Tool.builder("late_tool")
                .description("Added after track")
                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                .build();
        server.addTool(new McpStatelessServerFeatures.AsyncToolSpecification(lateToolSchema,
                new StatelessAsyncEchoHandler())).toFuture().get();

        List<McpStatelessServerFeatures.AsyncToolSpecification> tools =
                ReflectionUtils.readField(server, "tools");

        McpStatelessServerFeatures.AsyncToolSpecification lateTool = tools.stream()
                .filter(t -> "late_tool".equals(t.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("late_tool not found in stateless tool list"));

        assertTrue(StatelessToolCallWrapper.isWrapped(lateTool),
                "Tool added after track() should be auto-wrapped by StatelessWrappingToolList");
    }

    // ── 3.1 Handle injection tests ───────────────────────────────────────────

    /**
     * session_id must appear in every tool's input schema after track() with default options.
     */
    @Test
    void sessionIdInjectedIntoSchema() throws Exception {
        McpSyncServer server = buildSyncServer();
        AgentCat.track(server, "proj_test");

        Map<String, Object> schema = getToolSchema(server.getAsyncServer(), "echo");
        Map<String, Object> props  = schemaProps(schema);

        assertNotNull(props, "properties must not be null after injection");
        assertTrue(props.containsKey("session_id"), "session_id must be present in tool schema");
        // Not added to required — MCP Java SDK enforces required and would reject calls
        // that omit it. AgentCat uses soft enforcement: property + description guide the agent.

        // Verify the pattern constraint is present
        @SuppressWarnings("unchecked")
        Map<String, Object> sessionIdProp = (Map<String, Object>) props.get("session_id");
        assertEquals("^(start|ses_[0-9A-Za-z]{27})$", sessionIdProp.get("pattern"),
                "session_id must carry the KSUID pattern constraint");
    }

    /**
     * agent_id must appear in the schema only when enableAgentTracking is true.
     */
    @Test
    void agentIdInjectedOnlyWhenEnabled() throws Exception {
        // Default options: agent_id must NOT be injected
        McpSyncServer serverDefault = buildSyncServer();
        AgentCat.track(serverDefault, "proj_test");
        assertFalse(schemaProps(getToolSchema(serverDefault.getAsyncServer(), "echo")).containsKey("agent_id"),
                "agent_id must not be injected when enableAgentTracking is false (default)");

        // With enableAgentTracking=true: agent_id must be injected
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        McpSyncServer serverEnabled = buildSyncServer();
        AgentCat.track(serverEnabled, "proj_test", opts);
        Map<String, Object> schema = getToolSchema(serverEnabled.getAsyncServer(), "echo");
        assertTrue(schemaProps(schema).containsKey("agent_id"),
                "agent_id must be injected when enableAgentTracking is true");
    }

    /**
     * context must appear in the schema only when enableToolCallContext is true,
     * and must never be injected into get_more_tools.
     */
    @Test
    void contextInjectedOnlyWhenEnabledAndNotOnGetMoreTools() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(true);

        McpSyncServer server = buildSyncServer();
        AgentCat.track(server, "proj_test", opts);

        // echo must have context
        assertTrue(schemaProps(getToolSchema(server.getAsyncServer(), "echo")).containsKey("context"),
                "context must be injected into regular tools when enableToolCallContext is true");

        // get_more_tools already has context in its own properties — must not appear twice
        Map<String, Object> gmtProps = schemaProps(getToolSchema(server.getAsyncServer(), "get_more_tools"));
        assertEquals(1, gmtProps.keySet().stream().filter("context"::equals).count(),
                "get_more_tools must have exactly one context entry in properties (its own, not duplicated)");
    }

    /**
     * When enableTracing is false, no parameters must be injected into any schema.
     */
    @Test
    void noInjectionWhenTracingDisabled() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(false);

        McpSyncServer server = buildSyncServer();
        AgentCat.track(server, "proj_test", opts);

        Map<String, Object> props = schemaProps(getToolSchema(server.getAsyncServer(), "echo"));
        // Handle params (session_id, agent_id) require enableTracing.
        // Context injection is controlled independently by enableToolCallContext.
        boolean hasHandleParams = props != null
                && (props.containsKey("session_id") || props.containsKey("agent_id"));
        assertFalse(hasHandleParams, "session_id and agent_id must not be injected when enableTracing is false");
    }

    /**
     * When a tool declares its own session_id, AgentCat must not overwrite it,
     * and track() must not throw.
     */
    @Test
    void collisionSkippedWithoutThrowing() throws Exception {
        McpSchema.JsonSchema schemaWithConflict = McpSchema.JsonSchema.builder()
                .type("object")
                .properties(Map.of(
                        "session_id", Map.of("type", "string", "description", "tool-owned"),
                        "input",      Map.of("type", "string")
                ))
                .required(List.of("input"))
                .build();

        McpSyncServer server = McpServer.sync(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("conflict_tool")
                                .description("Tool with own session_id")
                                .inputSchema(schemaWithConflict)
                                .build(),
                        new EchoHandler()
                )
                .build();

        assertDoesNotThrow(() -> AgentCat.track(server, "proj_test"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sessionIdProp = (Map<String, Object>)
                schemaProps(getToolSchema(server.getAsyncServer(), "conflict_tool")).get("session_id");
        assertEquals("tool-owned", sessionIdProp.get("description"),
                "tool's own session_id must not be overwritten by AgentCat");
    }

    /**
     * additionalProperties: false must be removed so injected parameters pass validation.
     *
     * <p>We inject {@code additionalProperties: false} directly into the raw schema map
     * (McpSchema.JsonSchema extends Map, so it is mutable) before calling track().
     * After track() the constraint must be gone and session_id must be present.
     */
    @Test
    @SuppressWarnings("unchecked")
    void additionalPropertiesFalseIsRemoved() throws Exception {
        McpSyncServer server = McpServer.sync(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("strict_tool")
                                .description("Tool with additionalProperties false")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("input", Map.of("type", "string")))
                                        .required(List.of("input"))
                                        .build())
                                .build(),
                        new EchoHandler()
                )
                .build();

        // Inject additionalProperties: false into the raw schema map before track().
        // McpSchema.JsonSchema is a Map subtype and is mutable at this point.
        List<McpServerFeatures.AsyncToolSpecification> preTrackTools =
                ReflectionUtils.readField(server.getAsyncServer(), "tools");
        preTrackTools.stream()
                .filter(t -> "strict_tool".equals(t.tool().name()))
                .findFirst()
                .map(t -> (Map<String, Object>) t.tool().inputSchema())
                .ifPresent(m -> m.put("additionalProperties", false));

        AgentCat.track(server, "proj_test");

        Map<String, Object> resultSchema = getToolSchema(server.getAsyncServer(), "strict_tool");
        assertNotEquals(Boolean.FALSE, resultSchema.get("additionalProperties"),
                "additionalProperties: false must be removed after injection");
        assertTrue(schemaProps(resultSchema).containsKey("session_id"),
                "session_id must still be injected after removing additionalProperties constraint");
    }

    /**
     * A tool added via addTool() AFTER track() must also receive schema injection.
     */
    @Test
    void toolAddedAfterTrackIsInjected() throws Exception {
        McpAsyncServer server = buildAsyncServer();
        AgentCat.track(server, "proj_test");

        server.addTool(new McpServerFeatures.AsyncToolSpecification(
                McpSchema.Tool.builder("late_tool")
                        .description("Added after track")
                        .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                        .build(),
                new AsyncEchoHandler()
        )).toFuture().get();

        Map<String, Object> schema = getToolSchema(server, "late_tool");
        assertNotNull(schemaProps(schema), "late_tool schema must have properties after injection");
        assertTrue(schemaProps(schema).containsKey("session_id"),
                "session_id must be injected into tools added after track()");
    }

    // ── 3.2 Parameter stripping tests ───────────────────────────────────────

    /**
     * The user's handler must NOT receive session_id in its arguments — it must be stripped
     * by the wrapper before the original handler is called.
     */
    @Test
    void handlerReceivesStrippedArgs() throws Exception {
        AtomicReference<Map<String, Object>> capturedArgs = new AtomicReference<>();

        McpAsyncServer server = McpServer.async(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        (exchange, request) -> {
                            capturedArgs.set(request.arguments());
                            return Mono.just(McpSchema.CallToolResult.builder().addTextContent("ok").build());
                        }
                )
                .build();

        AgentCat.track(server, "proj_test");

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name()))
                .findFirst()
                .orElseThrow();

        // Send request including session_id and a real tool param
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("session_id", "start");
        args.put("my_param", "hello");
        echo.callHandler().apply(null, new McpSchema.CallToolRequest("echo", args)).block();

        assertNotNull(capturedArgs.get(), "Handler must receive arguments");
        assertFalse(capturedArgs.get().containsKey("session_id"),
                "session_id must be stripped before the handler receives args");
        assertTrue(capturedArgs.get().containsKey("my_param"),
                "Real tool params must pass through unchanged");
        assertEquals("hello", capturedArgs.get().get("my_param"));
    }

    /**
     * When agent_id stripping is enabled, the handler must not see agent_id either.
     */
    @Test
    void handlerDoesNotReceiveAgentIdWhenEnabled() throws Exception {
        AtomicReference<Map<String, Object>> capturedArgs = new AtomicReference<>();

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);

        McpAsyncServer server = McpServer.async(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        (exchange, request) -> {
                            capturedArgs.set(request.arguments());
                            return Mono.just(McpSchema.CallToolResult.builder().addTextContent("ok").build());
                        }
                )
                .build();

        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name()))
                .findFirst()
                .orElseThrow();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("session_id", "start");
        args.put("agent_id", "my-agent-v1");
        args.put("real_param", "value");
        echo.callHandler().apply(null, new McpSchema.CallToolRequest("echo", args)).block();

        assertNotNull(capturedArgs.get());
        assertFalse(capturedArgs.get().containsKey("session_id"), "session_id must be stripped");
        assertFalse(capturedArgs.get().containsKey("agent_id"), "agent_id must be stripped when enableAgentTracking");
        assertTrue(capturedArgs.get().containsKey("real_param"), "real params must pass through");
    }

    // ── 3.3 Session management tests ─────────────────────────────────────────

    /**
     * Freshly minted session IDs must match the ses_XXXXXXX pattern.
     */
    @Test
    void sessionIdHasCorrectFormat() {
        String id = SessionIdProvider.newSessionId();
        assertTrue(id.startsWith("ses_"), "session_id must start with ses_");
        assertEquals(31, id.length(), "session_id must be 31 chars (ses_ + 27)");
        assertTrue(SessionIdProvider.isValid(id), "freshly minted session_id must pass isValid()");
    }

    /**
     * Two minted IDs must not be equal (with overwhelming probability).
     */
    @Test
    void sessionIdsAreUnique() {
        String a = SessionIdProvider.newSessionId();
        String b = SessionIdProvider.newSessionId();
        assertNotEquals(a, b, "consecutive session IDs should not be equal");
    }

    /**
     * isValid() accepts only canonical ses_ KSUIDs and rejects everything else.
     */
    @Test
    void sessionIdValidationAcceptsCanonicalForm() {
        // Valid: ses_ + 27 base-62 chars
        assertTrue(SessionIdProvider.isValid("ses_000000000000000000000000000"));
        assertTrue(SessionIdProvider.isValid("ses_9AZaz9AZaz9AZaz9AZaz9AZaz91"));
        String minted = SessionIdProvider.newSessionId();
        assertTrue(SessionIdProvider.isValid(minted), "minted id must be valid");

        // Invalid cases
        assertFalse(SessionIdProvider.isValid(null));
        assertFalse(SessionIdProvider.isValid("start"));
        assertFalse(SessionIdProvider.isValid(""));
        assertFalse(SessionIdProvider.isValid("ses_tooshort"));
        assertFalse(SessionIdProvider.isValid("ses_tooloooooooooooooooooooooooooong"));
        assertFalse(SessionIdProvider.isValid("ses_!@#$%^&invalid!!!!!!!!!!!!!!!!"));
        assertFalse(SessionIdProvider.isValid("abc_0000000000000000000000000"));  // wrong prefix
    }

    /**
     * Absent session_id → MINTED with a valid new KSUID.
     */
    @Test
    void resolvesAbsentSessionIdAsMinted() {
        HandleResolution r = SessionManager.resolveHandles(Map.of(), "echo", new AgentCatOptions());
        assertEquals(SessionSource.MINTED, r.getSessionSource());
        assertTrue(SessionIdProvider.isValid(r.getSessionId()), "minted id must be valid");
    }

    /**
     * session_id = "start" → MINTED (the sentinel triggers a fresh session).
     */
    @Test
    void resolvesStartSentinelAsMinted() {
        HandleResolution r = SessionManager.resolveHandles(
                Map.of("session_id", "start"), "echo", new AgentCatOptions());
        assertEquals(SessionSource.MINTED, r.getSessionSource());
        assertTrue(SessionIdProvider.isValid(r.getSessionId()));
    }

    /**
     * session_id = "START" → MINTED (case-insensitive sentinel check).
     */
    @Test
    void resolvesStartSentinelCaseInsensitive() {
        HandleResolution r = SessionManager.resolveHandles(
                Map.of("session_id", "START"), "echo", new AgentCatOptions());
        assertEquals(SessionSource.MINTED, r.getSessionSource());
    }

    /**
     * Valid ses_ KSUID → SUPPLIED, echoed back as-is.
     */
    @Test
    void resolvesValidSessionIdAsSupplied() {
        String id = SessionIdProvider.newSessionId();
        HandleResolution r = SessionManager.resolveHandles(
                Map.of("session_id", id), "echo", new AgentCatOptions());
        assertEquals(SessionSource.SUPPLIED, r.getSessionSource());
        assertEquals(id, r.getSessionId(), "supplied valid id must be echoed back unchanged");
    }

    /**
     * Unrecognised session_id → INVALID, empty sessionId (sessionless event).
     * Mirror TypeScript: publish sessionless rather than mint a surrogate.
     */
    @Test
    void resolvesInvalidSessionIdAsInvalid() {
        HandleResolution r = SessionManager.resolveHandles(
                Map.of("session_id", "totally-wrong"), "echo", new AgentCatOptions());
        assertEquals(SessionSource.INVALID, r.getSessionSource());
        assertEquals("", r.getSessionId(), "invalid session_id must yield empty sessionId (sessionless event)");
    }

    /**
     * agent_id is captured only when enableAgentTracking is true.
     */
    @Test
    void agentIdCapturedOnlyWhenEnabled() {
        Map<String, Object> args = Map.of("agent_id", "my-agent");

        AgentCatOptions disabled = new AgentCatOptions();
        disabled.setEnableAgentTracking(false);
        HandleResolution rDisabled = SessionManager.resolveHandles(args, "echo", disabled);
        assertNull(rDisabled.getAgentId(), "agentId must be null when tracking disabled");

        AgentCatOptions enabled = new AgentCatOptions();
        enabled.setEnableAgentTracking(true);
        HandleResolution rEnabled = SessionManager.resolveHandles(args, "echo", enabled);
        assertEquals("my-agent", rEnabled.getAgentId(), "agentId must be captured when tracking enabled");
    }

    // ── 3.4 Mint-back tests ──────────────────────────────────────────────────

    /**
     * When session_id is absent (MINTED), the first content element of the result
     * must start with the issued header and contain the ses_ ID.
     */
    @Test
    void mintedSessionPrependsMintBackText() throws Exception {
        McpAsyncServer server = McpServer.async(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        (exchange, request) ->
                                Mono.just(McpSchema.CallToolResult.builder()
                                        .addTextContent("original response")
                                        .build())
                )
                .build();

        AgentCat.track(server, "proj_test");

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        // Call with no session_id → MINTED
        McpSchema.CallToolResult result = echo.callHandler()
                .apply(null, new McpSchema.CallToolRequest("echo", Map.of()))
                .block();

        assertNotNull(result);
        assertNotNull(result.content());
        assertTrue(result.content().size() >= 2,
                "Result must have at least 2 content blocks after mint-back prepend");

        McpSchema.Content first = result.content().get(0);
        assertTrue(first instanceof McpSchema.TextContent,
                "First content element must be a TextContent");
        String firstText = ((McpSchema.TextContent) first).text();
        assertTrue(firstText.contains("[session_id issued"),
                "Mint-back block must contain the issued header");
        assertTrue(firstText.contains("ses_"),
                "Mint-back block must contain the ses_ session ID");

        // Original response must still be present
        McpSchema.Content second = result.content().get(1);
        assertEquals("original response", ((McpSchema.TextContent) second).text());
    }

    /**
     * When a valid session_id is supplied (SUPPLIED), the result must be returned
     * unchanged — no mint-back text prepended.
     */
    @Test
    void suppliedSessionDoesNotPrependMintBack() throws Exception {
        McpAsyncServer server = McpServer.async(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        (exchange, request) ->
                                Mono.just(McpSchema.CallToolResult.builder()
                                        .addTextContent("original response")
                                        .build())
                )
                .build();

        AgentCat.track(server, "proj_test");

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        String validId = SessionIdProvider.newSessionId();
        McpSchema.CallToolResult result = echo.callHandler()
                .apply(null, new McpSchema.CallToolRequest("echo", Map.of("session_id", validId)))
                .block();

        assertNotNull(result);
        assertEquals(1, result.content().size(),
                "SUPPLIED session must not add extra content blocks");
        assertEquals("original response", ((McpSchema.TextContent) result.content().get(0)).text());
    }

    /**
     * When an invalid session_id is sent (INVALID), the first content element must
     * carry the unrecognised header.
     */
    @Test
    void invalidSessionPrependsUnrecognizedHeader() throws Exception {
        McpAsyncServer server = McpServer.async(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        (exchange, request) ->
                                Mono.just(McpSchema.CallToolResult.builder()
                                        .addTextContent("original response")
                                        .build())
                )
                .build();

        AgentCat.track(server, "proj_test");

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        McpSchema.CallToolResult result = echo.callHandler()
                .apply(null, new McpSchema.CallToolRequest("echo",
                        Map.of("session_id", "totally-invalid-value")))
                .block();

        assertNotNull(result);
        assertTrue(result.content().size() >= 2);
        String firstText = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(firstText.contains("[session_id unrecognized"),
                "INVALID session must prepend the unrecognized header");
    }

    /**
     * When enableTracing is false, no mint-back must be added.
     */
    @Test
    void noMintBackWhenTracingDisabled() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(false);

        McpAsyncServer server = McpServer.async(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        (exchange, request) ->
                                Mono.just(McpSchema.CallToolResult.builder()
                                        .addTextContent("original response")
                                        .build())
                )
                .build();

        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        McpSchema.CallToolResult result = echo.callHandler()
                .apply(null, new McpSchema.CallToolRequest("echo", Map.of()))
                .block();

        assertNotNull(result);
        assertEquals(1, result.content().size(),
                "No mint-back content when tracing is disabled");
        assertEquals("original response", ((McpSchema.TextContent) result.content().get(0)).text());
    }

    /**
     * MintBack unit: buildMintBackText returns null for SUPPLIED.
     */
    @Test
    void buildMintBackTextReturnsNullForSupplied() {
        String id = SessionIdProvider.newSessionId();
        HandleResolution supplied = new HandleResolution(id, SessionSource.SUPPLIED, null);
        assertNull(MintBackHandler.buildMintBackText(supplied),
                "SUPPLIED session must produce no mint-back text");
    }

    /**
     * MintBack unit: buildMintBackText contains the session ID for MINTED.
     */
    @Test
    void buildMintBackTextContainsSessionIdForMinted() {
        String id = SessionIdProvider.newSessionId();
        HandleResolution minted = new HandleResolution(id, SessionSource.MINTED, null);
        String text = MintBackHandler.buildMintBackText(minted);
        assertNotNull(text);
        assertTrue(text.contains(id), "Mint-back text must include the minted session ID");
        assertTrue(text.contains("[session_id issued"));
    }

    /**
     * MintBack unit: appendMintBack does not mutate the original result.
     */
    @Test
    void appendMintBackDoesNotMutateOriginal() {
        McpSchema.CallToolResult original = McpSchema.CallToolResult.builder()
                .addTextContent("original")
                .build();
        int originalSize = original.content().size();

        MintBackHandler.appendMintBack(original, "prepended");

        assertEquals(originalSize, original.content().size(),
                "appendMintBack must not mutate the original result");
    }

    // ── Schema helpers ───────────────────────────────────────────────────────

    /** Returns the raw inputSchema map for a named tool on a sync server. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> getToolSchema(McpAsyncServer asyncServer,
                                                      String toolName) throws Exception {
        List<McpServerFeatures.AsyncToolSpecification> tools =
                ReflectionUtils.readField(asyncServer, "tools");
        return (Map<String, Object>) tools.stream()
                .filter(t -> toolName.equals(t.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tool not found: " + toolName))
                .tool()
                .inputSchema();
    }

    /** Extracts {@code properties} from a raw schema map. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaProps(Map<String, Object> schema) {
        if (schema == null) return Map.of();
        Object p = schema.get("properties");
        return p instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** Extracts {@code required} from a raw schema map. */
    @SuppressWarnings("unchecked")
    private static List<String> schemaRequired(Map<String, Object> schema) {
        if (schema == null) return List.of();
        Object r = schema.get("required");
        return r instanceof List<?> l ? (List<String>) l : List.of();
    }

    // ── Tool handler classes ──────────────────────────────────────────────────

    /** Returns a fixed text response. Used wherever a working sync tool is needed. */
    private static class EchoHandler
            implements java.util.function.BiFunction<
                io.modelcontextprotocol.server.McpSyncServerExchange,
                McpSchema.CallToolRequest,
                McpSchema.CallToolResult> {

        @Override
        public McpSchema.CallToolResult apply(
                io.modelcontextprotocol.server.McpSyncServerExchange exchange,
                McpSchema.CallToolRequest request) {
            return McpSchema.CallToolResult.builder().addTextContent("echo").build();
        }
    }

    /** Records that it was called. Used to verify the original sync handler is still invoked after wrapping. */
    private static class RecordingHandler
            implements java.util.function.BiFunction<
                io.modelcontextprotocol.server.McpSyncServerExchange,
                McpSchema.CallToolRequest,
                McpSchema.CallToolResult> {

        private final AtomicBoolean wasCalled;

        RecordingHandler(AtomicBoolean wasCalled) {
            this.wasCalled = wasCalled;
        }

        @Override
        public McpSchema.CallToolResult apply(
                io.modelcontextprotocol.server.McpSyncServerExchange exchange,
                McpSchema.CallToolRequest request) {
            wasCalled.set(true);
            return McpSchema.CallToolResult.builder().addTextContent("pong").build();
        }
    }

    /** Returns a fixed text response. Used wherever a working async tool is needed. */
    private static class AsyncEchoHandler
            implements BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> {

        @Override
        public Mono<McpSchema.CallToolResult> apply(
                McpAsyncServerExchange exchange,
                McpSchema.CallToolRequest request) {
            return Mono.just(McpSchema.CallToolResult.builder().addTextContent("echo").build());
        }
    }

    /** Records that it was called. Used to verify the original async handler is still invoked after wrapping. */
    private static class AsyncRecordingHandler
            implements BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> {

        private final AtomicBoolean wasCalled;

        AsyncRecordingHandler(AtomicBoolean wasCalled) {
            this.wasCalled = wasCalled;
        }

        @Override
        public Mono<McpSchema.CallToolResult> apply(
                McpAsyncServerExchange exchange,
                McpSchema.CallToolRequest request) {
            wasCalled.set(true);
            return Mono.just(McpSchema.CallToolResult.builder().addTextContent("pong").build());
        }
    }

    /** Stateless echo: returns a fixed response. Used wherever a working stateless sync tool is needed. */
    private static class StatelessEchoHandler
            implements BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

        @Override
        public McpSchema.CallToolResult apply(McpTransportContext ctx, McpSchema.CallToolRequest request) {
            return McpSchema.CallToolResult.builder().addTextContent("echo").build();
        }
    }

    /** Stateless recording handler (sync). Records that it was called. */
    private static class StatelessRecordingHandler
            implements BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

        private final AtomicBoolean wasCalled;

        StatelessRecordingHandler(AtomicBoolean wasCalled) {
            this.wasCalled = wasCalled;
        }

        @Override
        public McpSchema.CallToolResult apply(McpTransportContext ctx, McpSchema.CallToolRequest request) {
            wasCalled.set(true);
            return McpSchema.CallToolResult.builder().addTextContent("pong").build();
        }
    }

    /** Stateless async echo: returns a fixed response reactively. */
    private static class StatelessAsyncEchoHandler
            implements BiFunction<McpTransportContext, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> {

        @Override
        public Mono<McpSchema.CallToolResult> apply(McpTransportContext ctx, McpSchema.CallToolRequest request) {
            return Mono.just(McpSchema.CallToolResult.builder().addTextContent("echo").build());
        }
    }

    /** Stateless async recording handler. Records that it was called. */
    private static class StatelessAsyncRecordingHandler
            implements BiFunction<McpTransportContext, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> {

        private final AtomicBoolean wasCalled;

        StatelessAsyncRecordingHandler(AtomicBoolean wasCalled) {
            this.wasCalled = wasCalled;
        }

        @Override
        public Mono<McpSchema.CallToolResult> apply(McpTransportContext ctx, McpSchema.CallToolRequest request) {
            wasCalled.set(true);
            return Mono.just(McpSchema.CallToolResult.builder().addTextContent("pong").build());
        }
    }

    // ── Section 5: Hook invocation tests ─────────────────────────────────────

    /**
     * identify hook must be called on each tool invocation.
     */
    @Test
    void identifyHookIsInvoked() throws Exception {
        AtomicBoolean hookCalled = new AtomicBoolean(false);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) -> {
            hookCalled.set(true);
            return CompletableFuture.completedFuture(new UserIdentity("user-123"));
        });

        McpAsyncServer server = buildAsyncServer();
        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        echo.callHandler().apply(null, new McpSchema.CallToolRequest("echo", Map.of())).block();

        assertTrue(hookCalled.get(), "identify hook must be invoked on tool call");
    }

    /**
     * identify hook must not affect the tool response — it is fire-and-forget.
     */
    @Test
    void identifyHookDoesNotAffectToolResult() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.completedFuture(
                        new UserIdentity("user-123", "Alice", Map.of("role", "admin"))));

        McpAsyncServer server = McpServer.async(transport())
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

        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        McpSchema.CallToolResult result = echo.callHandler()
                .apply(null, new McpSchema.CallToolRequest("echo", Map.of())).block();

        assertNotNull(result, "identify hook must not null-out the tool result");
        assertFalse(Boolean.TRUE.equals(result.isError()),
                "identify hook must not cause the tool to return an error");
    }

    /**
     * When resolveSessionId hook is configured (hookMode), session_id must NOT be injected
     * into the tool input schema.
     */
    @Test
    void resolveSessionIdHookMode_sessionIdNotInjectedIntoSchema() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setResolveSessionId((request, extra) ->
                CompletableFuture.completedFuture("user@example.com"));

        McpSyncServer server = buildSyncServer();
        AgentCat.track(server, "proj_test", opts);

        Map<String, Object> props = schemaProps(getToolSchema(server.getAsyncServer(), "echo"));

        assertFalse(props.containsKey("session_id"),
                "session_id must NOT be injected into the schema when resolveSessionId hook is set (hookMode)");
    }

    /**
     * When resolveSessionId hook is configured (hookMode), the tool response must not
     * contain any mint-back text — the hook owns session management entirely.
     */
    @Test
    void resolveSessionIdHookMode_noMintBackInResponse() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setResolveSessionId((request, extra) ->
                CompletableFuture.completedFuture("user@example.com"));

        McpAsyncServer server = McpServer.async(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        (exchange, request) ->
                                Mono.just(McpSchema.CallToolResult.builder().addTextContent("original").build())
                )
                .build();

        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        McpSchema.CallToolResult result = echo.callHandler()
                .apply(null, new McpSchema.CallToolRequest("echo", Map.of())).block();

        assertNotNull(result);
        assertEquals(1, result.content().size(),
                "No mint-back block must be added in hookMode (resolveSessionId configured)");
        assertEquals("original", ((McpSchema.TextContent) result.content().get(0)).text(),
                "Original response must be returned unchanged in hookMode");
    }

    /**
     * eventTags hook must be called on each tool invocation.
     */
    @Test
    void eventTagsHookIsInvoked() throws Exception {
        AtomicBoolean hookCalled = new AtomicBoolean(false);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventTags((request, extra) -> {
            hookCalled.set(true);
            return CompletableFuture.completedFuture(Map.of("env", "test"));
        });

        McpAsyncServer server = buildAsyncServer();
        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        echo.callHandler().apply(null, new McpSchema.CallToolRequest("echo", Map.of())).block();

        assertTrue(hookCalled.get(), "eventTags hook must be invoked on tool call");
    }

    /**
     * eventProperties hook must be called on each tool invocation.
     */
    @Test
    void eventPropertiesHookIsInvoked() throws Exception {
        AtomicBoolean hookCalled = new AtomicBoolean(false);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventProperties((request, extra) -> {
            hookCalled.set(true);
            return CompletableFuture.completedFuture(Map.of("sdk_version", "2.0"));
        });

        McpAsyncServer server = buildAsyncServer();
        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        echo.callHandler().apply(null, new McpSchema.CallToolRequest("echo", Map.of())).block();

        assertTrue(hookCalled.get(), "eventProperties hook must be invoked on tool call");
    }

    // ── track() fault-containment: catch(Throwable) branches ─────────────────
    //
    // Each track() overload wraps its body in try/catch(Throwable).
    // Passing null as the server causes a NullPointerException inside the try block,
    // exercising the catch path. The method must absorb the exception and return
    // the original (null) server reference.

    @Test
    void trackSyncServerCatchThrowableDoesNotThrow() {
        // track(McpSyncServer) catch(Throwable t): NPE on null.getServerInfo()
        assertDoesNotThrow(() -> AgentCat.track((McpSyncServer) null, "proj"),
                "track(McpSyncServer) must swallow exceptions and not propagate");
    }

    @Test
    void trackSyncServerCatchThrowableReturnsNullServer() {
        McpSyncServer result = AgentCat.track((McpSyncServer) null, "proj");
        assertNull(result, "track(McpSyncServer) must return the original server reference (null)");
    }

    @Test
    void trackAsyncServerCatchThrowableDoesNotThrow() {
        // track(McpAsyncServer) catch(Throwable t): NPE on null.getServerInfo()
        assertDoesNotThrow(() -> AgentCat.track((McpAsyncServer) null, "proj"),
                "track(McpAsyncServer) must swallow exceptions and not propagate");
    }

    @Test
    void trackAsyncServerCatchThrowableReturnsNullServer() {
        McpAsyncServer result = AgentCat.track((McpAsyncServer) null, "proj");
        assertNull(result);
    }

    @Test
    void trackStatelessSyncCatchThrowableDoesNotThrow() {
        // track(McpStatelessSyncServer) catch(Throwable t)
        assertDoesNotThrow(() -> AgentCat.track((McpStatelessSyncServer) null, "proj"),
                "track(McpStatelessSyncServer) must swallow exceptions and not propagate");
    }

    @Test
    void trackStatelessSyncCatchThrowableReturnsNullServer() {
        McpStatelessSyncServer result = AgentCat.track((McpStatelessSyncServer) null, "proj");
        assertNull(result);
    }

    @Test
    void trackStatelessAsyncCatchThrowableDoesNotThrow() {
        // track(McpStatelessAsyncServer) catch(Throwable t)
        assertDoesNotThrow(() -> AgentCat.track((McpStatelessAsyncServer) null, "proj"),
                "track(McpStatelessAsyncServer) must swallow exceptions and not propagate");
    }

    @Test
    void trackStatelessAsyncCatchThrowableReturnsNullServer() {
        McpStatelessAsyncServer result = AgentCat.track((McpStatelessAsyncServer) null, "proj");
        assertNull(result);
    }

    // ── publishCustomEvent fault-containment: catch(Throwable) branch ─────────

    @Test
    void publishCustomEventCatchThrowableDoesNotThrow() {
        // server=null → NPE on null.getServerInfo() → caught by catch(Throwable t)
        assertDoesNotThrow(() -> AgentCat.publishCustomEvent((McpSyncServer) null, "proj", null),
                "publishCustomEvent must swallow unexpected exceptions and not propagate");
    }

    @Test
    void publishCustomEventBlankProjectIdIsSwallowed() {
        // blank projectId → silently returns (logged, not thrown) — fault containment
        assertDoesNotThrow(
                () -> AgentCat.publishCustomEvent((McpSyncServer) null, "", null),
                "blank projectId must be swallowed — SDK must never throw to the caller");
    }

    // ── ensureToolCapabilities fault-containment ──────────────────────────────
    //
    // ensureToolCapabilities() is called by trackAsyncServer() which is called by
    // track(McpAsyncServer). When the internal reflection throws unexpectedly the
    // outer catch(Throwable) in ensureToolCapabilities() swallows the error and
    // track() continues normally.
    //
    // Triggering the inner guard returns (sessionFactoryField==null, factory==null,
    // handlersField==null, requestHandlers.containsKey("tools/list")) requires
    // SDK-internal transport structures that vary by SDK version and are not
    // reproducible in unit tests. The Throwable catch is exercised via the
    // track(McpAsyncServer=null) test above (NPE propagates through trackAsyncServer
    // → ensureToolCapabilities where it is caught).
    //
    // The "already registered" guard (requestHandlers.containsKey("tools/list")) is
    // exercised by calling track() twice on the same server — the second call finds
    // the handlers already present and returns early.

    @Test
    void ensureToolCapabilitiesIsIdempotent() throws Exception {
        // Calling track() twice must not throw — the second call finds tools/list
        // already in requestHandlers (or tools() != null) and returns early.
        McpAsyncServer server = buildAsyncServer();
        assertDoesNotThrow(() -> AgentCat.track(server, "proj_test"),
                "first track() call must not throw");
        assertDoesNotThrow(() -> AgentCat.track(server, "proj_test"),
                "second track() call must not throw (ensureToolCapabilities is idempotent)");
    }

    // ── Section 5: Fault containment ─────────────────────────────────────────

    /**
     * A hook that throws synchronously must not break the tool response (fault containment).
     */
    @Test
    void throwingHookDoesNotBreakToolCall() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) -> {
            throw new RuntimeException("identify hook exploded synchronously");
        });
        opts.setEventTags((request, extra) -> {
            throw new RuntimeException("eventTags hook exploded synchronously");
        });
        opts.setEventProperties((request, extra) -> {
            throw new RuntimeException("eventProperties hook exploded synchronously");
        });

        McpAsyncServer server = McpServer.async(transport())
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

        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        McpSchema.CallToolResult result = echo.callHandler()
                .apply(null, new McpSchema.CallToolRequest("echo", Map.of())).block();

        assertNotNull(result, "Throwing hook must not null-out the tool result");
        assertFalse(Boolean.TRUE.equals(result.isError()),
                "Throwing hook must not cause the tool to return an error");
    }

    /**
     * A hook returning a failed CompletableFuture must not break the tool response.
     */
    @Test
    void failedFutureHookDoesNotBreakToolCall() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.failedFuture(new RuntimeException("identify future failed")));

        McpAsyncServer server = McpServer.async(transport())
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

        AgentCat.track(server, "proj_test", opts);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(server, "tools");
        McpServerFeatures.AsyncToolSpecification echo = tools.stream()
                .filter(t -> "echo".equals(t.tool().name())).findFirst().orElseThrow();

        McpSchema.CallToolResult result = echo.callHandler()
                .apply(null, new McpSchema.CallToolRequest("echo", Map.of())).block();

        assertNotNull(result, "Failed-future hook must not null-out the tool result");
        assertFalse(Boolean.TRUE.equals(result.isError()),
                "Failed-future hook must not cause the tool to return an error");
    }

    // ── toMap() — private helper, tested via reflection ──────────────────────
    //
    // toMap(Object) converts arbitrary parameters/response values into a Map:
    //   null          → null
    //   Map           → same Map instance (the Map branch, currently uncovered)
    //   non-Map value → {"value": <value>}   (the wrapper branch, currently uncovered)

    @SuppressWarnings("unchecked")
    private static <T> T invokeToMap(Object value) throws Exception {
        java.lang.reflect.Method m = AgentCat.class.getDeclaredMethod("toMap", Object.class);
        m.setAccessible(true);
        return (T) m.invoke(null, value);
    }

    @Test
    void toMapReturnsNullForNullInput() throws Exception {
        assertNull(invokeToMap(null), "toMap(null) must return null");
    }

    @Test
    void toMapReturnsSameMapInstance() throws Exception {
        // Map branch: if (value instanceof Map<?, ?>) return (Map<String, Object>) value;
        Map<String, Object> map = new java.util.HashMap<>(Map.of("key", "value"));
        Map<String, Object> result = invokeToMap(map);
        assertSame(map, result,
                "toMap must return the identical Map instance, not a copy");
    }

    @Test
    void toMapMapBranchPreservesContents() throws Exception {
        Map<String, Object> map = Map.of("x", 1, "y", "two");
        Map<String, Object> result = invokeToMap(map);
        assertEquals(map, result, "toMap must preserve all entries of the input Map");
    }

    @Test
    void toMapWrapsNonMapValueUnderValueKey() throws Exception {
        // wrapper branch: Map<String,Object> wrapper = new HashMap<>(); wrapper.put("value", value);
        String s = "hello";
        Map<String, Object> wrapper = invokeToMap(s);
        assertNotNull(wrapper, "toMap must wrap non-Map values in a HashMap");
        assertEquals("hello", wrapper.get("value"),
                "toMap must store the non-Map value under the \"value\" key");
    }

    @Test
    void toMapWrapsIntegerValue() throws Exception {
        Map<String, Object> wrapper = invokeToMap(42);
        assertNotNull(wrapper);
        assertEquals(42, wrapper.get("value"),
                "toMap must wrap integer under the \"value\" key");
    }

    @Test
    void toMapWrapperHasExactlyOneEntry() throws Exception {
        Map<String, Object> wrapper = invokeToMap("some string");
        assertEquals(1, wrapper.size(),
                "toMap wrapper map must contain exactly one entry: {\"value\": <input>}");
    }

    // ── ensureToolCapabilities inner guard branches ───────────────────────────
    //
    // ensureToolCapabilities() is a private static method. We invoke it via
    // reflection and manipulate the server's internal fields so that each of the
    // four guard conditions is exercised. Every branch must be fault-contained —
    // the method must never throw regardless of what it finds via reflection.

    private static void invokeEnsureToolCapabilities(McpAsyncServer server) throws Exception {
        java.lang.reflect.Method m = AgentCat.class.getDeclaredMethod(
                "ensureToolCapabilities", McpAsyncServer.class);
        m.setAccessible(true);
        m.invoke(null, server);
    }

    /**
     * Build an async server and override {@code serverCapabilities} to {@code null}
     * so that the early-exit guard
     * {@code if (caps != null && caps.tools() != null) return} is not taken.
     */
    private static McpAsyncServer buildAsyncServerWithNullCaps() throws Exception {
        McpAsyncServer server = buildAsyncServer();
        java.lang.reflect.Field f = ReflectionUtils.findFieldInHierarchy(
                McpAsyncServer.class, "serverCapabilities");
        if (f != null) {
            f.setAccessible(true);
            f.set(server, null);
        }
        return server;
    }

    /**
     * Guard: {@code if (sessionFactoryField == null) return}.
     *
     * <p>When the transport has no "sessionFactory" field anywhere in its hierarchy,
     * {@code findFieldInHierarchy} returns {@code null} and the method logs a non-fatal
     * message and returns. A dynamic Proxy of the transport interface has no declared
     * fields, guaranteeing this branch is taken.
     */
    @Test
    void ensureToolCapabilitiesGuard_sessionFactoryNotFound_doesNotThrow() throws Exception {
        McpAsyncServer server = buildAsyncServerWithNullCaps();
        java.lang.reflect.Field tf = ReflectionUtils.findFieldInHierarchy(
                McpAsyncServer.class, "mcpTransportProvider");
        if (tf != null) {
            tf.setAccessible(true);
            Class<?> type = tf.getType();
            if (type.isInterface()) {
                // Proxy has no declared fields → sessionFactory lookup returns null → guard fires
                Object noFieldProxy = java.lang.reflect.Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class[]{type},
                        (p, method, args) -> null);
                tf.set(server, noFieldProxy);
            }
        }
        assertDoesNotThrow(() -> invokeEnsureToolCapabilities(server),
                "ensureToolCapabilities must not throw when sessionFactory field is absent on transport");
    }

    /**
     * Guard: {@code if (factory == null) return}.
     *
     * <p>Set the transport's "sessionFactory" field to {@code null} so the
     * null-factory guard fires.
     */
    @Test
    void ensureToolCapabilitiesGuard_sessionFactoryIsNull_doesNotThrow() throws Exception {
        McpAsyncServer server = buildAsyncServerWithNullCaps();
        java.lang.reflect.Field tf = ReflectionUtils.findFieldInHierarchy(
                McpAsyncServer.class, "mcpTransportProvider");
        if (tf != null) {
            tf.setAccessible(true);
            Object transport = tf.get(server);
            if (transport != null) {
                java.lang.reflect.Field sf = ReflectionUtils.findFieldInHierarchy(
                        transport.getClass(), "sessionFactory");
                if (sf != null) {
                    sf.setAccessible(true);
                    sf.set(transport, null);
                }
            }
        }
        assertDoesNotThrow(() -> invokeEnsureToolCapabilities(server),
                "ensureToolCapabilities must not throw when sessionFactory value is null");
    }

    /**
     * Guard: {@code if (handlersField == null) return}.
     *
     * <p>Set the transport's "sessionFactory" to a plain {@code Object} instance.
     * {@code Object} has no "requestHandlers" field, so {@code findFieldInHierarchy}
     * returns {@code null} and the guard fires.
     */
    @Test
    void ensureToolCapabilitiesGuard_requestHandlersNotFound_doesNotThrow() throws Exception {
        McpAsyncServer server = buildAsyncServerWithNullCaps();
        java.lang.reflect.Field tf = ReflectionUtils.findFieldInHierarchy(
                McpAsyncServer.class, "mcpTransportProvider");
        if (tf != null) {
            tf.setAccessible(true);
            Object transport = tf.get(server);
            if (transport != null) {
                java.lang.reflect.Field sf = ReflectionUtils.findFieldInHierarchy(
                        transport.getClass(), "sessionFactory");
                if (sf != null) {
                    sf.setAccessible(true);
                    sf.set(transport, new Object()); // Object has no "requestHandlers" field
                }
            }
        }
        assertDoesNotThrow(() -> invokeEnsureToolCapabilities(server),
                "ensureToolCapabilities must not throw when requestHandlers field is absent on factory");
    }

    /**
     * Guard: {@code catch (Throwable t)}.
     *
     * <p>Set {@code mcpTransportProvider} to {@code null} so that
     * {@code transport.getClass()} throws {@code NullPointerException} inside the
     * {@code try} block. The catch must absorb it and return normally.
     */
    @Test
    void ensureToolCapabilitiesGuard_catchThrowable_doesNotThrow() throws Exception {
        McpAsyncServer server = buildAsyncServerWithNullCaps();
        java.lang.reflect.Field tf = ReflectionUtils.findFieldInHierarchy(
                McpAsyncServer.class, "mcpTransportProvider");
        if (tf != null) {
            tf.setAccessible(true);
            tf.set(server, null); // null transport → NPE at transport.getClass() → catch(Throwable)
        }
        assertDoesNotThrow(() -> invokeEnsureToolCapabilities(server),
                "ensureToolCapabilities must catch all Throwable and never propagate");
    }

    // ── Section 5: deriveSessionId tests ─────────────────────────────────────

    /**
     * deriveSessionId must produce a valid ses_ session ID.
     */
    @Test
    void deriveSessionIdProducesValidFormat() {
        String id = SessionIdProvider.deriveSessionId("user@example.com", "proj_test");
        assertTrue(id.startsWith("ses_"), "derived id must start with ses_");
        assertEquals(31, id.length(), "derived id must be 31 chars (ses_ + 27)");
        assertTrue(SessionIdProvider.isValid(id), "derived id must pass isValid()");
    }

    /**
     * deriveSessionId must be deterministic: same inputs always produce the same ID.
     */
    @Test
    void deriveSessionIdIsDeterministic() {
        String id1 = SessionIdProvider.deriveSessionId("user@example.com", "proj_test");
        String id2 = SessionIdProvider.deriveSessionId("user@example.com", "proj_test");
        assertEquals(id1, id2, "deriveSessionId must return the same ID for the same inputs");
    }

    /**
     * deriveSessionId must differ when the hook value differs.
     */
    @Test
    void deriveSessionIdDiffersForDifferentValues() {
        String id1 = SessionIdProvider.deriveSessionId("alice@example.com", "proj_test");
        String id2 = SessionIdProvider.deriveSessionId("bob@example.com", "proj_test");
        assertNotEquals(id1, id2, "deriveSessionId must produce distinct IDs for different hook values");
    }

    /**
     * deriveSessionId must differ when the project ID differs (namespace isolation).
     */
    @Test
    void deriveSessionIdDiffersForDifferentProjects() {
        String id1 = SessionIdProvider.deriveSessionId("user@example.com", "proj_A");
        String id2 = SessionIdProvider.deriveSessionId("user@example.com", "proj_B");
        assertNotEquals(id1, id2, "deriveSessionId must produce distinct IDs for different project IDs");
    }

}
