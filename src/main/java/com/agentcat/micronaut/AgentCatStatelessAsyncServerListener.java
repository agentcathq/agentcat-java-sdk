package com.agentcat.micronaut;

import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import jakarta.enterprise.inject.Vetoed;
import jakarta.inject.Singleton;

/**
 * Micronaut integration for AgentCat analytics — stateless async servers.
 *
 * <p>Listens for {@link McpStatelessAsyncServer} bean creation and wraps it with
 * {@link AgentCat#track} automatically.
 *
 * <p>Activates when {@code agentcat.project-id} is set in
 * {@code application.yml} / {@code application.properties}.
 */
@Vetoed
@Singleton
@Requires(property = "agentcat.project-id")
public class AgentCatStatelessAsyncServerListener implements BeanCreatedEventListener<McpStatelessAsyncServer> {

    private final AgentCatMicronautConfig config;
    private final AgentCatOptions opts;

    public AgentCatStatelessAsyncServerListener(AgentCatMicronautConfig config, AgentCatOptions opts) {
        this.config = config;
        this.opts   = opts;
    }

    /** Wraps the server with AgentCat analytics in-place; the original bean reference is returned unchanged. */
    @Override
    public McpStatelessAsyncServer onCreated(BeanCreatedEvent<McpStatelessAsyncServer> event) {
        AgentCat.track(event.getBean(), config.getProjectId(), opts);
        return event.getBean();
    }
}
