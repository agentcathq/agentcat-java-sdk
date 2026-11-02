package com.agentcat.spring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentCatPropertiesTest {

    @Test
    void defaultValues() {
        AgentCatProperties props = new AgentCatProperties();
        assertNull(props.getProjectId());
        assertFalse(props.isEnableAgentTracking());
        assertTrue(props.isEnableToolCallContext());
    }

    @Test
    void setProjectId() {
        AgentCatProperties props = new AgentCatProperties();
        props.setProjectId("proj_spring");
        assertEquals("proj_spring", props.getProjectId());
    }

    @Test
    void setEnableAgentTracking() {
        AgentCatProperties props = new AgentCatProperties();
        props.setEnableAgentTracking(true);
        assertTrue(props.isEnableAgentTracking());
    }

    @Test
    void setEnableToolCallContextFalse() {
        AgentCatProperties props = new AgentCatProperties();
        props.setEnableToolCallContext(false);
        assertFalse(props.isEnableToolCallContext());
    }

    @Test
    void allSettersRoundtrip() {
        AgentCatProperties props = new AgentCatProperties();
        props.setProjectId("proj_roundtrip");
        props.setEnableAgentTracking(true);
        props.setEnableToolCallContext(false);

        assertEquals("proj_roundtrip", props.getProjectId());
        assertTrue(props.isEnableAgentTracking());
        assertFalse(props.isEnableToolCallContext());
    }
}
