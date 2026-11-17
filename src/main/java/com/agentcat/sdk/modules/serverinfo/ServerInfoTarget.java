package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.sdk.AgentCatOptions;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;

/**
 * A tracked server as the heartbeat sees it: the project, the server name and version that
 * the events carry, the options, and a weak handle on the server for its list methods.
 */
public final class ServerInfoTarget {

    final String projectId;
    final String serverName;
    final String serverVersion;
    final AgentCatOptions opts;
    final ServerInfoSource source;

    ServerInfoTarget(String projectId, String serverName, String serverVersion,
                     AgentCatOptions opts, ServerInfoSource source) {
        this.projectId = projectId;
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.opts = opts;
        this.source = source;
    }

    /** The target of a stateful server; {@code null} when {@code server} is {@code null}. */
    public static ServerInfoTarget of(String projectId, String serverName, String serverVersion,
                                      AgentCatOptions opts, McpAsyncServer server) {
        return server == null ? null
                : new ServerInfoTarget(projectId, serverName, serverVersion, opts, McpServerSource.of(server));
    }

    /** The target of a stateless server; {@code null} when {@code server} is {@code null}. */
    public static ServerInfoTarget of(String projectId, String serverName, String serverVersion,
                                      AgentCatOptions opts, McpStatelessAsyncServer server) {
        return server == null ? null
                : new ServerInfoTarget(projectId, serverName, serverVersion, opts, McpServerSource.of(server));
    }
}
