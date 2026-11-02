package com.agentcat.micronaut;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentCatMicronautConfigTest {

    @Test
    void defaultValues() {
        AgentCatMicronautConfig config = new AgentCatMicronautConfig();
        assertNull(config.getProjectId());
        assertFalse(config.isEnableAgentTracking());
        assertTrue(config.isEnableToolCallContext());
    }

    @Test
    void setProjectId() {
        AgentCatMicronautConfig config = new AgentCatMicronautConfig();
        config.setProjectId("proj_abc123");
        assertEquals("proj_abc123", config.getProjectId());
    }

    @Test
    void setEnableAgentTracking() {
        AgentCatMicronautConfig config = new AgentCatMicronautConfig();
        config.setEnableAgentTracking(true);
        assertTrue(config.isEnableAgentTracking());
    }

    @Test
    void setEnableToolCallContextFalse() {
        AgentCatMicronautConfig config = new AgentCatMicronautConfig();
        config.setEnableToolCallContext(false);
        assertFalse(config.isEnableToolCallContext());
    }

    @Test
    void allSettersRoundtrip() {
        AgentCatMicronautConfig config = new AgentCatMicronautConfig();
        config.setProjectId("proj_roundtrip");
        config.setEnableAgentTracking(true);
        config.setEnableToolCallContext(false);

        assertEquals("proj_roundtrip", config.getProjectId());
        assertTrue(config.isEnableAgentTracking());
        assertFalse(config.isEnableToolCallContext());
    }
}
