package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.integration.TestUtils;
import com.agentcat.sdk.modules.handles.MintBackHandler;
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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/structured-mintback.test.ts
 *
 * Tests for structured mint-back (mcp_session in structuredContent).
 *
 * TypeScript has two describe blocks:
 *   "structured mint-back: high-level (V2) path" — uses McpServer (high-level V2 API)
 *   "structured mint-back: low-level (V1) path"  — uses Server (low-level V1 API)
 *
 * Java has no V1/V2 distinction at the server level — all tests are ported using
 * McpSyncServer (the Java equivalent of the V2 high-level path). The low-level V1
 * tests verify the same mint-back behaviour from a different server seam; here
 * they are implemented via McpSyncServer with matching assertions.
 */
class StructuredMintbackTest {

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

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Server with a structured {@code get_stats} tool (count = 0).
     * Equivalent to TypeScript {@code setupTestServerAndClient()}.
     */
    private McpTestContext setupGetStatsServer(AgentCatOptions opts) throws Exception {
        return setupStructuredServer("get_stats", 0, opts);
    }

    /**
     * Server with a structured {@code stats} tool (count = 42).
     * Equivalent to TypeScript {@code setupLowLevelStructured()}.
     */
    private McpTestContext setupStatsServer(AgentCatOptions opts) throws Exception {
        return setupStructuredServer("stats", 42, opts);
    }

    private McpTestContext setupStructuredServer(String toolName, int count,
                                                  AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        Map<String, Object> outputSchema = new LinkedHashMap<>();
        outputSchema.put("type", "object");
        outputSchema.put("properties", Map.of("count", Map.of("type", "number")));
        outputSchema.put("required", List.of("count"));
        outputSchema.put("additionalProperties", false);

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("structured-mintback", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder(toolName,
                                        Map.of("type", "object",
                                               "properties", Map.of()))
                                .description("Returns stats")
                                .outputSchema(outputSchema)
                                .build(),
                        (exchange, req) -> McpSchema.CallToolResult.builder()
                                .addTextContent(count + " things")
                                .structuredContent(new LinkedHashMap<>(Map.of("count", count)))
                                .build())
                .build();
        AgentCat.track(server, "proj_test", opts);
        McpTestContext ctx = McpTestContext.start(transport, server);
        ctx.client().listTools(); // prime schema registry
        return ctx;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // addMcpSessionToOutputSchema — unit tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void addsMcpSessionToOutputSchemaForStructuredTool() {
        // ported from: "tools/list declares mcp_session on get_stats; schema-less tools untouched"
        Map<String, Object> outputSchema = new LinkedHashMap<>();
        outputSchema.put("type", "object");
        Map<String, Object> outProps = new LinkedHashMap<>();
        outProps.put("count", Map.of("type", "number"));
        outputSchema.put("properties", outProps);
        outputSchema.put("required", List.of("count"));
        outputSchema.put("additionalProperties", false);

        McpSchema.Tool tool = McpSchema.Tool.builder("get_stats",
                Map.of("type", "object", "properties", Map.of()))
                .outputSchema(outputSchema)
                .build();

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        McpSchema.Tool result = ParameterInjector.addMcpSessionToOutputSchema(tool, opts);

        Map<String, Object> props = (Map<String, Object>) result.outputSchema().get("properties");
        assertNotNull(props.get(MintBackHandler.MCP_SESSION_KEY),
                "mcp_session must be declared on structured tool");
    }

    @Test
    void toolWithNoOutputSchemaIsUntouched() {
        // ported from: "content-only tools are unaffected"
        McpSchema.Tool tool = McpSchema.Tool.builder("add_todo",
                Map.of("type", "object", "properties", Map.of())).build();

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        McpSchema.Tool result = ParameterInjector.addMcpSessionToOutputSchema(tool, opts);

        assertSame(tool, result, "Tool without outputSchema must be returned unchanged");
        assertNull(result.outputSchema(), "outputSchema must remain null");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integration tests — high-level (V2) path
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "structuredContent-only consumer discovers minted handles"
     *
     * <p>A client reading only structuredContent can still discover the minted
     * session_id from {@code mcp_session}. mcp_session is the FIRST key so it
     * survives client-side truncation of long results.
     *
     * <p>Ported from: src/tests/structured-mintback.test.ts — V2 path
     */
    @Test
    @SuppressWarnings("unchecked")
    void structuredContentOnlyConsumerDiscoversMintedHandles() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);

