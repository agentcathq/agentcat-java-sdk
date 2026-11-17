package com.agentcat.sdk.modules.serverinfo;

/** Reads a tracked server for the heartbeat. */
@FunctionalInterface
interface ServerInfoSource {

    /** The server's features, or {@code null} when the server no longer exists. */
    ServerFeatures read();
}
