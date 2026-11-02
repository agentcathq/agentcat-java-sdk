package com.agentcat.sdk.config;

import com.agentcat.sdk.modules.handles.HandleTags;
import com.agentcat.sdk.modules.handles.MintBackHandler;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import com.agentcat.sdk.modules.handles.SessionIdProvider;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that every wire-level constant has the exact string value required by the protocol.
 * These are the values that cross the network or appear in schemas — changing them is a breaking change.
 */
class AgentCatConstantsTest {

    // ── AgentCatConstants ─────────────────────────────────────────────────────────

    @Test
    void protocolVersion() {
        assertEquals("SEP-2567", AgentCatConstants.PROTOCOL_VERSION);
    }

    @Test
    void sdkLanguage() {
        assertEquals("java", AgentCatConstants.SDK_LANGUAGE);
    }

    // ── ParameterInjector ──────────────────────────────────────────────────────

    @Test
    void sessionIdParam() {
        assertEquals("session_id", ParameterInjector.SESSION_ID_PARAM);
    }

    @Test
    void agentIdParam() {
        assertEquals("agent_id", ParameterInjector.AGENT_ID_PARAM);
    }

    @Test
    void contextParam() {
        assertEquals("context", ParameterInjector.CONTEXT_PARAM);
    }

    @Test
    void sessionIdPattern() {
        assertEquals("^(start|ses_[0-9A-Za-z]{27})$", ParameterInjector.SESSION_ID_PATTERN);
    }

    // ── SessionIdProvider ──────────────────────────────────────────────────────

    @Test
    void startSentinel() {
        assertEquals("start", SessionIdProvider.START_SENTINEL);
    }

    // ── MintBackHandler ────────────────────────────────────────────────────────

    @Test
    void mcpSessionKey() {
        assertEquals("mcp_session", MintBackHandler.MCP_SESSION_KEY);
    }

    // ── HandleTags ─────────────────────────────────────────────────────────────

    @Test
    void sessionIdSourceTag() {
        assertEquals("agentcat_session_id_source", HandleTags.SESSION_ID_SOURCE_TAG);
    }

    @Test
    void agentIdTag() {
        assertEquals("agentcat_agent_id", HandleTags.AGENT_ID_TAG);
    }

    @Test
    void protocolVersionTag() {
        assertEquals("agentcat_protocol_version", HandleTags.PROTOCOL_VERSION_TAG);
    }

    // ── Pattern acceptance / rejection ─────────────────────────────────────────

    @Test
    void patternAcceptsStartLiteral() {
        assertTrue("start".matches(ParameterInjector.SESSION_ID_PATTERN));
    }

    @Test
    void patternAcceptsValidSesId() {
        String validId = "ses_" + "a".repeat(27);
        assertTrue(validId.matches(ParameterInjector.SESSION_ID_PATTERN));
    }

    @Test
    void patternRejectsTooShortSuffix() {
        String tooShort = "ses_" + "a".repeat(26);
        assertFalse(tooShort.matches(ParameterInjector.SESSION_ID_PATTERN));
    }

    @Test
    void patternRejectsTooLongSuffix() {
        String tooLong = "ses_" + "a".repeat(28);
        assertFalse(tooLong.matches(ParameterInjector.SESSION_ID_PATTERN));
    }

    @Test
    void patternRejectsRestartWord() {
        assertFalse("restart".matches(ParameterInjector.SESSION_ID_PATTERN));
    }

    @Test
    void patternIsCaseSensitive_STARTRejected() {
        assertFalse("START".matches(ParameterInjector.SESSION_ID_PATTERN));
    }

    @Test
    void patternRejectsEmptyString() {
        assertFalse("".matches(ParameterInjector.SESSION_ID_PATTERN));
    }

    @Test
    void patternRejectsNonBase62Chars() {
        String withDashes = "ses_" + "-".repeat(27);
        assertFalse(withDashes.matches(ParameterInjector.SESSION_ID_PATTERN));
    }
}
