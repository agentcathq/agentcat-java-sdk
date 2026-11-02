package com.agentcat.micronaut;

import com.agentcat.sdk.AgentCatOptions;
import io.micronaut.context.event.BeanCreatedEvent;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentCatAsyncServerListenerTest {

    @Test
    void onCreatedReturnsTheSameServer() {
        AgentCatAsyncServerListener listener = buildListener("proj_test", new AgentCatOptions());
        McpAsyncServer server = buildServer();

        McpAsyncServer returned = listener.onCreated(eventOf(server));

        assertSame(server, returned);
    }

    @Test
    void onCreatedDoesNotThrowWithDefaultOpts() {
        AgentCatAsyncServerListener listener = buildListener("proj_default", new AgentCatOptions());

        assertDoesNotThrow(() -> listener.onCreated(eventOf(buildServer())));
    }

    @Test
    void onCreatedDoesNotThrowWithAgentTrackingEnabled() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        AgentCatAsyncServerListener listener = buildListener("proj_agents", opts);

        assertDoesNotThrow(() -> listener.onCreated(eventOf(buildServer())));
    }

    @Test
    void onCreatedDoesNotThrowWithContextDisabled() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(false);
        AgentCatAsyncServerListener listener = buildListener("proj_nocontext", opts);

        assertDoesNotThrow(() -> listener.onCreated(eventOf(buildServer())));
    }

    @Test
    void onCreatedDoesNotThrowWithBlankProjectId() {
        AgentCatAsyncServerListener listener = buildListener("", new AgentCatOptions());

        assertDoesNotThrow(() -> listener.onCreated(eventOf(buildServer())));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AgentCatAsyncServerListener buildListener(String projectId, AgentCatOptions opts) {
        AgentCatMicronautConfig config = new AgentCatMicronautConfig();
        config.setProjectId(projectId);
        return new AgentCatAsyncServerListener(config, opts);
    }

    @SuppressWarnings("unchecked")
    private static BeanCreatedEvent<McpAsyncServer> eventOf(McpAsyncServer server) {
        BeanCreatedEvent<McpAsyncServer> event = mock(BeanCreatedEvent.class);
        when(event.getBean()).thenReturn(server);
        return event;
    }

    private static McpAsyncServer buildServer() {
        return McpServer.async(
                        new StdioServerTransportProvider(new JacksonMcpJsonMapper(new JsonMapper())))
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .build();
    }
}
