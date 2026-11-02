package com.agentcat.spring;

import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Intercepts {@link McpSyncServer} and {@link McpAsyncServer} beans after they
 * are created by Spring (including Spring AI auto-configuration) and wraps them
 * with AgentCat analytics via {@link AgentCat#track}.
 *
 * <p>Registered automatically by {@link AgentCatAutoConfiguration} when
 * {@code agentcat.project-id} is set. No code changes required on the user's side.
 *
 * <p>If a custom {@link AgentCatOptions} bean is present in the Spring context,
 * it is picked up via constructor injection and used instead of the options derived
 * from {@link AgentCatProperties}.
 */
public class AgentCatBeanPostProcessor implements BeanPostProcessor {

    private final AgentCatProperties props;
    private final AgentCatOptions opts;

    /**
     * @param props   bound from {@code agentcat.*} properties
     * @param opts    custom options bean if declared by the user, otherwise
     *                options are built from {@code props}
     */
    public AgentCatBeanPostProcessor(AgentCatProperties props, AgentCatOptions opts) {
        this.props = props;
        this.opts  = opts != null ? opts : buildOpts(props);
    }

    /**
     * Intercepts all four MCP server types ({@link McpSyncServer}, {@link McpAsyncServer},
     * {@link McpStatelessSyncServer}, {@link McpStatelessAsyncServer}) and wraps each
     * with {@link AgentCat#track}. All other beans pass through unchanged.
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        if (bean instanceof McpSyncServer server) {
            AgentCat.track(server, props.getProjectId(), opts);
        } else if (bean instanceof McpAsyncServer server) {
            AgentCat.track(server, props.getProjectId(), opts);
        } else if (bean instanceof McpStatelessSyncServer server) {
            AgentCat.track(server, props.getProjectId(), opts);
        } else if (bean instanceof McpStatelessAsyncServer server) {
            AgentCat.track(server, props.getProjectId(), opts);
        }
        return bean;
    }

    private static AgentCatOptions buildOpts(AgentCatProperties props) {
        AgentCatOptions o = new AgentCatOptions();
        o.setEnableAgentTracking(props.isEnableAgentTracking());
        o.setEnableToolCallContext(props.isEnableToolCallContext());
        return o;
    }
}