        try (McpTestContext ctx = setupGetStatsServer(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("get_stats",
                            Map.of("context", "checking stats")));

            Map<String, Object> sc = (Map<String, Object>) result.structuredContent();
            assertNotNull(sc, "structuredContent must be present");

            Map<String, Object> mint = (Map<String, Object>) sc.get(MintBackHandler.MCP_SESSION_KEY);
            assertNotNull(mint, "mcp_session must be injected into structuredContent");

            String sessionId = (String) mint.get("session_id");
            assertNotNull(sessionId, "session_id must be present in mcp_session");
            assertTrue(sessionId.startsWith("ses_"), "session_id must start with ses_");

            assertFalse(mint.containsKey("agent_id"),
                    "mcp_session must not have agent_id when none was provided");
            assertEquals("issued", mint.get("status"),
                    "status must be 'issued' for first call without session_id");

            assertEquals(0, sc.get("count"), "customer count payload must be intact");

            // Mirror leads so it survives client-side truncation of long results
            assertEquals(MintBackHandler.MCP_SESSION_KEY, sc.keySet().iterator().next(),
                    "mcp_session must be the first key in structuredContent");
        }
    }

    /**
     * "steady state: supplied handles are re-echoed on every response; text block stays mint-only"
     *
     * <p>When the agent supplies both session_id and agent_id, the structured mirror
     * re-echoes them with status "active". No mint-back text block is added.
     *
     * <p>Ported from: src/tests/structured-mintback.test.ts — V2 path
     */
    @Test
    @SuppressWarnings("unchecked")
    void steadyStateSuppliedHandlesAreReechoedOnEveryResponse() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);

        String sessionId = TestUtils.sid("fixed");

        try (McpTestContext ctx = setupGetStatsServer(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("get_stats",
                            Map.of("context", "checking stats",
                                   "session_id", sessionId,
                                   "agent_id", "opus-4.80-1m|claude-code|k3n9x")));

            // The text block is a mint-time announcement only — none in steady state
            assertNull(TestUtils.mintBackOf(result),
                    "supplied session must not produce mint-back text block");

            Map<String, Object> sc = (Map<String, Object>) result.structuredContent();
            assertNotNull(sc, "structuredContent must be present");

            Map<String, Object> mint = (Map<String, Object>) sc.get(MintBackHandler.MCP_SESSION_KEY);
            assertNotNull(mint, "mcp_session must be injected into structuredContent");
            assertEquals(sessionId, mint.get("session_id"),
                    "mcp_session.session_id must match supplied session_id");
            assertEquals("opus-4.80-1m|claude-code|k3n9x", mint.get("agent_id"),
                    "mcp_session.agent_id must match supplied agent_id");
            assertEquals("active", mint.get("status"),
                    "status must be 'active' when supplied handles are re-echoed");
        }
    }

    /**
     * "wire-only: published event.response never contains mcp_session"
     *
     * <p>The SDK schedules the analytics event BEFORE applying mint-back, so the
     * published event response is always the clean customer payload — never the
     * wire-enriched version with mcp_session or [session_id text blocks.
     *
     * <p>Ported from: src/tests/structured-mintback.test.ts — V2 path
     */
    @Test
    @SuppressWarnings("unchecked")
    void wireOnlyPublishedEventResponseNeverContainsMcpSession() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);

        try (McpTestContext ctx = setupGetStatsServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("get_stats",
                    Map.of("context", "checking stats")));

            capture.flush();
            PublishEventRequest event = capture.findByTool("get_stats");
            assertNotNull(event, "event must be published");

            Object responseObj = event.getResponse();
            assertNotNull(responseObj, "event response must not be null");

            // The analytics response is snapshotted BEFORE mint-back injection
            String responseStr = responseObj.toString();
            assertFalse(responseStr.contains(MintBackHandler.MCP_SESSION_KEY),
                    "published event response must not contain mcp_session");
            assertFalse(responseStr.contains("[session_id"),
                    "published event response must not contain [session_id text block");

            // structuredContent in the event must be the customer's clean payload only
            Map<String, Object> responseMap = (Map<String, Object>) responseObj;
            Map<String, Object> sc = (Map<String, Object>) responseMap.get("structuredContent");
            assertNotNull(sc, "structuredContent must be present in event response");
            assertFalse(sc.containsKey(MintBackHandler.MCP_SESSION_KEY),
                    "event response structuredContent must not contain mcp_session");
            assertEquals(0, sc.get("count"),
                    "event response structuredContent must contain only customer payload {count: 0}");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integration tests — low-level (V1) path
    //
    // TypeScript uses Server + setRequestHandler(ListToolsRequestSchema, ...).
    // Java has no V1 low-level server; the same behaviour is verified via
    // McpSyncServer with matching tool definitions and assertions.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "mints into structuredContent on the V1 path too"
     *
     * <p>Verifies that mint-back is applied to a structured tool regardless of
     * the server flavor. Uses count=42 to match the TypeScript low-level fixture.
     *
     * <p>Ported from: src/tests/structured-mintback.test.ts — V1 path
     */
    @Test
    @SuppressWarnings("unchecked")
    void mintsIntoStructuredContentOnV1Path() throws Exception {
        // TypeScript low-level server uses enableAgentTracking: true implicitly
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);

        try (McpTestContext ctx = setupStatsServer(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("stats", Map.of()));

            Map<String, Object> sc = (Map<String, Object>) result.structuredContent();
            assertNotNull(sc, "structuredContent must be present");

            Map<String, Object> mint = (Map<String, Object>) sc.get(MintBackHandler.MCP_SESSION_KEY);
            assertNotNull(mint, "mcp_session must be injected into structuredContent");

            String sessionId = (String) mint.get("session_id");
            assertNotNull(sessionId, "session_id must be present");
            assertTrue(sessionId.startsWith("ses_"), "session_id must start with ses_");

            assertFalse(mint.containsKey("agent_id"),
                    "mcp_session must not have agent_id when none was provided");
            assertEquals("issued", mint.get("status"), "status must be 'issued'");

            assertEquals(42, sc.get("count"), "customer count payload (42) must be intact");
            assertEquals(MintBackHandler.MCP_SESSION_KEY, sc.keySet().iterator().next(),
                    "mcp_session must be the first key");

            // Event response must not contain mcp_session
            capture.flush();
            PublishEventRequest event = capture.findByTool("stats");
            assertNotNull(event, "event must be published");
            assertFalse(event.getResponse().toString().contains(MintBackHandler.MCP_SESSION_KEY),
                    "published event response must not contain mcp_session");
        }
    }

    /**
     * "hook mode: mirrored payload carries agent_id but never session_id or status"
     *
     * <p>When {@code resolveSessionId} is configured, the structured mint-back
     * omits session_id and status — only agent_id is echoed when provided.
     *
     * <p>Ported from: src/tests/structured-mintback.test.ts — V1 path
     */
    @Test
    @SuppressWarnings("unchecked")
    void hookModeMirroredPayloadCarriesAgentIdButNeverSessionIdOrStatus() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        opts.setResolveSessionId((req, extra) -> CompletableFuture.completedFuture("corr-1"));

        try (McpTestContext ctx = setupStatsServer(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("stats",
                            Map.of("agent_id", "opus-4.80-1m|claude-code|k3n9x")));

            Map<String, Object> sc = (Map<String, Object>) result.structuredContent();
            assertNotNull(sc, "structuredContent must be present");

            Map<String, Object> mint = (Map<String, Object>) sc.get(MintBackHandler.MCP_SESSION_KEY);
            assertNotNull(mint, "mcp_session must be injected into structuredContent in hook mode");

            assertFalse(mint.containsKey("session_id"),
                    "mcp_session must NOT have session_id in hook mode");
            assertFalse(mint.containsKey("status"),
                    "mcp_session must NOT have status in hook mode");
            assertEquals("opus-4.80-1m|claude-code|k3n9x", mint.get("agent_id"),
                    "mcp_session must carry agent_id when provided");
        }
    }
}
