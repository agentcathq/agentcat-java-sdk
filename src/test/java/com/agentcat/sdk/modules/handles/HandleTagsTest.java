package com.agentcat.sdk.modules.handles;

import com.agentcat.sdk.config.AgentCatConstants;
import com.agentcat.sdk.model.HandleResolution;
import com.agentcat.sdk.model.SessionSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HandleTags#buildHandleTags}.
 */
class HandleTagsTest {

    private static HandleResolution resolution(SessionSource source, String agentId) {
        String sessionId = source == SessionSource.HOOK_MODE ? "" : "ses_" + "a".repeat(27);
        return new HandleResolution(sessionId, source, agentId);
    }

    // ── SESSION_ID_SOURCE_TAG values ─────────────────────────────────────────────

    @Test
    void suppliedSessionSourceTagIsSupplied() {
        Map<String, String> tags = HandleTags.buildHandleTags(resolution(SessionSource.SUPPLIED, null));
        assertEquals("supplied", tags.get(HandleTags.SESSION_ID_SOURCE_TAG));
    }

    @Test
    void mintedSessionSourceTagIsMinted() {
        Map<String, String> tags = HandleTags.buildHandleTags(resolution(SessionSource.MINTED, null));
        assertEquals("minted", tags.get(HandleTags.SESSION_ID_SOURCE_TAG));
    }

    @Test
    void invalidSessionSourceTagIsInvalid() {
        Map<String, String> tags = HandleTags.buildHandleTags(resolution(SessionSource.INVALID, null));
        assertEquals("invalid", tags.get(HandleTags.SESSION_ID_SOURCE_TAG));
    }

    @Test
    void hookModeSessionSourceTagIsHook() {
        Map<String, String> tags = HandleTags.buildHandleTags(resolution(SessionSource.HOOK_MODE, null));
        assertEquals("hook", tags.get(HandleTags.SESSION_ID_SOURCE_TAG));
    }

    // ── Agent ID tags ─────────────────────────────────────────────────────────────

    @Test
    void suppliedSessionWithAgentIdHasAgentIdTag() {
        Map<String, String> tags = HandleTags.buildHandleTags(
                resolution(SessionSource.SUPPLIED, "agent-007"));
        assertEquals("agent-007", tags.get(HandleTags.AGENT_ID_TAG));
    }

    @Test
    void suppliedSessionWithAgentIdHasAgentIdSourceTagSupplied() {
        Map<String, String> tags = HandleTags.buildHandleTags(
                resolution(SessionSource.SUPPLIED, "agent-007"));
        assertEquals("supplied", tags.get(HandleTags.AGENT_ID_SOURCE_TAG));
    }

    @Test
    void noAgentIdMeansNoAgentIdTagKey() {
        Map<String, String> tags = HandleTags.buildHandleTags(resolution(SessionSource.MINTED, null));
        assertFalse(tags.containsKey(HandleTags.AGENT_ID_TAG),
                "AGENT_ID_TAG must be absent when no agentId was provided");
    }

    @Test
    void noAgentIdMeansNoAgentIdSourceTagKey() {
        Map<String, String> tags = HandleTags.buildHandleTags(resolution(SessionSource.MINTED, null));
        assertFalse(tags.containsKey(HandleTags.AGENT_ID_SOURCE_TAG),
                "AGENT_ID_SOURCE_TAG must be absent when no agentId was provided");
    }

    // ── PROTOCOL_VERSION_TAG ──────────────────────────────────────────────────────

    @Test
    void alwaysHasProtocolVersionTag() {
        for (SessionSource source : SessionSource.values()) {
            Map<String, String> tags = HandleTags.buildHandleTags(resolution(source, null));
            assertEquals(AgentCatConstants.PROTOCOL_VERSION,
                    tags.get(HandleTags.PROTOCOL_VERSION_TAG),
                    "PROTOCOL_VERSION_TAG must always be present for source: " + source);
        }
    }

    // ── Non-null guarantee ────────────────────────────────────────────────────────

    @Test
    void resultIsNeverNull() {
        for (SessionSource source : SessionSource.values()) {
            assertNotNull(HandleTags.buildHandleTags(resolution(source, null)),
                    "buildHandleTags must never return null for source: " + source);
        }
    }
}
