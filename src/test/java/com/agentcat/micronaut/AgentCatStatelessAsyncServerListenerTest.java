package com.agentcat.micronaut;

import com.agentcat.sdk.AgentCatOptions;
import io.micronaut.context.event.BeanCreatedEvent;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentCatStatelessAsyncServerListenerTest {

    @Test
    void onCreatedReturnsTheSameServer() {
        AgentCatStatelessAsyncServerListener listener = buildListener("proj_test", new AgentCatOptions());
        McpStatelessAsyncServer server = buildServer();

        McpStatelessAsyncServer returned = listener.onCreated(eventOf(server));

        assertSame(server, returned);
    }

    @Test
    void onCreatedDoesNotThrowWithDefaultOpts() {
        AgentCatStatelessAsyncServerListener listener = buildListener("proj_default", new AgentCatOptions());

        assertDoesNotThrow(() -> listener.onCreated(eventOf(buildServer())));
    }

    @Test
    void onCreatedDoesNotThrowWithAgentTrackingEnabled() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        AgentCatStatelessAsyncServerListener listener = buildListener("proj_agents", opts);

        assertDoesNotThrow(() -> listener.onCreated(eventOf(buildServer())));
    }

    @Test
    void onCreatedDoesNotThrowWithBlankProjectId() {
        AgentCatStatelessAsyncServerListener listener = buildListener("", new AgentCatOptions());

        assertDoesNotThrow(() -> listener.onCreated(eventOf(buildServer())));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AgentCatStatelessAsyncServerListener buildListener(String projectId, AgentCatOptions opts) {
        AgentCatMicronautConfig config = new AgentCatMicronautConfig();
        config.setProjectId(projectId);
        return new AgentCatStatelessAsyncServerListener(config, opts);
    }

    @SuppressWarnings("unchecked")
    private static BeanCreatedEvent<McpStatelessAsyncServer> eventOf(McpStatelessAsyncServer server) {
        BeanCreatedEvent<McpStatelessAsyncServer> event = mock(BeanCreatedEvent.class);
        when(event.getBean()).thenReturn(server);
        return event;
    }

    private static McpStatelessAsyncServer buildServer() {
        return McpServer.async(statelessTransport())
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
