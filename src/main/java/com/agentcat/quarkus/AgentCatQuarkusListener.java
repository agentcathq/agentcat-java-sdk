package com.agentcat.quarkus;

import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.micronaut.context.annotation.Requires;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;

/**
 * Quarkus integration for AgentCat analytics.
 *
 * <p>Listens for application startup and wraps any {@link McpSyncServer} or
 * {@link McpAsyncServer} CDI bean with {@link AgentCat#track} automatically.
 *
 * <p>Activates when {@code agentcat.project-id} is set in
 * {@code application.properties} or via the {@code AGENTCAT_PROJECT_ID}
 * environment variable.
 *
 * <p>To customise hooks, declare an {@link AgentCatOptions} bean in your
 * Quarkus context — the listener will use it instead of the default:
 * <pre>{@code
 * @Singleton
 * @DefaultBean
 * public AgentCatOptions agentCatOptions() {
 *     AgentCatOptions opts = new AgentCatOptions();
 *     opts.setIdentify((req, ctx) -> CompletableFuture.completedFuture(
 *         new UserIdentity(getCurrentUserId(), null, null)
 *     ));
 *     return opts;
 * }
 * }</pre>
 *
 * <p><b>Note:</b> targets the official MCP Java SDK
 * ({@code io.modelcontextprotocol.sdk:mcp}), not {@code quarkus-mcp-server}.
 */
@Requires(classes = io.quarkus.runtime.StartupEvent.class)
@Startup
@ApplicationScoped
public class AgentCatQuarkusListener {

    @Inject
    Instance<McpSyncServer> syncServers;

    @Inject
    Instance<McpAsyncServer> asyncServers;

    @Inject
    Instance<McpStatelessSyncServer> statelessSyncServers;

    @Inject
    Instance<McpStatelessAsyncServer> statelessAsyncServers;

    /** User-declared AgentCatOptions bean — optional, used when present. */
    @Inject
    Instance<AgentCatOptions> customOpts;

    @ConfigProperty(name = "agentcat.project-id")
    Optional<String> projectId;

    @ConfigProperty(name = "agentcat.enable-agent-tracking", defaultValue = "false")
    boolean enableAgentTracking;

    @ConfigProperty(name = "agentcat.enable-tool-call-context", defaultValue = "true")
    boolean enableToolCallContext;

    void onStart(@Observes StartupEvent ev) {
        if (projectId.isEmpty() || projectId.get().isBlank()) {
            writeToLog("[Quarkus] agentcat.project-id not set — skipping track()");
            return;
        }

        String pid = projectId.get();
        AgentCatOptions opts = resolveOpts();

        int count = 0;
        for (McpSyncServer server : syncServers) {
            AgentCat.track(server, pid, opts);
            count++;
        }
        for (McpAsyncServer server : asyncServers) {
            AgentCat.track(server, pid, opts);
            count++;
        }
        for (McpStatelessSyncServer server : statelessSyncServers) {
            AgentCat.track(server, pid, opts);
            count++;
        }
        for (McpStatelessAsyncServer server : statelessAsyncServers) {
            AgentCat.track(server, pid, opts);
            count++;
        }

        if (count == 0) {
            writeToLog("[Quarkus] no McpSyncServer or McpAsyncServer beans found — nothing to track");
        }
    }

    /**
     * Returns the user-declared {@link AgentCatOptions} bean if present,
     * otherwise builds a default one from config properties.
     */
    private AgentCatOptions resolveOpts() {
        if (!customOpts.isUnsatisfied()) {
            return customOpts.get();
        }
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(enableAgentTracking);
        opts.setEnableToolCallContext(enableToolCallContext);
        return opts;
    }
}
