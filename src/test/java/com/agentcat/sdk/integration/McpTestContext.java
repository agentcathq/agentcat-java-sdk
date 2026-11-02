package com.agentcat.sdk.integration;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

import java.time.Duration;

/**
 * Integration test fixture: embedded Jetty + {@link McpSyncServer} + {@link McpSyncClient}.
 *
 * <p>Java analogue of the TypeScript {@code setupLowLevel()} / {@code connectClient()}
 * helpers. Starts an embedded HTTP server on a random port so tests exercise the full
 * MCP transport stack without any external processes.
 *
 * <h3>Typical usage</h3>
 * <pre>{@code
 * // 1. Create the transport (it IS the HttpServlet)
 * var transport = HttpServletStreamableServerTransportProvider.builder().build();
 *
 * // 2. Build the MCP server and register tools
 * McpSyncServer server = McpServer.sync(transport)
 *         .serverInfo("test-server", "1.0")
 *         .tool(spec, handler)
 *         .build();
 *
 * // 3. Apply AgentCat tracking
 * AgentCat.track(server, "proj_test", options);
 *
 * // 4. Wrap in McpTestContext — starts Jetty and connects the client
 * try (McpTestContext ctx = McpTestContext.start(transport, server)) {
 *     McpSyncClient client = ctx.client();
 *     // ... assertions ...
 * }
 * }</pre>
 */
public class McpTestContext implements AutoCloseable {

    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(10);

    private final Server jetty;
    private final McpSyncServer mcpServer;
    private final McpSyncClient client;
    private final int port;

    private McpTestContext(Server jetty, McpSyncServer mcpServer, McpSyncClient client, int port) {
        this.jetty     = jetty;
        this.mcpServer = mcpServer;
        this.client    = client;
        this.port      = port;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Starts an embedded Jetty server hosting {@code transport}, then creates and
     * initialises a {@link McpSyncClient} connected to it.
     *
     * @param transport the servlet transport provider (already wired into the MCP server)
     * @param mcpServer the pre-built MCP server (tools registered, tracking applied)
     */
    public static McpTestContext start(
            HttpServletStreamableServerTransportProvider transport,
            McpSyncServer mcpServer) throws Exception {

        int port = TestUtils.findFreePort();

        // Mount the transport servlet at /* — it handles path matching internally
        Server jetty = new Server(port);
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(new ServletHolder(transport), "/*");
        jetty.setHandler(context);
        jetty.start();

        // Connect client — default endpoint is /mcp (matches transport default)
        McpSyncClient client = McpClient.sync(
                HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .connectTimeout(CLIENT_TIMEOUT)
                        .build())
                .build();

        client.initialize();

        return new McpTestContext(jetty, mcpServer, client, port);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** The connected MCP client. */
    public McpSyncClient client() {
        return client;
    }

    /** The MCP server under test. */
    public McpSyncServer server() {
        return mcpServer;
    }

    /** The port Jetty is listening on. */
    public int port() {
        return port;
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    /**
     * Closes the client, shuts down the MCP server, and stops Jetty.
     * Safe to call multiple times.
     */
    @Override
    public void close() throws Exception {
        try {
            client.closeGracefully();
        } catch (Exception ignored) {}
        try {
            mcpServer.closeGracefully();
        } catch (Exception ignored) {}
        try {
            jetty.stop();
        } catch (Exception ignored) {}
    }
}
