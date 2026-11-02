package com.agentcat.sdk.e2e;

import io.modelcontextprotocol.client.McpSyncClient;

/**
 * A running e2e lane instance returned by {@link E2ELane#start}.
 *
 * <p>Java analogue of the TypeScript {@code LaneInstance} interface.
 * Every call to {@link #connectClient()} creates a fresh initialized
 * {@link McpSyncClient} over the real HTTP endpoint. All clients are
 * automatically closed when {@link #close()} is called.
 */
public interface LaneInstance extends AutoCloseable {

    /**
     * Creates, connects, and initialises a new {@link McpSyncClient} against
     * this lane's HTTP endpoint. Clients are tracked and closed with the lane.
     */
    McpSyncClient connectClient() throws Exception;

    /**
     * Idempotent teardown: closes all clients, shuts down the MCP server,
     * and stops the embedded HTTP server.
     */
    @Override
    void close() throws Exception;
}
