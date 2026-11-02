package com.agentcat.sdk.ported;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.model.HandleResolution;
import com.agentcat.sdk.model.SessionSource;
import com.agentcat.sdk.modules.handles.HandleTags;
import com.agentcat.sdk.modules.handles.MintBackHandler;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import com.agentcat.sdk.modules.handles.SessionIdProvider;
import com.agentcat.sdk.modules.handles.SessionManager;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/handles.test.ts
 *
 * <h3>API mapping (TypeScript → Java)</h3>
 * <ul>
 *   <li>{@code newSessionId()} → {@link SessionIdProvider#newSessionId()}</li>
 *   <li>{@code deriveSessionId(id, proj)} → {@link SessionIdProvider#deriveSessionId(String, String)}</li>
 *   <li>{@code isValidSessionId(s)} → {@link SessionIdProvider#isValid(String)}</li>
 *   <li>{@code resolveHandles(opts, projectId, req)} → {@link SessionManager#resolveHandles(Map, String, AgentCatOptions)}</li>
 *   <li>{@code buildHandleTags(res, version)} → {@link HandleTags#buildHandleTags(HandleResolution)}</li>
 *   <li>{@code buildMintBackText(res)} → {@link MintBackHandler} (internal)</li>
 *   <li>{@code buildStructuredMintBack(res)} → {@link MintBackHandler} (internal)</li>
 * </ul>
 */
class HandlesTest {

    // ── Test helpers ──────────────────────────────────────────────────────────

    /**
     * Mirrors the TypeScript test-utils {@code sid()} helper:
     * returns a deterministic ses_ ID for the given label.
     */
    private static String sid(String label) {
        return SessionIdProvider.deriveSessionId(label, "test");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // handle primitives
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mintsSessionIdsWithSesPrefixAndUniqueValues() {
        // ported from: "mints session ids with the ses_ prefix"
        String id = SessionIdProvider.newSessionId();
        assertTrue(id.matches("^ses_[0-9A-Za-z]{27}$"), "Must match ses_[0-9A-Za-z]{27}");
        assertNotEquals(id, SessionIdProvider.newSessionId(), "Must be unique per call");
    }

    @Test
    void derivesDeterministicSessionIds() {
        // ported from: "derives deterministic session ids from id+project"
        String a = SessionIdProvider.deriveSessionId("customer-abc", "proj_1");
        String b = SessionIdProvider.deriveSessionId("customer-abc", "proj_1");
        assertEquals(a, b);
        assertTrue(a.startsWith("ses_"));
    }

    @Test
    void differentProjectYieldsDifferentDerivedId() {
        // ported from: "different project yields a different derived id"
        assertNotEquals(
                SessionIdProvider.deriveSessionId("customer-abc", "proj_1"),
                SessionIdProvider.deriveSessionId("customer-abc", "proj_2")
        );
    }

    @Test
    void pinsGoldenDerivedValues() {
        // ported from: "pins derived session ids to their golden values"
        // Golden values from the TypeScript implementation.
        assertEquals("ses_2cOHEO0LYGADMzRvWTXXVbbgxgm",
                SessionIdProvider.deriveSessionId("customer-abc", "proj_1"));
    }

    @Test
    void doesNotTrimInputForDerive() {
        // ported from: "does not trim its input"
        assertNotEquals(
                SessionIdProvider.deriveSessionId(" x ", "p"),
                SessionIdProvider.deriveSessionId("x", "p")
        );
        assertEquals("ses_2c3yR5mYKQdLaXsJNgZH6erbfQK",
                SessionIdProvider.deriveSessionId(" x ", "p"));
        assertEquals("ses_2bw285VY9apdgUgTPXKFnT6P4G0",
                SessionIdProvider.deriveSessionId("x", "p"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isValidSessionId
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void isValidAcceptsMintsAndDerived() {
        // ported from: "accepts an ID this SDK actually mints"
        assertTrue(SessionIdProvider.isValid(SessionIdProvider.newSessionId()));
        assertTrue(SessionIdProvider.isValid(SessionIdProvider.deriveSessionId("anything", "proj_1")));
    }

    @Test
    void isValidAcceptsTestHelperIds() {
        // ported from: "accepts the test helper's IDs"
        assertTrue(SessionIdProvider.isValid(sid("parent")));
    }

    @Test
    void isValidRejectsWrongPrefix() {
        assertFalse(SessionIdProvider.isValid("task_2xF9kQm3rTvB8nLpYw7ZcHd4Ke"));
    }

    @Test
    void isValidRejectsNoPrefix() {
        assertFalse(SessionIdProvider.isValid("2xF9kQm3rTvB8nLpYw7ZcHd4Ke1"));
    }

    @Test
    void isValidRejectsTooShort() {
        assertFalse(SessionIdProvider.isValid("ses_abc"));
    }

    @Test
    void isValidRejectsTooLong() {
        assertFalse(SessionIdProvider.isValid("ses_" + "a".repeat(28)));
    }

    @Test
    void isValidRejectsEmpty() {
        assertFalse(SessionIdProvider.isValid(""));
    }

    @Test
    void isValidRejectsPrefixOnly() {
        assertFalse(SessionIdProvider.isValid("ses_"));
    }

    @Test
    void isValidRejectsCustomerValue() {
        assertFalse(SessionIdProvider.isValid("my-app-session-42"));
    }

    @Test
    void isValidRejectsNonBase62Body() {
        assertFalse(SessionIdProvider.isValid("ses_" + "-".repeat(27)));
    }

    @Test
    void isValidRejectsInnerWhitespace() {
        assertFalse(SessionIdProvider.isValid("ses_ " + "a".repeat(26)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SessionManager.resolveHandles — prompted mode
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mintsWhenNothingSupplied() {
        // ported from: "mints the task but never the agent when nothing supplied"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        HandleResolution res = SessionManager.resolveHandles(Map.of(), "echo", opts);
        assertEquals(SessionSource.MINTED, res.getSessionSource());
        assertTrue(res.getSessionId().startsWith("ses_"));
        assertNull(res.getAgentId());
    }

    @Test
    void usesSuppliedHandlesVerbatim() {
        // ported from: "uses supplied handles verbatim"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(ParameterInjector.SESSION_ID_PARAM, sid("anythinggoes"));
        args.put(ParameterInjector.AGENT_ID_PARAM, " opus-4.80-1m|claude-code|k3n9x ");
        HandleResolution res = SessionManager.resolveHandles(args, "echo", opts);
        assertEquals(SessionSource.SUPPLIED, res.getSessionSource());
        assertEquals(sid("anythinggoes"), res.getSessionId());
        // TS trims agent_id whitespace before storing — Java must match
        assertEquals("opus-4.80-1m|claude-code|k3n9x", res.getAgentId(),
                "agent_id must be trimmed of surrounding whitespace");
    }

    @Test
    void agentTrackingFalseResolvesNoAgentId() {
        // ported from: "enableAgentTracking:false resolves no agent id at all"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(false);
        Map<String, Object> args = Map.of(ParameterInjector.AGENT_ID_PARAM, "agt_ignored");
        HandleResolution res = SessionManager.resolveHandles(args, "echo", opts);
        assertNull(res.getAgentId());
    }

    @Test
    void agentTrackingIsOffByDefault() {
        // ported from: "agent tracking is OFF by default"
        AgentCatOptions opts = new AgentCatOptions();
        Map<String, Object> args = Map.of(ParameterInjector.AGENT_ID_PARAM, "agt_ignored");
        HandleResolution res = SessionManager.resolveHandles(args, "echo", opts);
        assertEquals(SessionSource.MINTED, res.getSessionSource());
        assertNull(res.getAgentId());
    }

    @Test
    void rejectsMalformedSessionId() {
        // ported from: "rejects a malformed supplied session_id: sessionless, source invalid"
        AgentCatOptions opts = new AgentCatOptions();
        Map<String, Object> args = Map.of(ParameterInjector.SESSION_ID_PARAM, "nope");
        HandleResolution res = SessionManager.resolveHandles(args, "echo", opts);
        assertEquals(SessionSource.INVALID, res.getSessionSource());
        assertEquals("", res.getSessionId(),
                "invalid session_id must yield empty sessionId (sessionless), not a surrogate");
    }

    @Test
    void acceptsWellFormedSuppliedSessionId() {
        // ported from: "accepts a well-formed supplied session_id verbatim"
        AgentCatOptions opts = new AgentCatOptions();
        Map<String, Object> args = Map.of(ParameterInjector.SESSION_ID_PARAM, sid("parent"));
        HandleResolution res = SessionManager.resolveHandles(args, "echo", opts);
        assertEquals(SessionSource.SUPPLIED, res.getSessionSource());
        assertEquals(sid("parent"), res.getSessionId());
    }

    @Test
    void startSentinelMintsLikeMissingValue() {
        // ported from: "start sentinel resolves exactly like omission: fresh mint, source minted"
        AgentCatOptions opts = new AgentCatOptions();
        Map<String, Object> args = Map.of(ParameterInjector.SESSION_ID_PARAM, "start");
        HandleResolution res = SessionManager.resolveHandles(args, "echo", opts);
        assertEquals(SessionSource.MINTED, res.getSessionSource());
        assertTrue(res.getSessionId().startsWith("ses_"));
    }

    @Test
    void startSentinelIsCaseInsensitive() {
        // ported from: "start sentinel is case-insensitive and whitespace-tolerant"
        AgentCatOptions opts = new AgentCatOptions();
        for (String value : List.of("START", "Start", "sTaRt")) {
            Map<String, Object> args = Map.of(ParameterInjector.SESSION_ID_PARAM, value);
            HandleResolution res = SessionManager.resolveHandles(args, "echo", opts);
            assertEquals(SessionSource.MINTED, res.getSessionSource(),
                    "Expected MINTED for sentinel: " + value);
        }
    }

    @Test
    void nearSentinelValuesStayInvalid() {
        // ported from: "near-sentinel values stay on the invalid path"
        AgentCatOptions opts = new AgentCatOptions();
        for (String value : List.of("restart", "start now", "starts", "star t")) {
            Map<String, Object> args = Map.of(ParameterInjector.SESSION_ID_PARAM, value);
            HandleResolution res = SessionManager.resolveHandles(args, "echo", opts);
            assertEquals(SessionSource.INVALID, res.getSessionSource(),
                    "Expected INVALID for near-sentinel: " + value);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SessionManager — hook mode
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void hookModeReturnsHookSourceAndEmptySessionId() {
        // ported from: "returns a provisional resolution: empty id, source 'hook', hookMode on"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setResolveSessionId((req, extra) -> CompletableFuture.completedFuture("customer-42"));
        HandleResolution res = SessionManager.resolveHandles(Map.of(), "echo", opts);
        assertEquals(SessionSource.HOOK_MODE, res.getSessionSource());
        assertEquals("", res.getSessionId());
    }

    @Test
    void hookModeIgnoresAgentSuppliedSessionId() {
        // ported from: "ignores agent-supplied session_id — the hook wins"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setResolveSessionId((req, extra) -> CompletableFuture.completedFuture("customer-42"));
        Map<String, Object> args = Map.of(ParameterInjector.SESSION_ID_PARAM, "ses_agent_sent");
        HandleResolution res = SessionManager.resolveHandles(args, "echo", opts);
        assertEquals(SessionSource.HOOK_MODE, res.getSessionSource());
        assertNotEquals("ses_agent_sent", res.getSessionId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HandleTags.buildHandleTags
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildHandleTagsStampsSourcesAndProtocolVersion() {
        // ported from: "stamps sources, agent id, and protocol version"
        HandleResolution res = new HandleResolution(
                "ses_test",
                SessionSource.SUPPLIED,
                "opus-4.80-1m|claude-code|k3n9x"
        );
        Map<String, String> tags = HandleTags.buildHandleTags(res);
        assertEquals("supplied", tags.get(HandleTags.SESSION_ID_SOURCE_TAG));
        assertEquals("opus-4.80-1m|claude-code|k3n9x", tags.get(HandleTags.AGENT_ID_TAG));
        assertNotNull(tags.get(HandleTags.PROTOCOL_VERSION_TAG));
    }

    @Test
    void buildHandleTagsOmitsAgentKeysWhenAbsent() {
        // ported from: "omits agent and protocol keys when absent"
        // In Java, agent_id is null when not supplied; tags still contain protocol version.
        HandleResolution res = new HandleResolution("ses_test", SessionSource.HOOK_MODE, null);
        Map<String, String> tags = HandleTags.buildHandleTags(res);
        assertEquals("hook", tags.get(HandleTags.SESSION_ID_SOURCE_TAG));
        assertFalse(tags.containsKey(HandleTags.AGENT_ID_TAG));
    }

    @Test
    void buildHandleTagsPassesNormalAgentIdThrough() {
        // ported from: "passes a normal agent_id through unchanged"
        String agentId = "opus-4.80-1m|claude-code|k3n9x";
        HandleResolution res = new HandleResolution("ses_test", SessionSource.SUPPLIED, agentId);
        assertEquals(agentId, HandleTags.buildHandleTags(res).get(HandleTags.AGENT_ID_TAG));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MintBackHandler — text and structured
    // ─────────────────────────────────────────────────────────────────────────

    /** Shared test constants matching TypeScript golden values. */
    private static final String T = "ses_2xF9kQm3rTvB8nLpYw7ZcHd4Ke1";
    private static final String A = "opus-4.80-1m|claude-code|k3n9x";

    @Test
    void buildMintBackTextTaskMinted() {
        // ported from: "task minted: task-only block, no agent mention even when agent supplied"
        HandleResolution res = new HandleResolution(T, SessionSource.MINTED, A);

        String text = MintBackHandler.buildMintBackText(res);

        String expected =
                "[session_id issued \u2014 see this tool's session_id parameter description]\n"
                + "session_id: " + T + "\n"
                + "This is the first-call issuance described in this tool's session_id parameter description.";

        assertNotNull(text, "MINTED must produce a text block");
        assertEquals(expected, text);
        assertFalse(text.contains("agent_id"),
                "text block must not mention agent_id even when agent was supplied");
    }

    @Test
    void buildMintBackTextTaskMintedWithoutAgentTracking() {
        // ported from: "task minted without agent tracking: same task-only block"
        HandleResolution res = new HandleResolution(T, SessionSource.MINTED, null);
        String text = MintBackHandler.buildMintBackText(res);
        String expected =
                "[session_id issued \u2014 see this tool's session_id parameter description]\n"
                + "session_id: " + T + "\n"
                + "This is the first-call issuance described in this tool's session_id parameter description.";
        assertNotNull(text, "MINTED without agent tracking must still produce a text block");
        assertEquals(expected, text);
    }

    @Test
    void buildMintBackTextTaskSuppliedReturnsNull() {
        // ported from: "task supplied -> null (nothing to announce, agent never mints)"
        HandleResolution res = new HandleResolution(T, SessionSource.SUPPLIED, A);
        assertNull(MintBackHandler.buildMintBackText(res),
                "SUPPLIED session must produce no mint-back text — nothing new to announce");
    }

    @Test
    void buildMintBackTextHookModeReturnsNull() {
        // ported from: "hook mode never emits task instructions, even when hook-null minted"
        HandleResolution res = new HandleResolution(T, SessionSource.HOOK_MODE, A);
        assertNull(MintBackHandler.buildMintBackText(res),
                "HOOK_MODE must never emit mint-back text — the customer owns session management");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildStructuredMintBackTaskMintedAgentSupplied() {
        // ported from: "task minted + agent supplied: both ids, status issued"
        HandleResolution res = new HandleResolution(T, SessionSource.MINTED, A);

        Map<String, Object> mint = MintBackHandler.buildStructuredMintBack(res);

        assertNotNull(mint);
        assertEquals(T, mint.get("session_id"));
        assertEquals(A, mint.get("agent_id"));
        assertEquals("issued", mint.get("status"));
        // Key order must be [session_id, agent_id, status] — mirrors TypeScript
        assertEquals(List.of("session_id", "agent_id", "status"), List.copyOf(mint.keySet()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mirrorStructuredMintBackAddsFieldAsFirstKey() {
        // ported from: "adds the field as the FIRST key of structuredContent without mutating"
        Map<String, Object> mint = new LinkedHashMap<>();
        mint.put("session_id", T);
        mint.put("status", "issued");

        Map<String, Object> originalSc = new LinkedHashMap<>();
        originalSc.put("a", 1);

        McpSchema.CallToolResult original = McpSchema.CallToolResult.builder()
                .structuredContent(originalSc)
                .build();

        McpSchema.CallToolResult out = MintBackHandler.mirrorStructuredMintBack(original, mint);

        // mcp_session injected and equals the mint map
        Map<String, Object> sc = (Map<String, Object>) out.structuredContent();
        assertEquals(mint, sc.get(MintBackHandler.MCP_SESSION_KEY));
        assertEquals(1, sc.get("a"));

        // mcp_session must be the FIRST key (survives client-side truncation)
        assertEquals(MintBackHandler.MCP_SESSION_KEY, sc.keySet().iterator().next());

        // original structuredContent must not be mutated
        assertEquals(1, originalSc.size());
        assertFalse(originalSc.containsKey(MintBackHandler.MCP_SESSION_KEY));

        // out must be a different object
        assertNotSame(original, out);
    }
}
