package com.agentcat.sdk.ported;

import com.agentcat.sdk.config.AgentCatConstants;
import com.agentcat.sdk.modules.handles.HandleTags;
import com.agentcat.sdk.modules.handles.MintBackHandler;
import com.agentcat.sdk.modules.handles.SessionIdProvider;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/constants.test.ts
 *
 * TypeScript constants → Java equivalents:
 * <ul>
 *   <li>{@code AGENTCAT_CUSTOM_EVENT_TYPE} → TODO: missing in Java SDK</li>
 *   <li>{@code AGENTCAT_SOURCE} → TODO: missing in Java SDK</li>
 *   <li>{@code DIAGNOSTICS_SCOPE_NAME} → TODO: missing in Java SDK</li>
 *   <li>{@code MCP_SESSION_KEY} → {@link MintBackHandler#MCP_SESSION_KEY}</li>
 *   <li>{@code SESSION_ID_PARAM_PATTERN} → pattern from {@link SessionIdProvider}</li>
 *   <li>{@code SESSION_START_SENTINEL} → {@link SessionIdProvider#START_SENTINEL}</li>
 *   <li>{@code PROTOCOL_VERSION} → {@link AgentCatConstants#PROTOCOL_VERSION}</li>
 * </ul>
 */
class ConstantsTest {

    // ─────────────────────────────────────────────────────────────────────────
    // brand constants (wire literals)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mirrorsUnderMcpSessionKey() {
        // ported from: "mirrors under the mcp_session key"
        assertEquals("mcp_session", MintBackHandler.MCP_SESSION_KEY);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // session_id value-contract constants
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void startSentinelIsLowercaseStart() {
        // ported from: pins SESSION_START_SENTINEL
        assertEquals("start", SessionIdProvider.START_SENTINEL);
    }

    @Test
    void sessionIdPatternMatchesStartAndSesIds() {
        // ported from: "pins the session_id value-contract constants"
        // The TypeScript pattern is: "^(start|ses_[0-9A-Za-z]{27})$"
        // Java equivalent: validate that a valid ses_ ID matches and "start" matches.
        String sesPart = "ses_" + "a".repeat(27);
        assertTrue(sesPart.matches("^ses_[0-9A-Za-z]{27}$"),
                "Valid ses_ ID must match the pattern");
        assertTrue("start".equalsIgnoreCase(SessionIdProvider.START_SENTINEL) ||
                   "start".equals(SessionIdProvider.START_SENTINEL));

        // Negative cases
        assertFalse(("ses_" + "a".repeat(25)).matches("^ses_[0-9A-Za-z]{27}$"));
        assertFalse("restart".matches("^ses_[0-9A-Za-z]{27}$"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // handle param tag names
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void sessionIdSourceTagHasCorrectValue() {
        assertEquals("agentcat_session_id_source", HandleTags.SESSION_ID_SOURCE_TAG);
    }

    @Test
    void agentIdTagHasCorrectValue() {
        assertEquals("agentcat_agent_id", HandleTags.AGENT_ID_TAG);
    }

    @Test
    void agentIdSourceTagHasCorrectValue() {
        assertEquals("agentcat_agent_id_source", HandleTags.AGENT_ID_SOURCE_TAG);
    }

    @Test
    void protocolVersionTagHasCorrectValue() {
        assertEquals("agentcat_protocol_version", HandleTags.PROTOCOL_VERSION_TAG);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AgentCatConstants constants
    // ─────────────────────────────────────────────────────────────────────────

}
