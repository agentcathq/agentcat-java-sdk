package com.agentcat.micronaut;

import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.modelcontextprotocol.server.McpAsyncServer;
import jakarta.enterprise.inject.Vetoed;
import jakarta.inject.Singleton;

/**
 * Micronaut integration for AgentCat analytics — stateful async servers.
 *
 * <p>Listens for {@link McpAsyncServer} bean creation and wraps it with
 * {@link AgentCat#track} automatically.
 *
 * <p>Activates when {@code agentcat.project-id} is set in
 * {@code application.yml} / {@code application.properties}.
 */
@Vetoed
@Singleton
@Requires(property = "agentcat.project-id")
public class AgentCatAsyncServerListener implements BeanCreatedEventListener<McpAsyncServer> {

    private final AgentCatMicronautConfig config;
    private final AgentCatOptions opts;

    public AgentCatAsyncServerListener(AgentCatMicronautConfig config, AgentCatOptions opts) {
        this.config = config;
        this.opts   = opts;
    }

    /** Wraps the server with AgentCat analytics in-place; the original bean reference is returned unchanged. */
    @Override
    public McpAsyncServer onCreated(BeanCreatedEvent<McpAsyncServer> event) {
        AgentCat.track(event.getBean(), config.getProjectId(), opts);
        return event.getBean();
    }
}
