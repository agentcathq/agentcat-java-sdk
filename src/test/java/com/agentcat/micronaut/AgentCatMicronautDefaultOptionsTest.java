package com.agentcat.micronaut;

import com.agentcat.sdk.AgentCatOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentCatMicronautDefaultOptionsTest {

    @Test
    void buildsOptionsFromConfigDefaults() {
        AgentCatMicronautConfig config = new AgentCatMicronautConfig();

        AgentCatOptions opts = new AgentCatMicronautDefaultOptions().agentCatOptions(config);

        assertNotNull(opts);
        assertFalse(opts.isEnableAgentTracking());
        assertTrue(opts.isEnableToolCallContext());
    }

    @Test
    void reflectsEnableAgentTrackingTrue() {
        AgentCatMicronautConfig config = new AgentCatMicronautConfig();
        config.setEnableAgentTracking(true);

        AgentCatOptions opts = new AgentCatMicronautDefaultOptions().agentCatOptions(config);

        assertTrue(opts.isEnableAgentTracking());
    }

    @Test
    void reflectsEnableToolCallContextFalse() {
        AgentCatMicronautConfig config = new AgentCatMicronautConfig();
        config.setEnableToolCallContext(false);

        AgentCatOptions opts = new AgentCatMicronautDefaultOptions().agentCatOptions(config);

        assertFalse(opts.isEnableToolCallContext());
    }

    @Test
    void eachCallProducesNewOptionsInstance() {
        AgentCatMicronautConfig config = new AgentCatMicronautConfig();
        AgentCatMicronautDefaultOptions factory = new AgentCatMicronautDefaultOptions();

        AgentCatOptions first  = factory.agentCatOptions(config);
        AgentCatOptions second = factory.agentCatOptions(config);

        assertNotSame(first, second);
    }

    @Test
    void otherOptionFieldsHaveCorrectDefaults() {
        AgentCatMicronautConfig config = new AgentCatMicronautConfig();

        AgentCatOptions opts = new AgentCatMicronautDefaultOptions().agentCatOptions(config);

        assertTrue(opts.isEnableTracing(),       "tracing should be on by default");
        assertTrue(opts.isEnableReportMissing(), "get_more_tools should be on by default");
        assertNull(opts.getIdentify(),            "no identify hook by default");
        assertNull(opts.getResolveSessionId(),    "no session hook by default");
    }
}
