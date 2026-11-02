package com.agentcat.sdk.e2e.lanes;

import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.e2e.E2ELane;
import com.agentcat.sdk.e2e.E2EToolkit;
import com.agentcat.sdk.e2e.LaneInstance;
import com.agentcat.sdk.e2e.ToolSink;
import com.agentcat.sdk.integration.TestUtils;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stateful-sync lane: one long-lived {@code McpSyncServer} with a session-map
 * transport, {@code track()} applied once at startup.
 *
 * <p>Java analogue of the TypeScript {@code v1-stateful} lane in
 * {@code e2e-http/lanes.ts}. The transport holds an internal session map so
 * consecutive requests from the same client land on the same server instance.
 *
 * <h3>Teardown order</h3>
 * All clients → MCP server → Jetty.
 */
public final class StatefulSyncLane implements E2ELane {

    public static final E2ELane INSTANCE = new StatefulSyncLane();

    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(10);

    private StatefulSyncLane() {}

    @Override
    public String id() {
        return "stateful-sync";
    }

    @Override
    public LaneInstance start(AgentCatOptions options, ToolSink sink) throws Exception {
        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder().build();

        McpSyncServer server = E2EToolkit.buildSync(transport, sink);
        AgentCat.track(server, E2EToolkit.E2E_PROJECT_ID, options);

        int port = TestUtils.findFreePort();
        Server jetty = new Server(port);
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(new ServletHolder(transport), "/*");
        jetty.setHandler(context);
        jetty.start();

        String baseUrl = "http://localhost:" + port;
        List<McpSyncClient> clients = new CopyOnWriteArrayList<>();
        boolean[] closed = {false};

        return new LaneInstance() {
            @Override
            public McpSyncClient connectClient() throws Exception {
                McpSyncClient client = McpClient.sync(
                        HttpClientStreamableHttpTransport.builder(baseUrl)
                                .connectTimeout(CLIENT_TIMEOUT)
                                .build())
                        .build();
                client.initialize();
                clients.add(client);
                return client;
            }

            @Override
            public void close() throws Exception {
                if (closed[0]) return;
                closed[0] = true;
                for (McpSyncClient c : clients) {
                    try { c.closeGracefully(); } catch (Exception ignored) {}
                }
                try { server.closeGracefully(); } catch (Exception ignored) {}
                try { jetty.stop(); } catch (Exception ignored) {}
            }
        };
    }

    @Override
    public String toString() {
        return id();
    }
}
