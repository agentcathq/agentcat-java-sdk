package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.integration.TestUtils;
import com.agentcat.sdk.modules.handles.HandleTags;
import com.agentcat.sdk.modules.handles.MintBackHandler;
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
 * Ported from: src/tests/v2/agent-tracking.test.ts
 *
 * Two describe blocks:
 *   "v2 agent tracking: enableAgentTracking true" — 4 tests
 *   "v2 agent tracking: OFF by default"            — 1 test
 */
class AgentTrackingTest {

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

    /**
     * Corresponds to TypeScript {@code setupHighLevel()}: echo tool with outputSchema,
     * AgentCat tracking applied, schema registry primed via listTools().
     */
    private McpTestContext setupHighLevel(AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        Map<String, Object> outputSchema = new LinkedHashMap<>();
        outputSchema.put("type", "object");
        outputSchema.put("properties", Map.of("echoed", Map.of("type", "string")));
        outputSchema.put("required", List.of("echoed"));
        outputSchema.put("additionalProperties", false);

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("v2-agent-tracking", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("echo",
                                        Map.of("type", "object",
                                               "properties", Map.of("msg", Map.of("type", "string"))))
                                .description("Echo")
                                .outputSchema(outputSchema)
                                .build(),
                        (exchange, req) -> {
                            String msg = String.valueOf(req.arguments().get("msg"));
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("echo: " + msg)
                                    .structuredContent(new LinkedHashMap<>(Map.of("echoed", msg)))
                                    .build();
                        })
                .build();

        AgentCat.track(server, "proj_test", opts);
        McpTestContext ctx = McpTestContext.start(transport, server);
        ctx.client().listTools(); // prime the schema registry
        return ctx;
    }

    private AgentCatOptions withAgentTracking() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        return opts;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "v2 agent tracking: enableAgentTracking true"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void advertisesAgentIdAndSessionIdAsRequiredOnEveryToolIncludingGetMoreTools() throws Exception {
        // ported from: "advertises both handles as required on every tool including get_more_tools"
        try (McpTestContext ctx = setupHighLevel(withAgentTracking())) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();

            McpSchema.Tool echo = tools.stream()
                    .filter(t -> "echo".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("echo tool not found"));

            Map<String, Object> echoProps = (Map<String, Object>) echo.inputSchema().get("properties");
            assertNotNull(echoProps.get("agent_id"),   "echo must have agent_id in properties");
            assertNotNull(echoProps.get("session_id"), "echo must have session_id in properties");

            List<?> echoRequired = (List<?>) echo.inputSchema().get("required");
            assertNotNull(echoRequired, "echo must have a required array");
            assertTrue(echoRequired.contains("agent_id"),   "echo.required must contain agent_id");
            assertTrue(echoRequired.contains("session_id"), "echo.required must contain session_id");

            McpSchema.Tool gmt = tools.stream()
                    .filter(t -> "get_more_tools".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("get_more_tools tool not found"));

            Map<String, Object> gmtProps = (Map<String, Object>) gmt.inputSchema().get("properties");
            assertNotNull(gmtProps.get("agent_id"), "get_more_tools must have agent_id in properties");

            List<?> gmtRequired = (List<?>) gmt.inputSchema().get("required");
            assertNotNull(gmtRequired, "get_more_tools must have a required array");
            assertTrue(gmtRequired.contains("agent_id"),   "get_more_tools.required must contain agent_id");
            assertTrue(gmtRequired.contains("session_id"), "get_more_tools.required must contain session_id");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void toleratesOmissionTaskOnlyMintBackEventWithoutAgentIdentity() throws Exception {
        // ported from: "tolerates omission: task-only mint-back, event without agent identity"
        try (McpTestContext ctx = setupHighLevel(withAgentTracking())) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("msg", "hi", "context", "agent omission")));

            String block = TestUtils.mintBackOf(result);
            assertNotNull(block, "mint-back must be present on first call");
            assertTrue(block.contains("[session_id issued"), "must announce session_id issued");
            assertFalse(block.contains("agent_id"), "must not mention agent_id when omitted");

            Map<String, Object> sc = (Map<String, Object>) result.structuredContent();
            assertNotNull(sc, "structuredContent must be present");
            Map<String, Object> mcpSession = (Map<String, Object>) sc.get(MintBackHandler.MCP_SESSION_KEY);
            assertNotNull(mcpSession, "mcp_session must be injected");
            assertEquals(TestUtils.handleFrom(block, "session_id"), mcpSession.get("session_id"),
                    "mcp_session.session_id must match minted id from text block");
            assertFalse(mcpSession.containsKey("agent_id"), "mcp_session must not have agent_id");

