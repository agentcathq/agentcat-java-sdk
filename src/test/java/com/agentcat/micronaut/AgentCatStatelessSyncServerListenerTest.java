package com.agentcat.micronaut;

import com.agentcat.sdk.AgentCatOptions;
import io.micronaut.context.event.BeanCreatedEvent;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentCatStatelessSyncServerListenerTest {

    @Test
    void onCreatedReturnsTheSameServer() {
        AgentCatStatelessSyncServerListener listener = buildListener("proj_test", new AgentCatOptions());
        McpStatelessSyncServer server = buildServer();

        McpStatelessSyncServer returned = listener.onCreated(eventOf(server));

        assertSame(server, returned);
    }

    @Test
    void onCreatedDoesNotThrowWithDefaultOpts() {
        AgentCatStatelessSyncServerListener listener = buildListener("proj_default", new AgentCatOptions());

        assertDoesNotThrow(() -> listener.onCreated(eventOf(buildServer())));
    }

    @Test
    void onCreatedDoesNotThrowWithAgentTrackingEnabled() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        AgentCatStatelessSyncServerListener listener = buildListener("proj_agents", opts);

        assertDoesNotThrow(() -> listener.onCreated(eventOf(buildServer())));
    }

    @Test
    void onCreatedDoesNotThrowWithBlankProjectId() {
        AgentCatStatelessSyncServerListener listener = buildListener("", new AgentCatOptions());

        assertDoesNotThrow(() -> listener.onCreated(eventOf(buildServer())));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AgentCatStatelessSyncServerListener buildListener(String projectId, AgentCatOptions opts) {
        AgentCatMicronautConfig config = new AgentCatMicronautConfig();
        config.setProjectId(projectId);
        return new AgentCatStatelessSyncServerListener(config, opts);
    }

    @SuppressWarnings("unchecked")
    private static BeanCreatedEvent<McpStatelessSyncServer> eventOf(McpStatelessSyncServer server) {
        BeanCreatedEvent<McpStatelessSyncServer> event = mock(BeanCreatedEvent.class);
        when(event.getBean()).thenReturn(server);
        return event;
    }

    private static McpStatelessSyncServer buildServer() {
        return McpServer.sync(statelessTransport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .build();
    }

    private static McpStatelessServerTransport statelessTransport() {
        return new McpStatelessServerTransport() {
            @Override public void setMcpHandler(McpStatelessServerHandler h) {}
            @Override public Mono<Void> closeGracefully() { return Mono.empty(); }
        };
    }
}
