package com.agentcat.spring;

import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for AgentCat analytics.
 *
 * <p>Activates automatically when:
 * <ul>
 *   <li>{@link AgentCat} and {@link McpSyncServer} are on the classpath.</li>
 *   <li>{@code agentcat.project-id} is set in {@code application.yml} /
 *       {@code application.properties}.</li>
 * </ul>
 *
 * <p>Registers {@link AgentCatBeanPostProcessor}, which intercepts any
 * {@code McpSyncServer} or {@code McpAsyncServer} bean (including those
 * created by Spring AI MCP Server starter) and wraps it with AgentCat
 * analytics — without any code changes on the user's side.
 *
 * <p>To customise hooks (identify, eventTags, etc.), declare an
 * {@link AgentCatOptions} bean in your Spring context — the post-processor
 * will pick it up automatically:
 * <pre>{@code
 * @Bean
 * public AgentCatOptions agentCatOptions() {
 *     AgentCatOptions opts = new AgentCatOptions();
 *     opts.setIdentify((req, ctx) -> CompletableFuture.completedFuture(
 *         new UserIdentity(getCurrentUserId(), null, null)
 *     ));
 *     return opts;
 * }
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnClass({AgentCat.class, McpSyncServer.class})
@ConditionalOnProperty(prefix = "agentcat", name = "project-id")
@EnableConfigurationProperties(AgentCatProperties.class)
public class AgentCatAutoConfiguration {

    /**
     * Registers the post-processor that wraps MCP server beans with AgentCat analytics.
     * Uses {@link ObjectProvider} so the {@link AgentCatOptions} bean is optional —
     * if absent, options are derived from {@link AgentCatProperties}.
     */
    @Bean
    public AgentCatBeanPostProcessor agentCatBeanPostProcessor(
            AgentCatProperties props,
            ObjectProvider<AgentCatOptions> optsProvider) {
        // Use custom AgentCatOptions bean if the user declared one, otherwise
        // build from properties. ObjectProvider avoids a required-bean failure.
        return new AgentCatBeanPostProcessor(props, optsProvider.getIfAvailable());
    }
}
