package com.agentcat.sdk.model;

/**
 * Immutable snapshot of the MCP server and connecting client identifiers
 * reported in analytics events.
 *
 * @param serverName    MCP server name; {@code null} if absent
 * @param serverVersion MCP server version; {@code null} if absent
 * @param clientName    connecting client name; {@code null} if absent
 * @param clientVersion connecting client version; {@code null} if absent
 */
public record ServerContext(String serverName,
                             String serverVersion,
                             String clientName,
                             String clientVersion) {

    /** Convenience factory for when no server/client info is available. */
    public static ServerContext empty() {
        return new ServerContext(null, null, null, null);
    }
}
