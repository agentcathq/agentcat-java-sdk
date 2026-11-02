package com.agentcat.micronaut;

import com.agentcat.sdk.AgentCatOptions;
import io.micronaut.context.event.BeanCreatedEvent;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentCatServerListenerTest {

    // ── onCreated returns same server ────────────────────────────────────────

    @Test
    void onCreatedReturnsTheSameServer() {
        AgentCatServerListener listener = buildListener("proj_test", new AgentCatOptions());
        McpSyncServer server = buildServer();

        McpSyncServer returned = listener.onCreated(eventOf(server));

        assertSame(server, returned);
    }

    // ── does not throw ───────────────────────────────────────────────────────

    @Test
    void onCreatedDoesNotThrowWithDefaultOpts() {
        AgentCatServerListener listener = buildListener("proj_default", new AgentCatOptions());

        assertDoesNotThrow(() -> listener.onCreated(eventOf(buildServer())));
    }

    @Test
    void onCreatedDoesNotThrowWithAgentTrackingEnabled() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        AgentCatServerListener listener = buildListener("proj_agents", opts);

        assertDoesNotThrow(() -> listener.onCreated(eventOf(buildServer())));
    }

    @Test
    void onCreatedDoesNotThrowWithContextDisabled() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(false);
        AgentCatServerListener listener = buildListener("proj_nocontext", opts);

        assertDoesNotThrow(() -> listener.onCreated(eventOf(buildServer())));
    }

    @Test
    void onCreatedDoesNotThrowWithBlankProjectId() {
        AgentCatServerListener listener = buildListener("", new AgentCatOptions());

        assertDoesNotThrow(() -> listener.onCreated(eventOf(buildServer())));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AgentCatServerListener buildListener(String projectId,
                                                         AgentCatOptions opts) {
        AgentCatMicronautConfig config = new AgentCatMicronautConfig();
        config.setProjectId(projectId);
        return new AgentCatServerListener(config, opts);
    }

    @SuppressWarnings("unchecked")
    private static BeanCreatedEvent<McpSyncServer> eventOf(McpSyncServer server) {
        BeanCreatedEvent<McpSyncServer> event = mock(BeanCreatedEvent.class);
        when(event.getBean()).thenReturn(server);
        return event;
    }

    private static McpSyncServer buildServer() {
        return McpServer.sync(
                        new StdioServerTransportProvider(new JacksonMcpJsonMapper(new JsonMapper())))
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .build();
    }
}
