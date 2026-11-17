package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.modules.serverinfo.ServerInfoFixture.Kind;
import com.agentcat.sdk.modules.serverinfo.ServerInfoFixture.Tracked;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerInfoGuardTest extends HeartbeatTestSupport {

    private static final long SECOND = 1_000L;

    private void advance(long ms) {
        clock.addAndGet(ms);
    }

    private static boolean normal(McpSchema.CallToolResult result) {
        return result.content().stream().anyMatch(c -> c instanceof McpSchema.TextContent t && "ok".equals(t.text()));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void v7_oneHeartbeatAnHourForEachServer(Kind kind) throws Exception {
        Tracked server = ServerInfoFixture.tracked(kind);

        server.call("alpha");
        server.call("alpha");
        server.call("alpha");
        assertEquals(1, postsAfterIdle().size());

        advance(3_599 * SECOND);
        server.call("alpha");
        assertEquals(1, postsAfterIdle().size());

        advance(SECOND);
        server.call("alpha");
        assertEquals(2, postsAfterIdle().size());
    }

    @Test
    void v8_twoServerObjectsWithTheSameNameSendOneHeartbeat() throws Exception {
        Tracked first = ServerInfoFixture.tracked(Kind.STATELESS_SYNC);
        Tracked second = ServerInfoFixture.tracked(Kind.STATELESS_SYNC);

        first.call("alpha");
        second.call("alpha");

        assertEquals(1, postsAfterIdle().size());
    }

    @Test
    void v8_twoServerObjectsWithDifferentNamesSendTwoHeartbeats() throws Exception {
        Tracked first = ServerInfoFixture.tracked(Kind.SYNC);
        Tracked second = ServerInfoFixture.tracked(Kind.SYNC, ServerInfoFixture.PROJECT_ID, "other-server",
                new AgentCatOptions(), false);

        first.call("alpha");
        second.call("alpha");

        assertEquals(2, postsAfterIdle().size());
    }

    @Test
    void v9_anEmptyProjectIdSendsNothing() throws Exception {
        Tracked server = ServerInfoFixture.tracked(Kind.SYNC, "", "demo-server", new AgentCatOptions(), false);

        assertTrue(normal(server.call("alpha")));
        server.call("get_more_tools");

        assertTrue(postsAfterIdle().isEmpty());
    }

    @Test
    void v9_tracingOffSendsNothing() throws Exception {
        Tracked server = ServerInfoFixture.tracked(Kind.SYNC, ServerInfoFixture.PROJECT_ID, "demo-server",
                new AgentCatOptions().setEnableTracing(false), false);

        assertTrue(normal(server.call("alpha")));

        assertTrue(postsAfterIdle().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void v17_aNewMemorySendsForAFeedbackNoteAndNotForAToolCall(Kind kind) throws Exception {
        Tracked server = ServerInfoFixture.tracked(kind);

        ServerInfoHeartbeat.resetForTesting(clock::get);
        assertTrue(normal(server.call("alpha")));
        assertTrue(postsAfterIdle().isEmpty(), "no heartbeat for a tool call in a new memory");

        ServerInfoHeartbeat.resetForTesting(clock::get);
        assertFalse(server.call("get_more_tools").content().isEmpty());
        assertEquals(1, postsAfterIdle().size(), "one heartbeat for a feedback note in a new memory");
    }

    @Test
    void v18_noMemoryBetweenRequestsSendsNothingForToolCallsAndToolLists() throws Exception {
        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder().build();
        var spec = McpServer.sync(transport)
                .serverInfo(new McpSchema.Implementation("demo-server", "Demo Title", "1.2.3"))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());
        for (McpSchema.Tool tool : ServerInfoFixture.tools()) {
            spec.toolCall(tool, (exchange, request) -> McpSchema.CallToolResult.builder().addTextContent("ok").build());
        }
        McpSyncServer server = AgentCat.track(spec.build(), ServerInfoFixture.PROJECT_ID);

        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            for (int i = 0; i < 3; i++) {
                ServerInfoHeartbeat.resetForTesting(clock::get);
                assertTrue(normal(ctx.client().callTool(new McpSchema.CallToolRequest("alpha",
                        Map.of("q", "x", "session_id", "start", "context", "Checking the heartbeat.")))));
                ServerInfoHeartbeat.resetForTesting(clock::get);
                assertEquals(4, ctx.client().listTools().tools().size());
            }
        }

        assertTrue(postsAfterIdle().isEmpty());
    }

    @Test
    void v19_aToolCallSendsOnlyOnceTheMemoryIs60SecondsOld() throws Exception {
        Tracked server = ServerInfoFixture.tracked(Kind.SYNC);
        ServerInfoHeartbeat.resetForTesting(clock::get);

        server.call("alpha");
        assertTrue(postsAfterIdle().isEmpty(), "age 0 s");

        advance(59 * SECOND);
        server.call("alpha");
        assertTrue(postsAfterIdle().isEmpty(), "age 59 s");

        advance(SECOND);
        server.call("alpha");
        assertEquals(1, postsAfterIdle().size(), "age 60 s");
    }

    @Test
    void aFailedAttemptAlsoWaitsAnHour() throws Exception {
        Tracked server = ServerInfoFixture.tracked(Kind.SYNC);
        recorder.answer(500);

        server.call("alpha");
        advance(3_599 * SECOND);
        server.call("alpha");

        assertEquals(1, postsAfterIdle().size());
    }

    @Test
    void theGuardEmptiesItselfWhenItHoldsAThousandKeys() {
        ServerInfoGuard guard = new ServerInfoGuard(clock::get);
        guard.startMemoryAt(clock.get() - AGED_MS);
        for (int i = 0; i < ServerInfoGuard.MAX_KEYS; i++) {
            assertTrue(guard.tryStart("proj_x", "server-" + i, false));
        }
        assertEquals(ServerInfoGuard.MAX_KEYS, guard.keyCount());

        assertTrue(guard.tryStart("proj_x", "one-more", false));
        assertEquals(1, guard.keyCount());
    }
}