            capture.flush();
            PublishEventRequest event = capture.findByTool("echo");
            assertNotNull(event);
            assertFalse(event.getTags().containsKey(HandleTags.AGENT_ID_TAG),
                    "event must not have agentcat_agent_id tag when omitted");
            assertFalse(event.getTags().containsKey(HandleTags.AGENT_ID_SOURCE_TAG),
                    "event must not have agentcat_agent_id_source tag when omitted");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void suppliedAgentIdIsMirroredInMintBackAndStructuredContent() throws Exception {
        // ported from: "echoes a self-chosen agent_id verbatim with source tag supplied"
        try (McpTestContext ctx = setupHighLevel(withAgentTracking())) {
            String sessionId = TestUtils.sid("fixed");

            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("msg", "hi",
                                   "context", "agent echo",
                                   "session_id", sessionId,
                                   "agent_id", "opus-4.80-1m|claude-code|k3n9x")));

            assertNull(TestUtils.mintBackOf(result), "supplied session must not produce mint-back");

            Map<String, Object> sc = (Map<String, Object>) result.structuredContent();
            assertNotNull(sc, "structuredContent must be present");
            Map<String, Object> mcpSession = (Map<String, Object>) sc.get(MintBackHandler.MCP_SESSION_KEY);
            assertNotNull(mcpSession, "mcp_session must be injected");
            assertEquals(sessionId, mcpSession.get("session_id"));
            assertEquals("opus-4.80-1m|claude-code|k3n9x", mcpSession.get("agent_id"));
            assertEquals("active", mcpSession.get("status"));

            capture.flush();
            PublishEventRequest event = capture.findByTool("echo");
            assertNotNull(event);
            assertEquals(sessionId, event.getSessionId());
            assertEquals("opus-4.80-1m|claude-code|k3n9x", event.getTags().get(HandleTags.AGENT_ID_TAG));
            assertEquals("supplied", event.getTags().get(HandleTags.AGENT_ID_SOURCE_TAG));
        }
    }

    @Test
    void agentIdTagsAreCorrectlySetOnEvent() throws Exception {
        // ported from: "subagent flow: inherited task + own generated agent_id, no mint-back"
        try (McpTestContext ctx = setupHighLevel(withAgentTracking())) {
            String sessionId = TestUtils.sid("parent");

            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("msg", "hi",
                                   "context", "subagent flow",
                                   "session_id", sessionId,
                                   "agent_id", "haiku-4.5|claude-code|q7w2e")));

            assertNull(TestUtils.mintBackOf(result), "supplied session must not produce mint-back");

            capture.flush();
            PublishEventRequest event = capture.findByTool("echo");
            assertNotNull(event);
            assertEquals(sessionId, event.getSessionId());
            assertEquals("haiku-4.5|claude-code|q7w2e", event.getTags().get(HandleTags.AGENT_ID_TAG));
            assertEquals("supplied", event.getTags().get(HandleTags.AGENT_ID_SOURCE_TAG));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "v2 agent tracking: OFF by default"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void injectsNoAgentIdParamTagsNothingAndMintsTaskOnly() throws Exception {
        // ported from: "injects no agent_id param, tags nothing, and mints task-only"
        try (McpTestContext ctx = setupHighLevel(new AgentCatOptions())) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();
            for (McpSchema.Tool tool : tools) {
                Object propsRaw = tool.inputSchema().get("properties");
                if (propsRaw instanceof Map<?, ?> props) {
                    assertFalse(props.containsKey("agent_id"),
                            "tool '" + tool.name() + "' must not have agent_id when agent tracking is off");
                }
            }

            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("msg", "hi", "context", "defaults")));

            String block = TestUtils.mintBackOf(result);
            assertNotNull(block, "mint-back must be present");
            assertTrue(block.contains("[session_id issued"), "must announce session_id issued");
            assertFalse(block.contains("agent_id"), "must not mention agent_id");

            capture.flush();
            PublishEventRequest event = capture.findByTool("echo");
            assertNotNull(event);
            assertFalse(event.getTags().containsKey(HandleTags.AGENT_ID_TAG),
                    "event must not have agentcat_agent_id tag");
            assertFalse(event.getTags().containsKey(HandleTags.AGENT_ID_SOURCE_TAG),
                    "event must not have agentcat_agent_id_source tag");
        }
    }
}
