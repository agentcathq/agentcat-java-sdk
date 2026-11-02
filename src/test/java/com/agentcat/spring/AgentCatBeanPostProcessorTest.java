package com.agentcat.spring;

import com.agentcat.sdk.AgentCatOptions;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class AgentCatBeanPostProcessorTest {

    // ── sync server ──────────────────────────────────────────────────────────

    @Test
    void syncServerIsReturnedUnchanged() {
        AgentCatBeanPostProcessor processor = buildProcessor("proj_test", null);
        McpSyncServer server = buildSyncServer();

        Object result = processor.postProcessAfterInitialization(server, "mcpSyncServer");

        assertSame(server, result);
    }

    @Test
    void syncServerDoesNotThrow() {
        AgentCatBeanPostProcessor processor = buildProcessor("proj_test", null);

        assertDoesNotThrow(() ->
                processor.postProcessAfterInitialization(buildSyncServer(), "mcpSyncServer"));
    }

    // ── async server ─────────────────────────────────────────────────────────

    @Test
    void asyncServerIsReturnedUnchanged() {
        AgentCatBeanPostProcessor processor = buildProcessor("proj_test", null);
        McpAsyncServer server = buildAsyncServer();

        Object result = processor.postProcessAfterInitialization(server, "mcpAsyncServer");

        assertSame(server, result);
    }

    @Test
    void asyncServerDoesNotThrow() {
        AgentCatBeanPostProcessor processor = buildProcessor("proj_test", null);

        assertDoesNotThrow(() ->
                processor.postProcessAfterInitialization(buildAsyncServer(), "mcpAsyncServer"));
    }

    // ── stateless sync server ────────────────────────────────────────────────

    @Test
    void statelessSyncServerIsReturnedUnchanged() {
        AgentCatBeanPostProcessor processor = buildProcessor("proj_test", null);
        McpStatelessSyncServer server = buildStatelessSyncServer();

        Object result = processor.postProcessAfterInitialization(server, "mcpStatelessSyncServer");

        assertSame(server, result);
    }

    @Test
    void statelessSyncServerDoesNotThrow() {
        AgentCatBeanPostProcessor processor = buildProcessor("proj_test", null);

        assertDoesNotThrow(() ->
                processor.postProcessAfterInitialization(buildStatelessSyncServer(), "mcpStatelessSyncServer"));
    }

    // ── stateless async server ───────────────────────────────────────────────

    @Test
    void statelessAsyncServerIsReturnedUnchanged() {
        AgentCatBeanPostProcessor processor = buildProcessor("proj_test", null);
        McpStatelessAsyncServer server = buildStatelessAsyncServer();

        Object result = processor.postProcessAfterInitialization(server, "mcpStatelessAsyncServer");

        assertSame(server, result);
    }

    @Test
    void statelessAsyncServerDoesNotThrow() {
        AgentCatBeanPostProcessor processor = buildProcessor("proj_test", null);

        assertDoesNotThrow(() ->
                processor.postProcessAfterInitialization(buildStatelessAsyncServer(), "mcpStatelessAsyncServer"));
    }

    // ── non-MCP bean passes through untouched ────────────────────────────────

    @Test
    void nonMcpBeanIsReturnedAsIs() {
        AgentCatBeanPostProcessor processor = buildProcessor("proj_test", null);
        Object randomBean = new Object();

        Object result = processor.postProcessAfterInitialization(randomBean, "someBean");

        assertSame(randomBean, result);
    }

    @Test
    void stringBeanPassesThrough() {
        AgentCatBeanPostProcessor processor = buildProcessor("proj_test", null);
        String bean = "hello";

        Object result = processor.postProcessAfterInitialization(bean, "stringBean");

        assertSame(bean, result);
    }

    // ── custom options are accepted ──────────────────────────────────────────

    @Test
    void customOptsDoNotThrow() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        opts.setEnableToolCallContext(false);
        AgentCatBeanPostProcessor processor = buildProcessor("proj_custom", opts);

        assertDoesNotThrow(() ->
                processor.postProcessAfterInitialization(buildSyncServer(), "mcpSyncServer"));
    }

    // ── options built from properties when no custom opts ────────────────────

    @Test
    void buildOptsFromPropertiesAgentTrackingTrue() {
        AgentCatProperties props = new AgentCatProperties();
        props.setProjectId("proj_flags");
        props.setEnableAgentTracking(true);
        props.setEnableToolCallContext(false);
        AgentCatBeanPostProcessor processor = new AgentCatBeanPostProcessor(props, null);

        // Should not throw — opts are derived from props internally
        assertDoesNotThrow(() ->
                processor.postProcessAfterInitialization(buildSyncServer(), "mcpSyncServer"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AgentCatBeanPostProcessor buildProcessor(String projectId,
                                                             AgentCatOptions opts) {
        AgentCatProperties props = new AgentCatProperties();
        props.setProjectId(projectId);
        return new AgentCatBeanPostProcessor(props, opts);
    }

    private static StdioServerTransportProvider transport() {
        return new StdioServerTransportProvider(new JacksonMcpJsonMapper(new JsonMapper()));
    }

    private static McpSyncServer buildSyncServer() {
        return McpServer.sync(transport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .build();
    }

    private static McpAsyncServer buildAsyncServer() {
        return McpServer.async(transport())
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

    private static McpStatelessSyncServer buildStatelessSyncServer() {
        return McpServer.sync(statelessTransport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .build();
    }

    private static McpStatelessAsyncServer buildStatelessAsyncServer() {
        return McpServer.async(statelessTransport())
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .build();
    }
}
