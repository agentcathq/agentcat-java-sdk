package com.agentcat.sdk.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HandleResolutionTest {

    @Test
    void fullConstructorAndGetters() {
        HandleResolution r = new HandleResolution(
                "ses_abc123", SessionSource.SUPPLIED, "agent-x", "supplied");

        assertEquals("ses_abc123",       r.getSessionId());
        assertEquals(SessionSource.SUPPLIED, r.getSessionSource());
        assertEquals("agent-x",          r.getAgentId());
        assertEquals("supplied",          r.getAgentSource());
    }

    @Test
    void convenienceConstructorWithAgentId_setsAgentSourceToSupplied() {
        HandleResolution r = new HandleResolution("ses_abc123", SessionSource.MINTED, "agent-x");

        assertEquals("agent-x",  r.getAgentId());
        assertEquals("supplied", r.getAgentSource());
    }

    @Test
    void convenienceConstructorWithNullAgentId_setsAgentSourceToNull() {
        HandleResolution r = new HandleResolution("ses_abc123", SessionSource.MINTED, null);

        assertNull(r.getAgentId());
        assertNull(r.getAgentSource());
    }

    @Test
    void setters_updateAllFields() {
        HandleResolution r = new HandleResolution("ses_old", SessionSource.MINTED, null, null);

        r.setSessionId("ses_new");
        r.setSessionSource(SessionSource.SUPPLIED);
        r.setAgentSource("custom");

        assertEquals("ses_new",           r.getSessionId());
        assertEquals(SessionSource.SUPPLIED, r.getSessionSource());
        assertEquals("custom",             r.getAgentSource());
    }

    @Test
    void setAgentId_nonNull_setsAgentSourceToSupplied() {
        HandleResolution r = new HandleResolution("ses_abc123", SessionSource.MINTED, null, null);

        r.setAgentId("agent-y");

        assertEquals("agent-y",  r.getAgentId());
        assertEquals("supplied", r.getAgentSource());
    }

    @Test
    void setAgentId_null_clearsAgentSource() {
        HandleResolution r = new HandleResolution("ses_abc123", SessionSource.MINTED, "agent-x", "supplied");

        r.setAgentId(null);

        assertNull(r.getAgentId());
        assertNull(r.getAgentSource());
    }
}
