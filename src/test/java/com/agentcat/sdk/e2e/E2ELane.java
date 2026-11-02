package com.agentcat.sdk.e2e;

import com.agentcat.sdk.AgentCatOptions;

/**
 * A deployment topology that can be started for a single test scenario.
 *
 * <p>Java analogue of the TypeScript {@code Lane} interface in
 * {@code e2e-http/lanes.ts}. Each implementation spins up an embedded
 * HTTP server, applies AgentCat tracking, and exposes a connected client
 * through {@link LaneInstance}.
 *
 * <h3>Available lanes</h3>
 * <ul>
 *   <li>{@code stateful-sync} — one long-lived {@code McpSyncServer} with a
 *       session-map transport; {@code track()} called once at startup.</li>
 * </ul>
 */
public interface E2ELane {

    /** Stable identifier used in test display names and scenario lane guards. */
    String id();

    /**
     * Starts the lane: creates the server, applies tracking, binds the
     * HTTP transport, and returns a {@link LaneInstance} ready to accept clients.
     *
     * @param options AgentCat options for this scenario run
     * @param sink    tool-sink that customer handlers write into
     * @return a started instance; caller must {@link LaneInstance#close()} it
     */
    LaneInstance start(AgentCatOptions options, ToolSink sink) throws Exception;

    @Override
    String toString();
}
