package com.agentcat.micronaut;

import com.agentcat.sdk.AgentCatOptions;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.enterprise.inject.Vetoed;
import jakarta.inject.Singleton;

/**
 * Provides a default {@link AgentCatOptions} bean for Micronaut, built from
 * {@link AgentCatMicronautConfig} (i.e. from {@code application.yml}).
 *
 * <p>Active only when {@code agentcat.project-id} is set and the user has not
 * declared their own {@link AgentCatOptions} bean.
 *
 * <p>To supply custom hooks, declare a {@code @Singleton} bean of type
 * {@link AgentCatOptions} in a factory of your own — Micronaut will use it instead:
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
@Factory
@Requires(property = "agentcat.project-id")
public class AgentCatMicronautDefaultOptions {

    /**
     * Builds the default {@link AgentCatOptions} from application config.
     * Replaced automatically when the user declares their own {@code @Singleton AgentCatOptions} bean.
     */
    @Singleton
    public AgentCatOptions agentCatOptions(AgentCatMicronautConfig config) {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(config.isEnableAgentTracking());
        opts.setEnableToolCallContext(config.isEnableToolCallContext());
        return opts;
    }
}
