package com.agentcat.sdk;

import com.agentcat.api.model.PublishServerInfoRequest;
import com.agentcat.sdk.modules.serverinfo.ServerInfoHeartbeat;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;
import java.time.Duration;
import com.agentcat.sdk.engine.McpSdkDetector;
import com.agentcat.sdk.engine.ReflectionUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compatibility tests for MCP Java SDK 0.18.x.
 *
 * Compiled and run via:  mvn test -Pmcp-018x
 *
 * Exercises the path that never runs in the default 2.x test suite:
 *   McpSdkDetector → McpSdkBridgeV1 → ParameterInjector → AsyncToolSpecFactory (3-arg ctor)
 */
class AgentCatCompat018xTest {

    // ── track() safety ────────────────────────────────────────────────────────

    @Test
    void trackDoesNotThrow() {
        assertDoesNotThrow(() -> AgentCat.track(buildServer(), "proj_test"));
    }

    @Test
    void trackDoesNotThrowOnBlankProjectId() {
        assertDoesNotThrow(() -> AgentCat.track(buildServer(), ""));
    }

    @Test
    void trackDoesNotThrowOnNullProjectId() {
        assertDoesNotThrow(() -> AgentCat.track(buildServer(), null));
    }

    // ── Schema injection ──────────────────────────────────────────────────────

    @Test
    void sessionIdInjectedIntoSchema() throws Exception {
        McpSyncServer server = buildServer();
        AgentCat.track(server, "proj_test");

        Map<String, Object> props = schemaProps(getToolSchema(server, "get_students"));
        assertTrue(props.containsKey("session_id"),
                "session_id must be injected into tool schema on MCP 0.18.x");
    }

    @Test
    void sessionIdCarriesPatternConstraint() throws Exception {
        McpSyncServer server = buildServer();
        AgentCat.track(server, "proj_test");

        @SuppressWarnings("unchecked")
        Map<String, Object> sessionIdProp = (Map<String, Object>)
                schemaProps(getToolSchema(server, "get_students")).get("session_id");
        assertEquals("^(start|ses_[0-9A-Za-z]{27})$", sessionIdProp.get("pattern"),
                "session_id must carry the KSUID pattern constraint on MCP 0.18.x");
    }

    @Test
    void originalPropsPreservedAfterInjection() throws Exception {
        McpSyncServer server = buildServer();
        AgentCat.track(server, "proj_test");

        Map<String, Object> props = schemaProps(getToolSchema(server, "get_students"));
        assertTrue(props.containsKey("course_id"),
                "Original tool property must still be present after injection");
    }

    // ── get_more_tools ────────────────────────────────────────────────────────

    @Test
    void getMoreToolsRegistered() throws Exception {
        McpSyncServer server = buildServer();
        AgentCat.track(server, "proj_test");

        assertTrue(getTools(server).stream().anyMatch(t -> "get_more_tools".equals(t.tool().name())),
                "get_more_tools must be added after track() on MCP 0.18.x");
    }

    // ── Handler invocation ────────────────────────────────────────────────────

