package com.agentcat.micronaut;

import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import io.micronaut.context.annotation.Requires;
import jakarta.enterprise.inject.Vetoed;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.modelcontextprotocol.server.McpSyncServer;
import jakarta.inject.Singleton;

/**
 * Micronaut integration for AgentCat analytics.
 *
 * <p>Listens for {@link McpSyncServer} bean creation and wraps it with
 * {@link AgentCat#track} automatically — without any code changes on the
 * user's side.
 *
 * <p>Activates when {@code agentcat.project-id} is set in
 * {@code application.yml} / {@code application.properties}.
 *
 * <p>To customise hooks, declare an {@link AgentCatOptions} bean in your
 * Micronaut context and the listener will use it instead of the default:
 * <pre>{@code
 * @Factory
 * public class MyAgentCatOptionsFactory {
 *     @Singleton
 *     @Replaces(bean = AgentCatOptions.class, factory = AgentCatMicronautDefaultOptions.class)
 *     public AgentCatOptions agentCatOptions() {
 *         return new AgentCatOptions().setIdentify((req, ctx) -> CompletableFuture.completedFuture(
 *             new UserIdentity(getCurrentUserId(), null, null)
 *         ));
 *     }
 * }
 * }</pre>
 */
@Vetoed // Prevent CDI containers (Quarkus) from processing this Micronaut bean
@Singleton
@Requires(property = "agentcat.project-id")
public class AgentCatServerListener implements BeanCreatedEventListener<McpSyncServer> {

    private final AgentCatMicronautConfig config;
    private final AgentCatOptions opts;

    /**
     * Micronaut injects {@link AgentCatOptions} via {@link AgentCatMicronautDefaultOptions}
     * unless the user has declared their own replacement bean.
     */
    public AgentCatServerListener(AgentCatMicronautConfig config,
                                   AgentCatOptions opts) {
        this.config = config;
        this.opts   = opts;
    }

    /** Wraps the server with AgentCat analytics in-place; the original bean reference is returned unchanged. */
    @Override
    public McpSyncServer onCreated(BeanCreatedEvent<McpSyncServer> event) {
        AgentCat.track(event.getBean(), config.getProjectId(), opts);
        return event.getBean();
    }
}
