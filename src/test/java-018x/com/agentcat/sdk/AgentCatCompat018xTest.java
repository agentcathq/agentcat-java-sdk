package com.agentcat.sdk;

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
}
