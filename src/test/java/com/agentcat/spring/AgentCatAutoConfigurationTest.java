package com.agentcat.spring;

import com.agentcat.sdk.AgentCatOptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentCatAutoConfigurationTest {

    @Test
    void createsPostProcessorWithNullOptsWhenNoBeanDeclared() {
        AgentCatProperties props = new AgentCatProperties();
        props.setProjectId("proj_auto");

        ObjectProvider<AgentCatOptions> provider = emptyProvider();
        AgentCatBeanPostProcessor result =
                new AgentCatAutoConfiguration().agentCatBeanPostProcessor(props, provider);

        assertNotNull(result);
    }

    @Test
    void createsPostProcessorWithCustomOptsWhenBeanDeclared() {
        AgentCatProperties props = new AgentCatProperties();
        props.setProjectId("proj_custom");

        AgentCatOptions customOpts = new AgentCatOptions();
        customOpts.setEnableAgentTracking(true);

        ObjectProvider<AgentCatOptions> provider = providerOf(customOpts);
        AgentCatBeanPostProcessor result =
                new AgentCatAutoConfiguration().agentCatBeanPostProcessor(props, provider);

        assertNotNull(result);
    }

    @Test
    void eachCallProducesNewPostProcessorInstance() {
        AgentCatProperties props = new AgentCatProperties();
        props.setProjectId("proj_new");

        AgentCatAutoConfiguration config = new AgentCatAutoConfiguration();
        AgentCatBeanPostProcessor first  = config.agentCatBeanPostProcessor(props, emptyProvider());
        AgentCatBeanPostProcessor second = config.agentCatBeanPostProcessor(props, emptyProvider());

        assertNotSame(first, second);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AgentCatOptions> emptyProvider() {
        ObjectProvider<AgentCatOptions> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AgentCatOptions> providerOf(AgentCatOptions opts) {
        ObjectProvider<AgentCatOptions> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(opts);
        return p;
    }
}
