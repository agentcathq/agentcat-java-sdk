package com.agentcat.quarkus;

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
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class AgentCatQuarkusListenerTest {

    // ── project-id absent / blank → skip ────────────────────────────────────

    @Test
    void skipsWhenProjectIdIsAbsent() throws Exception {
        AgentCatQuarkusListener listener = listener(
                Optional.empty(),
                emptyInstance(), emptyInstance(),
                unsatisfiedInstance(),
                false, true);

        assertDoesNotThrow(() -> listener.onStart(new StartupEvent()));
    }

    @Test
    void skipsWhenProjectIdIsBlank() throws Exception {
        AgentCatQuarkusListener listener = listener(
                Optional.of("   "),
                emptyInstance(), emptyInstance(),
                unsatisfiedInstance(),
                false, true);

        assertDoesNotThrow(() -> listener.onStart(new StartupEvent()));
    }

    // ── sync server wrapped ──────────────────────────────────────────────────

    @Test
    void tracksSyncServer() throws Exception {
        AgentCatQuarkusListener listener = listener(
                Optional.of("proj_sync"),
                singleInstance(buildSyncServer()), emptyInstance(),
                unsatisfiedInstance(),
                false, true);

        assertDoesNotThrow(() -> listener.onStart(new StartupEvent()));
    }

    // ── async server wrapped ─────────────────────────────────────────────────

    @Test
    void tracksAsyncServer() throws Exception {
        AgentCatQuarkusListener listener = listener(
                Optional.of("proj_async"),
                emptyInstance(), singleInstance(buildAsyncServer()),
                unsatisfiedInstance(),
                false, true);

        assertDoesNotThrow(() -> listener.onStart(new StartupEvent()));
    }

    // ── both servers wrapped ─────────────────────────────────────────────────

    @Test
    void tracksBothSyncAndAsync() throws Exception {
        AgentCatQuarkusListener listener = listener(
                Optional.of("proj_both"),
                singleInstance(buildSyncServer()), singleInstance(buildAsyncServer()),
                unsatisfiedInstance(),
                false, true);

        assertDoesNotThrow(() -> listener.onStart(new StartupEvent()));
    }

    // ── stateless sync server wrapped ───────────────────────────────────────

    @Test
    void tracksStatelessSyncServer() throws Exception {
        AgentCatQuarkusListener listener = listener(
                Optional.of("proj_stateless_sync"),
                emptyInstance(), emptyInstance(),
                singleInstance(buildStatelessSyncServer()), emptyInstance(),
                unsatisfiedInstance(),
                false, true);

        assertDoesNotThrow(() -> listener.onStart(new StartupEvent()));
    }

    // ── stateless async server wrapped ──────────────────────────────────────

    @Test
    void tracksStatelessAsyncServer() throws Exception {
        AgentCatQuarkusListener listener = listener(
                Optional.of("proj_stateless_async"),
                emptyInstance(), emptyInstance(),
                emptyInstance(), singleInstance(buildStatelessAsyncServer()),
                unsatisfiedInstance(),
                false, true);

        assertDoesNotThrow(() -> listener.onStart(new StartupEvent()));
    }

    // ── all four server types wrapped ────────────────────────────────────────

    @Test
    void tracksAllFourServerTypes() throws Exception {
        AgentCatQuarkusListener listener = listener(
                Optional.of("proj_all"),
                singleInstance(buildSyncServer()), singleInstance(buildAsyncServer()),
                singleInstance(buildStatelessSyncServer()), singleInstance(buildStatelessAsyncServer()),
                unsatisfiedInstance(),
                false, true);

        assertDoesNotThrow(() -> listener.onStart(new StartupEvent()));
    }

    // ── no servers → no-op, no throw ────────────────────────────────────────

    @Test
    void noServersDoesNotThrow() throws Exception {
        AgentCatQuarkusListener listener = listener(
                Optional.of("proj_noservers"),
                emptyInstance(), emptyInstance(),
                unsatisfiedInstance(),
                false, true);

        assertDoesNotThrow(() -> listener.onStart(new StartupEvent()));
    }

    // ── custom options bean used when present ────────────────────────────────

    @Test
    void usesCustomOptsWhenPresent() throws Exception {
        AgentCatOptions custom = new AgentCatOptions();
        custom.setEnableAgentTracking(true);

        AgentCatQuarkusListener listener = listener(
                Optional.of("proj_custom"),
                singleInstance(buildSyncServer()), emptyInstance(),
                satisfiedInstance(custom),
                false, true);

        assertDoesNotThrow(() -> listener.onStart(new StartupEvent()));
    }

    // ── feature flags propagated into default opts ────────────────────────────

    @Test
    void agentTrackingFlagPropagated() throws Exception {
        AgentCatQuarkusListener listener = listener(
                Optional.of("proj_flags"),
                singleInstance(buildSyncServer()), emptyInstance(),
                unsatisfiedInstance(),
                true, false);

        assertDoesNotThrow(() -> listener.onStart(new StartupEvent()));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AgentCatQuarkusListener listener(
            Optional<String> projectId,
            Instance<McpSyncServer> syncServers,
            Instance<McpAsyncServer> asyncServers,
            Instance<AgentCatOptions> customOpts,
            boolean enableAgentTracking,
            boolean enableToolCallContext) throws Exception {
        return listener(projectId, syncServers, asyncServers,
                emptyInstance(), emptyInstance(),
                customOpts, enableAgentTracking, enableToolCallContext);
    }

    private static AgentCatQuarkusListener listener(
            Optional<String> projectId,
            Instance<McpSyncServer> syncServers,
            Instance<McpAsyncServer> asyncServers,
            Instance<McpStatelessSyncServer> statelessSyncServers,
            Instance<McpStatelessAsyncServer> statelessAsyncServers,
            Instance<AgentCatOptions> customOpts,
            boolean enableAgentTracking,
            boolean enableToolCallContext) throws Exception {

        AgentCatQuarkusListener l = new AgentCatQuarkusListener();
        set(l, "projectId",              projectId);
        set(l, "syncServers",            syncServers);
        set(l, "asyncServers",           asyncServers);
        set(l, "statelessSyncServers",   statelessSyncServers);
        set(l, "statelessAsyncServers",  statelessAsyncServers);
        set(l, "customOpts",             customOpts);
        set(l, "enableAgentTracking",    enableAgentTracking);
        set(l, "enableToolCallContext",  enableToolCallContext);
        return l;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> emptyInstance() {
        Instance<T> inst = mock(Instance.class);
        when(inst.isUnsatisfied()).thenReturn(true);
        when(inst.iterator()).thenReturn(List.<T>of().iterator());
        return inst;
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> unsatisfiedInstance() {
        Instance<T> inst = mock(Instance.class);
        when(inst.isUnsatisfied()).thenReturn(true);
        return inst;
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> singleInstance(T value) {
        Instance<T> inst = mock(Instance.class);
        when(inst.isUnsatisfied()).thenReturn(false);
        when(inst.get()).thenReturn(value);
        when(inst.iterator()).thenReturn(List.of(value).iterator());
        return inst;
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> satisfiedInstance(T value) {
        Instance<T> inst = mock(Instance.class);
        when(inst.isUnsatisfied()).thenReturn(false);
        when(inst.get()).thenReturn(value);
        when(inst.iterator()).thenReturn(List.of(value).iterator());
        return inst;
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