    @Test
    void originalHandlerIsStillCalled() throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);

        McpSyncServer server = McpServer.sync(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder()
                                .name("ping")
                                .description("Test tool")
                                .inputSchema(new JsonSchema("object", Map.of(), List.of(), null, null, null))
                                .build(),
                        (exchange, request) -> {
                            called.set(true);
                            return McpSchema.CallToolResult.builder().addTextContent("pong").build();
                        })
                .build();

        AgentCat.track(server, "proj_test");
        invokeHandler(server, "ping", Map.of());

        assertTrue(called.get(), "Original handler must be called after AgentCat wraps it on MCP 0.18.x");
    }

    // ── Parameter stripping ───────────────────────────────────────────────────

    @Test
    void sessionIdStrippedBeforeHandlerReceivesArgs() throws Exception {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();

        McpSyncServer server = McpServer.sync(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder()
                                .name("get_students")
                                .description("Test tool")
                                .inputSchema(new JsonSchema("object",
                                        Map.of("course_id", Map.of("type", "string")),
                                        List.of("course_id"), null, null, null))
                                .build(),
                        (exchange, request) -> {
                            captured.set(request.arguments());
                            return McpSchema.CallToolResult.builder().addTextContent("ok").build();
                        })
                .build();

        AgentCat.track(server, "proj_test");

        Map<String, Object> args = new HashMap<>();
        args.put("session_id", "start");
        args.put("course_id", "CS101");
        invokeHandler(server, "get_students", args);

        assertFalse(captured.get().containsKey("session_id"),
                "session_id must be stripped before handler on MCP 0.18.x");
        assertEquals("CS101", captured.get().get("course_id"),
                "Real tool param must pass through unchanged");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static McpSyncServer buildServer() {
        return McpServer.sync(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(
                        McpSchema.Tool.builder()
                                .name("get_students")
                                .description("Returns students for a course.")
                                .inputSchema(new JsonSchema("object",
                                        Map.of("course_id", Map.of("type", "string")),
                                        List.of("course_id"), null, null, null))
                                .build(),
                        (exchange, request) -> McpSchema.CallToolResult.builder()
                                .addTextContent("ok").build())
                .build();
    }

    private static StdioServerTransportProvider transport() {
        return new StdioServerTransportProvider(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    private static List<McpServerFeatures.AsyncToolSpecification> getTools(
            McpSyncServer server) throws Exception {
        return ReflectionUtils.readField(server.getAsyncServer(), "tools");
    }

    private static Map<String, Object> getToolSchema(McpSyncServer server,
                                                      String toolName) throws Exception {
        McpSchema.Tool tool = getTools(server).stream()
                .filter(t -> toolName.equals(t.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tool not found: " + toolName))
                .tool();
        return McpSdkDetector.bridge().getInputSchema(tool);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaProps(Map<String, Object> schema) {
        Object p = schema == null ? null : schema.get("properties");
        return p instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static void invokeHandler(McpSyncServer server, String toolName,
                                       Map<String, Object> args) throws Exception {
        getTools(server).stream()
                .filter(t -> toolName.equals(t.tool().name()))
                .findFirst()
                .orElseThrow()
                .callHandler()
                .apply(null, new McpSchema.CallToolRequest(toolName, args))
                .block();
    }

    // ── Server info heartbeat ─────────────────────────────────────────────────

    private static final String HEARTBEAT_PROJECT = "proj_" + "a".repeat(27);

    @Test
    void serverInfoHeartbeatOfASyncServer() throws Exception {
        McpSyncServer server = AgentCat.track(McpServer.sync(transport())
                .serverInfo(new McpSchema.Implementation("demo-server", "Demo Title", "1.2.3"))
                .instructions("Use the demo tools.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).prompts(false).build())
                .toolCall(heartbeatTool(), (exchange, request) -> okResult())
                .prompts(new McpServerFeatures.SyncPromptSpecification(heartbeatPrompt(),
                        (exchange, request) -> new McpSchema.GetPromptResult("p", List.of())))
                .build(), HEARTBEAT_PROJECT);
        McpAsyncServer async = server.getAsyncServer();
        List<McpSchema.Tool> listed = async.listTools().collectList().block();
        List<McpServerFeatures.AsyncToolSpecification> specs = ReflectionUtils.readField(async, "tools");

        assertHeartbeat(listed, () -> specs.stream().filter(s -> "lookup".equals(s.tool().name())).findFirst()
                .orElseThrow().callHandler().apply(null, heartbeatCall()).block());
    }

    @Test
    void serverInfoHeartbeatOfAnAsyncServer() throws Exception {
        McpAsyncServer server = AgentCat.track(McpServer.async(transport())
                .serverInfo(new McpSchema.Implementation("demo-server", "Demo Title", "1.2.3"))
                .instructions("Use the demo tools.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).prompts(false).build())
                .toolCall(heartbeatTool(), (exchange, request) -> Mono.just(okResult()))
                .prompts(new McpServerFeatures.AsyncPromptSpecification(heartbeatPrompt(),
                        (exchange, request) -> Mono.just(new McpSchema.GetPromptResult("p", List.of()))))
                .build(), HEARTBEAT_PROJECT);
        List<McpSchema.Tool> listed = server.listTools().collectList().block();
        List<McpServerFeatures.AsyncToolSpecification> specs = ReflectionUtils.readField(server, "tools");

        assertHeartbeat(listed, () -> specs.stream().filter(s -> "lookup".equals(s.tool().name())).findFirst()
                .orElseThrow().callHandler().apply(null, heartbeatCall()).block());
    }

    @Test
    void serverInfoHeartbeatOfAStatelessSyncServer() throws Exception {
        McpStatelessSyncServer server = AgentCat.track(McpServer.sync(statelessTransport())
                .serverInfo(new McpSchema.Implementation("demo-server", "Demo Title", "1.2.3"))
                .instructions("Use the demo tools.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).prompts(false).build())
                .toolCall(heartbeatTool(), (ctx, request) -> okResult())
                .prompts(new McpStatelessServerFeatures.SyncPromptSpecification(heartbeatPrompt(),
                        (ctx, request) -> new McpSchema.GetPromptResult("p", List.of())))
                .build(), HEARTBEAT_PROJECT);
        McpStatelessAsyncServer async = ReflectionUtils.readField(server, "asyncServer");
        assertStatelessHeartbeat(async);
    }

    @Test
    void serverInfoHeartbeatOfAStatelessAsyncServer() throws Exception {
        McpStatelessAsyncServer server = AgentCat.track(McpServer.async(statelessTransport())
                .serverInfo(new McpSchema.Implementation("demo-server", "Demo Title", "1.2.3"))
                .instructions("Use the demo tools.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).prompts(false).build())
                .toolCall(heartbeatTool(), (ctx, request) -> Mono.just(okResult()))
                .prompts(new McpStatelessServerFeatures.AsyncPromptSpecification(heartbeatPrompt(),
                        (ctx, request) -> Mono.just(new McpSchema.GetPromptResult("p", List.of()))))
                .build(), HEARTBEAT_PROJECT);
        assertStatelessHeartbeat(server);
    }

    private static void assertStatelessHeartbeat(McpStatelessAsyncServer server) throws Exception {
        List<McpSchema.Tool> listed = server.listTools().collectList().block();
        List<McpStatelessServerFeatures.AsyncToolSpecification> specs = ReflectionUtils.readField(server, "tools");
        assertHeartbeat(listed, () -> specs.stream().filter(s -> "lookup".equals(s.tool().name())).findFirst()
                .orElseThrow().callHandler().apply(McpTransportContext.EMPTY, heartbeatCall()).block());
    }

    /**
     * {@code listTools()} gives the tools with the injected parameters and get_more_tools, the
     * instructions come from the library's private field, and one tool call sends them.
     */
    @SuppressWarnings("unchecked")
    private static void assertHeartbeat(List<McpSchema.Tool> listed, Runnable toolCall) throws Exception {
        assertEquals(List.of("lookup", "get_more_tools"), listed.stream().map(McpSchema.Tool::name).toList());
        Map<String, Object> listedProps = schemaProps(toMap(listed.get(0).inputSchema()));
        assertTrue(listedProps.containsKey("session_id"));
        assertTrue(listedProps.containsKey("context"));

        AgentCatTestExtension.ServerInfoRecorder recorder = AgentCatTestExtension.serverInfoRecorder();
        assertTrue(ServerInfoHeartbeat.awaitIdleForTesting(Duration.ofSeconds(40)));
        recorder.clear();
        ServerInfoHeartbeat.resetForTesting(() -> 1_000_000L, 60_000L);

        toolCall.run();

        assertTrue(ServerInfoHeartbeat.awaitIdleForTesting(Duration.ofSeconds(40)));
        List<PublishServerInfoRequest> posts = recorder.requests();
        assertEquals(1, posts.size());
        PublishServerInfoRequest body = posts.get(0);
        assertEquals("demo-server", body.getServerName());
        assertEquals("1.2.3", body.getServerVersion());
        assertEquals("Demo Title", body.getServerTitle());
        assertEquals("Use the demo tools.", body.getInstructions());
        Map<String, Object> features = (Map<String, Object>) body.getFeatures();
        assertEquals(List.of("tools", "prompts"), List.copyOf(features.keySet()));
        List<Map<String, Object>> tools = (List<Map<String, Object>>) features.get("tools");
        assertEquals(List.of("lookup", "get_more_tools"), tools.stream().map(t -> t.get("name")).toList());
        Map<String, Object> props = schemaProps((Map<String, Object>) tools.get(0).get("inputSchema"));
        assertTrue(props.containsKey("session_id"));
        assertTrue(props.containsKey("context"));
        assertTrue(props.containsKey("course_id"));
        assertEquals(1, ((List<?>) features.get("prompts")).size());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object schema) {
        return (Map<String, Object>) com.agentcat.sdk.modules.pipeline.JsonTree.toTree(schema);
    }

    private static McpSchema.Tool heartbeatTool() {
        return McpSchema.Tool.builder()
                .name("lookup")
                .description("Looks up a course.")
                .inputSchema(new JsonSchema("object",
                        Map.of("course_id", Map.of("type", "string")),
                        List.of("course_id"), null, null, null))
                .build();
    }

    private static McpSchema.Prompt heartbeatPrompt() {
        return new McpSchema.Prompt("greet", "Greet someone",
                List.of(new McpSchema.PromptArgument("name", "Who to greet", true)));
    }

    private static McpSchema.CallToolRequest heartbeatCall() {
        return new McpSchema.CallToolRequest("lookup", Map.of("course_id", "c1"));
    }

    private static McpSchema.CallToolResult okResult() {
        return McpSchema.CallToolResult.builder().addTextContent("ok").build();
    }

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
}
