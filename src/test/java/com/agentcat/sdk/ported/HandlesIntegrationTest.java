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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/handles-integration.test.ts
 *
 * Integration tests for session handle injection, stripping, and mint-back
 * on a real low-level MCP server over HTTP (Jetty + McpTestContext).
 */
class HandlesIntegrationTest {

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

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Builds an echo server with AgentCat.track() applied, wrapped in McpTestContext.
     * enableAgentTracking defaults to true to match the TS setupLowLevel() helper.
     */
    private record Setup(McpTestContext ctx, List<Map<String, Object>> receivedArgs) {}

    private Setup setupEcho(AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        List<Map<String, Object>> receivedArgs = new CopyOnWriteArrayList<>();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("low-level test server", "1.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("echoes text")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("text", Map.of("type", "string")))
                                        .required(List.of("text"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            receivedArgs.add(req.arguments());
                            Object text = req.arguments().get("text");
                            if ("explode".equals(text)) {
                                return McpSchema.CallToolResult.builder()
                                        .isError(true)
                                        .addTextContent("boom")
                                        .build();
                            }
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("echo: " + text)
                                    .build();
                        })
                .build();

        AgentCat.track(server, "proj_test", opts);
        return new Setup(McpTestContext.start(transport, server), receivedArgs);
    }

    private AgentCatOptions withAgentTracking() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        return opts;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void injectsSessionIdAndAgentIdIntoToolListing() throws Exception {
        try (McpTestContext ctx = setupEcho(withAgentTracking()).ctx()) {
            McpSchema.ListToolsResult result = ctx.client().listTools();
            McpSchema.Tool echo = result.tools().stream()
                    .filter(t -> "echo".equals(t.name()))
                    .findFirst().orElseThrow();

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) echo.inputSchema().get("properties");

            // property key order must match injection order
            assertEquals(List.of("text", "session_id", "agent_id", "context"),
                    new ArrayList<>(props.keySet()));

            // session_id carries the pattern constraint
            @SuppressWarnings("unchecked")
            Map<String, Object> sessionIdProp = (Map<String, Object>) props.get("session_id");
            assertEquals(ParameterInjector.SESSION_ID_PATTERN, sessionIdProp.get("pattern"));

            // required array must match
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) echo.inputSchema().get("required");
            assertEquals(List.of("text", "session_id", "agent_id", "context"), required);

            // get_more_tools is present and has session_id
            McpSchema.Tool gmt = result.tools().stream()
                    .filter(t -> "get_more_tools".equals(t.name()))
                    .findFirst().orElseThrow();
            @SuppressWarnings("unchecked")
            Map<String, Object> gmtProps = (Map<String, Object>) gmt.inputSchema().get("properties");
            assertTrue(gmtProps.containsKey("session_id"));
            assertTrue(gmtProps.containsKey("agent_id"));

            // get_more_tools annotations
            var annots = gmt.annotations();
            assertNotNull(annots);
            assertEquals("Get More Tools", annots.title());
            assertTrue(Boolean.TRUE.equals(annots.readOnlyHint()));
            assertTrue(Boolean.FALSE.equals(annots.destructiveHint()));
            assertTrue(Boolean.TRUE.equals(annots.idempotentHint()));
            assertTrue(Boolean.FALSE.equals(annots.openWorldHint()));
        }
    }

    @Test
    void mintBackIssuedOnFirstCallWithStartSentinel() throws Exception {
        try (McpTestContext ctx = setupEcho(withAgentTracking()).ctx()) {
            ctx.client().listTools();

            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("text", "hi", "context", "testing intent", "session_id", "start")));

            String mintBack = TestUtils.mintBackOf(result);
            assertNotNull(mintBack, "mint-back block must be prepended on first call");
            assertTrue(mintBack.contains("[session_id issued"), "must contain issued header");
            assertFalse(mintBack.contains("agent_id"), "no agent_id line when not supplied");

            String sessionId = TestUtils.handleFrom(mintBack, "session_id");
            assertTrue(sessionId.startsWith("ses_"), "minted id must start with ses_");

            capture.flush();
            PublishEventRequest event = capture.findByTool("echo");
            assertNotNull(event);
            assertEquals(sessionId, event.getSessionId());
            assertEquals("minted", event.getTags().get("agentcat_session_id_source"));
            assertFalse(event.getTags().containsKey("agentcat_agent_id"),        "no agent_id tag when not supplied");
            assertFalse(event.getTags().containsKey("agentcat_agent_id_source"), "no agent_id_source tag when not supplied");
        }
    }

    @Test
    void stripsHandleParamsBeforeCallingToolHandler() throws Exception {
        Setup setup = setupEcho(withAgentTracking());
        try (McpTestContext ctx = setup.ctx()) {
            ctx.client().listTools();
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "hi",
                           "context", "why",
                           "session_id", TestUtils.sid("x"),
                           "agent_id", "agt_x")));

            assertFalse(setup.receivedArgs().isEmpty(), "handler must have been called");
            Map<String, Object> args = setup.receivedArgs().get(0);
            assertEquals(Map.of("text", "hi"), args, "handler must only receive user params");

            // event still carries the original session_id
            capture.flush();
            PublishEventRequest event = capture.findByTool("echo");
            assertNotNull(event);
            // parameters mirrors TS SDK: { request: { params: { arguments: rawArgs } }, extra }
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) event.getParameters();
            @SuppressWarnings("unchecked")
            Map<String, Object> request    = (Map<String, Object>) parameters.get("request");
            @SuppressWarnings("unchecked")
            Map<String, Object> reqParams  = (Map<String, Object>) request.get("params");
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments  = (Map<String, Object>) reqParams.get("arguments");
            assertEquals(TestUtils.sid("x"), arguments.get("session_id"));
        }
    }

    @Test
    void mintBackUnrecognizedWhenUnknownSessionIdSupplied() throws Exception {
        // ported from: handles-integration — unrecognized session_id
        try (McpTestContext ctx = setupEcho(withAgentTracking()).ctx()) {
            ctx.client().listTools();

            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("text", "hi", "context", "test", "session_id", "totally-wrong-id")));

            String mintBack = TestUtils.mintBackOf(result);
            assertNotNull(mintBack, "unrecognized session_id must get a mint-back block");
            assertTrue(mintBack.contains("[session_id unrecognized"), "block must contain unrecognized header");

            capture.flush();
            PublishEventRequest event = capture.findByTool("echo");
            assertNotNull(event);
            assertEquals("invalid", event.getTags().get("agentcat_session_id_source"));
        }
    }

    @Test
    void activeStatusWhenRecognizedSessionIdSupplied() throws Exception {
        // ported from: handles-integration — echoed handles are reused verbatim with no mint-back
        try (McpTestContext ctx = setupEcho(withAgentTracking()).ctx()) {
            ctx.client().listTools();

            String sessionId = TestUtils.sid("fixed");
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("text", "hi", "context", "test",
                                   "session_id", sessionId,
                                   "agent_id", "opus-4.80-1m|claude-code|k3n9x")));

            assertNull(TestUtils.mintBackOf(result), "supplied session must not produce mint-back");

            capture.flush();
            PublishEventRequest event = capture.findByTool("echo");
            assertNotNull(event);
            assertEquals(sessionId, event.getSessionId());
            assertEquals("supplied", event.getTags().get("agentcat_session_id_source"));
            assertEquals("opus-4.80-1m|claude-code|k3n9x", event.getTags().get("agentcat_agent_id"));
            assertEquals("supplied", event.getTags().get("agentcat_agent_id_source"));
        }
    }

    @Test
    void errorsFromToolHandlerArePublishedAsIsErrorEvents() throws Exception {
        // ported from: handles-integration — prepends the mint-back on isError results too
        try (McpTestContext ctx = setupEcho(withAgentTracking()).ctx()) {
            ctx.client().listTools();

            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("text", "explode", "context", "test")));

            assertTrue(Boolean.TRUE.equals(result.isError()), "result must be isError");
            String mintBack = TestUtils.mintBackOf(result);
            assertNotNull(mintBack, "mint-back must still be prepended on error");
            assertTrue(mintBack.contains("[session_id issued"), "mint-back must contain issued header");
            assertFalse(mintBack.contains("agent_id"), "no agent_id line when not supplied");

            capture.flush();
            PublishEventRequest event = capture.findByTool("echo");
            assertNotNull(event);
            assertTrue(Boolean.TRUE.equals(event.getIsError()), "event must have isError=true");
            assertEquals("minted", event.getTags().get("agentcat_session_id_source"));
        }
    }

    @Test
    void sessionIdAndAgentIdCarriedOnEveryEvent() throws Exception {
        // ported from: handles-integration — subagent flow: supplied session_id, no agent_id
        try (McpTestContext ctx = setupEcho(withAgentTracking()).ctx()) {
            ctx.client().listTools();

            String sessionId = TestUtils.sid("parent");
            McpSchema.CallToolResult result = ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "hi", "context", "test", "session_id", sessionId)));
            assertNull(TestUtils.mintBackOf(result), "supplied session must not produce mint-back");

            capture.flush();
            PublishEventRequest event = capture.findByTool("echo");
            assertNotNull(event);
            assertEquals(sessionId, event.getSessionId());
            assertEquals("supplied", event.getTags().get("agentcat_session_id_source"));
            // no agent_id supplied → no agent tags
            assertNull(event.getTags().get("agentcat_agent_id"));
            assertNull(event.getTags().get("agentcat_agent_id_source"));
        }
    }

    @Test
    void concurrentCallsWithDifferentSessionsDontClobber() throws Exception {
        // Two independent "start" calls must mint two different session IDs
        try (McpTestContext ctx = setupEcho(withAgentTracking()).ctx()) {
            ctx.client().listTools();

            McpSchema.CallToolResult r1 = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("text", "first", "context", "test", "session_id", "start")));
            McpSchema.CallToolResult r2 = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("text", "second", "context", "test", "session_id", "start")));

            String id1 = TestUtils.handleFrom(TestUtils.mintBackOf(r1), "session_id");
            String id2 = TestUtils.handleFrom(TestUtils.mintBackOf(r2), "session_id");

            assertTrue(id1.startsWith("ses_"));
            assertTrue(id2.startsWith("ses_"));
            assertNotEquals(id1, id2, "two independent start calls must produce different session IDs");
        }
    }
}
