package com.agentcat.sdk.modules.handles;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.model.HandleResolution;
import com.agentcat.sdk.model.SessionSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SessionManager#resolveHandles}.
 */
class SessionManagerTest {

    private static AgentCatOptions defaultOpts() {
        return new AgentCatOptions();
    }

    // ── No args / absent session_id → MINTED ────────────────────────────────────

    @Test
    void nullArgsMintsFreshSession() {
        HandleResolution r = SessionManager.resolveHandles(null, "tool", defaultOpts());
        assertEquals(SessionSource.MINTED, r.getSessionSource());
        assertTrue(r.getSessionId().startsWith("ses_"), "minted id must start with ses_");
    }

    @Test
    void emptyArgsMintsFreshSession() {
        HandleResolution r = SessionManager.resolveHandles(Map.of(), "tool", defaultOpts());
        assertEquals(SessionSource.MINTED, r.getSessionSource());
    }

    @Test
    void startSentinelLowercaseMintsFreshSession() {
        Map<String, Object> args = Map.of("session_id", "start");
        HandleResolution r = SessionManager.resolveHandles(args, "tool", defaultOpts());
        assertEquals(SessionSource.MINTED, r.getSessionSource());
    }

    @Test
    void startSentinelUppercaseMintsFreshSession() {
        Map<String, Object> args = Map.of("session_id", "START");
        HandleResolution r = SessionManager.resolveHandles(args, "tool", defaultOpts());
        assertEquals(SessionSource.MINTED, r.getSessionSource());
    }

    @Test
    void startSentinelMixedCaseMintsFreshSession() {
        Map<String, Object> args = Map.of("session_id", "sTaRt");
        HandleResolution r = SessionManager.resolveHandles(args, "tool", defaultOpts());
        assertEquals(SessionSource.MINTED, r.getSessionSource());
    }

    @Test
    void startWithLeadingSpacesYieldsInvalid() {
        // Java SessionManager does NOT trim — whitespace-padded "start" is NOT the sentinel
        Map<String, Object> args = Map.of("session_id", "  start  ");
        HandleResolution r = SessionManager.resolveHandles(args, "tool", defaultOpts());
        assertEquals(SessionSource.INVALID, r.getSessionSource(),
                "Java does not trim the sentinel — \"  start  \" must resolve as INVALID");
    }

    // ── Valid ses_ → SUPPLIED ────────────────────────────────────────────────────

    @Test
    void validSesIdYieldsSupplied() {
        String validId = "ses_" + "a".repeat(27);
        Map<String, Object> args = Map.of("session_id", validId);
        HandleResolution r = SessionManager.resolveHandles(args, "tool", defaultOpts());
        assertEquals(SessionSource.SUPPLIED, r.getSessionSource());
        assertEquals(validId, r.getSessionId());
    }

    // ── Invalid values → INVALID ─────────────────────────────────────────────────

    @Test
    void unrecognizedStringYieldsInvalid() {
        Map<String, Object> args = Map.of("session_id", "nope");
        HandleResolution r = SessionManager.resolveHandles(args, "tool", defaultOpts());
        assertEquals(SessionSource.INVALID, r.getSessionSource());
    }

    @Test
    void invalidSessionIdYieldsEmptySessionId() {
        // Mirror TypeScript: publish sessionless rather than mint a surrogate.
        Map<String, Object> args = Map.of("session_id", "nope");
        HandleResolution r = SessionManager.resolveHandles(args, "tool", defaultOpts());
        assertEquals("", r.getSessionId(),
                "invalid session_id must produce an empty sessionId (sessionless event)");
    }

    @Test
    void secretTokenYieldsInvalidAndIsNotEchoed() {
        String secret = "sk_live_secret_token";
        Map<String, Object> args = Map.of("session_id", secret);
        HandleResolution r = SessionManager.resolveHandles(args, "tool", defaultOpts());
        assertEquals(SessionSource.INVALID, r.getSessionSource());
        assertEquals("", r.getSessionId(),
                "invalid session_id must produce an empty sessionId (sessionless event)");
        assertFalse(r.toString().contains(secret),
                "secret value must not appear in HandleResolution.toString()");
    }

    // ── Two MINTED results are fresh each time ───────────────────────────────────

    @Test
    void twoMintedResultsAreDistinct() {
        HandleResolution r1 = SessionManager.resolveHandles(null, "tool", defaultOpts());
        HandleResolution r2 = SessionManager.resolveHandles(null, "tool", defaultOpts());
        assertNotEquals(r1.getSessionId(), r2.getSessionId(),
                "each mint must produce a unique session ID");
    }

    // ── agent_id resolution ───────────────────────────────────────────────────────

    @Test
    void agentIdIgnoredWhenEnableAgentTrackingFalse() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(false);
        Map<String, Object> args = Map.of("agent_id", "my-agent");
        HandleResolution r = SessionManager.resolveHandles(args, "tool", opts);
        assertNull(r.getAgentId(), "agent_id must be null when enableAgentTracking=false");
    }

    @Test
    void agentIdResolvedWhenEnableAgentTrackingTrue() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        Map<String, Object> args = new HashMap<>();
        args.put("session_id", "start");
        args.put("agent_id", "my-agent");
        HandleResolution r = SessionManager.resolveHandles(args, "tool", opts);
        assertEquals("my-agent", r.getAgentId());
    }

    // ── Hook mode ─────────────────────────────────────────────────────────────────

    @Test
    void resolveSessionIdHookYieldsHookMode() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setResolveSessionId((req, extra) -> CompletableFuture.completedFuture("user-123"));
        HandleResolution r = SessionManager.resolveHandles(Map.of(), "tool", opts);
        assertEquals(SessionSource.HOOK_MODE, r.getSessionSource());
    }

    @Test
    void hookModeSessionIdIsEmptyString() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setResolveSessionId((req, extra) -> CompletableFuture.completedFuture("user-123"));
        HandleResolution r = SessionManager.resolveHandles(Map.of(), "tool", opts);
        assertEquals("", r.getSessionId(),
                "HOOK_MODE resolution must use empty placeholder session ID");
    }

    @Test
    void hookModeStillResolvesAgentIdWhenEnabled() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setResolveSessionId((req, extra) -> CompletableFuture.completedFuture("user-123"));
        opts.setEnableAgentTracking(true);
        Map<String, Object> args = new HashMap<>();
        args.put("agent_id", "agent-007");
        HandleResolution r = SessionManager.resolveHandles(args, "tool", opts);
        assertEquals(SessionSource.HOOK_MODE, r.getSessionSource());
        assertEquals("agent-007", r.getAgentId());
    }
}
