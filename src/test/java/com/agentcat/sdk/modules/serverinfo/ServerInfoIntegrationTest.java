package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.api.model.PublishServerInfoRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The heartbeat of a stateful server that a real MCP client uses over streamable HTTP. */
class ServerInfoIntegrationTest extends HeartbeatTestSupport {

    private static final McpSchema.CallToolRequest ALPHA_CALL = new McpSchema.CallToolRequest("alpha",
            Map.of("q", "x", "session_id", "start", "context", "Checking the heartbeat."));

    private final EventCapture capture = new EventCapture();
    private HttpServletStreamableServerTransportProvider transport;
    private McpTestContext ctx;

    @BeforeEach
    void startServer() throws Exception {
        capture.start();
        transport = HttpServletStreamableServerTransportProvider.builder().build();
        var spec = McpServer.sync(transport)
                .serverInfo(new McpSchema.Implementation("demo-server", "Demo Title", "1.2.3"))
                .instructions("Use the demo tools.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());
        for (McpSchema.Tool tool : ServerInfoFixture.tools()) {
            spec.toolCall(tool, (exchange, request) -> McpSchema.CallToolResult.builder().addTextContent("ok").build());
        }
        McpSyncServer server = AgentCat.track(spec.build(), ServerInfoFixture.PROJECT_ID);
        ctx = McpTestContext.start(transport, server);
    }

    @AfterEach
    void stopServer() throws Exception {
        if (ctx != null) ctx.close();
        capture.stop();
    }

    @Test
    void v1_theToolsEqualWhatTheClientGetsFromToolsList() throws Exception {
        List<McpSchema.Tool> clientTools = ctx.client().listTools().tools();

        McpSchema.CallToolResult result = ctx.client().callTool(
                ALPHA_CALL);

        assertFalse(Boolean.TRUE.equals(result.isError()), String.valueOf(result.content()));
        List<PublishServerInfoRequest> posts = postsAfterIdle();
        assertEquals(1, posts.size());
        assertEquals(wire(clientTools), features(posts.get(0)).get("tools"));
        assertEquals(List.of("alpha", "beta", "gamma", "get_more_tools"), names(list(posts.get(0), "tools")));
    }

    @Test
    void v15_theHeartbeatCarriesTheServerNameAndVersionOfTheEvent() throws Exception {
        ctx.client().callTool(ALPHA_CALL);

        capture.flush();
        PublishEventRequest event = capture.findByTool("alpha");
        List<PublishServerInfoRequest> posts = postsAfterIdle();
        assertEquals(1, posts.size());
        assertEquals(event.getServerName(), posts.get(0).getServerName());
        assertEquals(event.getServerVersion(), posts.get(0).getServerVersion());
        assertEquals("demo-server", posts.get(0).getServerName());
    }
}
