package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.modules.handles.HandleTags;
import com.agentcat.sdk.modules.handles.SessionIdProvider;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/v2/session-modes.test.ts
 *
 * Tests for the {@code resolveSessionId} hook mode and derived session IDs on v2
 * McpServer. Integration tests use McpTestContext + EventCapture.
 */
class SessionModesTest {

    private static final String AGENTCAT_TAG_SESSION_SOURCE = HandleTags.SESSION_ID_SOURCE_TAG;

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
     * Builds an echo server with the given options, applies AgentCat tracking,
     * starts Jetty, and returns the running test context.
     */
    private McpTestContext setupEchoServer(AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("v2-session-modes", "1.0.0")
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
    // Unit tests — deriveSessionId determinism
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "derives a deterministic ses_ id: same hook value + project → same id across calls and instances"
     */
    @Test
    void deriveSessionIdIsDeterministic() {
        String id1 = SessionIdProvider.deriveSessionId("customer-correlation-7", "proj_test");
        String id2 = SessionIdProvider.deriveSessionId("customer-correlation-7", "proj_test");
        assertEquals(id1, id2, "Same inputs must always produce the same session ID");
        assertTrue(id1.startsWith("ses_"), "Derived session ID must start with ses_");
    }

    @Test
    void deriveSessionIdDifferentHookValueProducesDifferentId() {
        String id1 = SessionIdProvider.deriveSessionId("hook-value-a", "proj_test");
        String id2 = SessionIdProvider.deriveSessionId("hook-value-b", "proj_test");
        assertNotEquals(id1, id2, "Different hook values must produce different session IDs");
    }

    @Test
    void deriveSessionIdDifferentProjectProducesDifferentId() {
        String id1 = SessionIdProvider.deriveSessionId("same-value", "proj_A");
        String id2 = SessionIdProvider.deriveSessionId("same-value", "proj_B");
        assertNotEquals(id1, id2, "Different project IDs must produce different session IDs");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // v2 resolveSessionId hook integration tests
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "advertises no session_id param; agent_id stays available"
     *
     * <p>Ported from: src/tests/v2/session-modes.test.ts
     * describe "v2 session modes: resolveSessionId hook"
     */
    @Test
    @SuppressWarnings("unchecked")
    void advertisesNoSessionIdParamAgentIdStaysAvailableInHookMode() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setResolveSessionId((req, extra) -> CompletableFuture.completedFuture("customer-correlation-7"));
        opts.setEnableAgentTracking(true);

        try (McpTestContext ctx = setupEchoServer(opts)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();

            McpSchema.Tool echo = tools.stream()
                    .filter(t -> "echo".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("echo tool not found"));

            Map<String, Object> echoProps = (Map<String, Object>) echo.inputSchema().get("properties");
            assertNull(echoProps.get("session_id"),
                    "session_id must NOT be advertised in hook mode");
            assertNotNull(echoProps.get("agent_id"),
                    "agent_id must still be available in hook mode");

            List<String> required = (List<String>) echo.inputSchema().get("required");
            assertNotNull(required);
            assertTrue(required.contains("agent_id"),
                    "agent_id must be in required");
            assertFalse(required.contains("session_id"),
                    "session_id must NOT be in required in hook mode");

            // get_more_tools follows the same policy
            McpSchema.Tool gmt = tools.stream()
                    .filter(t -> "get_more_tools".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("get_more_tools not found"));

            Map<String, Object> gmtProps = (Map<String, Object>) gmt.inputSchema().get("properties");
            assertNull(gmtProps.get("session_id"),
                    "get_more_tools must NOT have session_id in hook mode");
            assertNotNull(gmtProps.get("agent_id"),
                    "get_more_tools must have agent_id in hook mode");
        }
    }

    /**
     * "derives a new session_id each call when hook returns null"
     * (TS: "mints silently on hook null: fresh ses_ per call, minted source, no mint-back")
     *
     * <p>Ported from: src/tests/v2/session-modes.test.ts
     */
    @Test
    void derivesNewSessionIdEachCallWhenHookReturnsNull() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setResolveSessionId((req, extra) -> CompletableFuture.completedFuture(null));

        try (McpTestContext ctx = setupEchoServer(opts)) {
            ctx.client().listTools();

            McpSchema.CallToolResult first = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("msg", "one", "context", "hook null")));
            McpSchema.CallToolResult second = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("msg", "two", "context", "hook null")));

            // No mint-back in hook mode — the agent can never learn a silently minted id
            assertNull(findMintBack(first), "no mint-back must appear in hook mode");
            assertNull(findMintBack(second), "no mint-back must appear in hook mode");

            capture.flush(2);
            List<PublishEventRequest> events = capture.getEvents();
            assertEquals(2, events.size());

            for (PublishEventRequest event : events) {
                assertTrue(event.getSessionId() != null && event.getSessionId().startsWith("ses_"),
                        "each event must have a minted ses_ id, got: " + event.getSessionId());
                assertEquals("minted", event.getTags().get(AGENTCAT_TAG_SESSION_SOURCE),
                        "session source must be 'minted' for null-returning hook");
            }

            // One single-event task per null return — never a shared session
            assertNotEquals(events.get(0).getSessionId(), events.get(1).getSessionId(),
                    "null hook must produce a fresh session per call, never a shared session");
        }
    }

    /**
     * "throwing hook falls back to a minted session_id"
     *
     * <p>When the resolveSessionId hook fails (future rejects), the SDK must not
     * crash the tool call. An event is still published with a minted session ID.
     */
    @Test
    void throwingHookFallsBackToMintedSessionId() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setResolveSessionId((req, extra) ->
                CompletableFuture.failedFuture(new RuntimeException("hook failure")));

        try (McpTestContext ctx = setupEchoServer(opts)) {
            ctx.client().listTools();

            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("msg", "hi", "context", "throwing hook")));

            assertNotNull(result, "tool call must succeed even when resolveSessionId hook throws");

            capture.flush();
            List<PublishEventRequest> events = capture.getEvents();
            assertFalse(events.isEmpty(), "an event must be published even when hook throws");

            PublishEventRequest event = events.get(0);
            assertNotNull(event.getSessionId(), "session ID must be populated even on hook failure");
            assertTrue(event.getSessionId().startsWith("ses_"),
                    "fallback session must be a valid ses_ id, got: " + event.getSessionId());
        }
    }

    /**
     * "session source tag = 'hook'"
     *
     * <p>When the resolveSessionId hook returns a non-null value, each event is tagged
     * with {@code agentcat_session_id_source: "hook"}.
     *
     * <p>Ported from: src/tests/v2/session-modes.test.ts
     */
    @Test
    void hookModeSessionsAreTaggedWithHookSource() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setResolveSessionId((req, extra) ->
                CompletableFuture.completedFuture("customer-correlation-7"));

        try (McpTestContext ctx = setupEchoServer(opts)) {
            ctx.client().listTools();

            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("msg", "hi", "context", "hook source tag")));

            capture.flush();
            List<PublishEventRequest> events = capture.getEvents();
            assertFalse(events.isEmpty());

            PublishEventRequest event = events.get(0);
            assertNotNull(event.getSessionId());
            assertTrue(event.getSessionId().startsWith("ses_"),
                    "hook-derived session must be a ses_ id");
            assertEquals("hook", event.getTags().get(AGENTCAT_TAG_SESSION_SOURCE),
                    "hook mode must produce 'hook' session source tag");
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    /** Returns the mint-back text block from the first content element, or null. */
    private static String findMintBack(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) return null;
        Object first = result.content().get(0);
        if (first instanceof McpSchema.TextContent tc) {
            String text = tc.text();
            if (text != null && text.startsWith("[session_id")) return text;
        }
        return null;
    }
}
