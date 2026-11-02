package com.agentcat.sdk.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Writes SDK diagnostics exclusively to {@code agentcat.log}.
 *
 * <p>Output is never sent to stdout or stderr. stdio-based MCP transports use stdout
 * for JSON-RPC framing; any extraneous output there corrupts the protocol stream.
 *
 * <p>The log directory defaults to the user home directory and can be changed at any
 * time via {@link #setLogDir(String)}.
 *
 * <p>This logger always writes regardless of {@code DISABLE_DIAGNOSTICS} — that flag
 * controls only the anonymized signal sent to AgentCat's servers, not local log output.
 */
public final class AgentCatLogger {

    private static volatile Path logFile =
            Paths.get(System.getProperty("user.home"), "agentcat.log");

    private AgentCatLogger() {}

    /**
     * Overrides the directory where {@code agentcat.log} is written.
     *
     * <p>Can be called at any point before or after the first log write.
     * Subsequent calls to {@link #writeToLog(String)} will use the new path.
     *
     * @param dir absolute path to the desired log directory
     */
    public static void setLogDir(String dir) {
        logFile = Paths.get(dir, "agentcat.log");
    }

    /**
     * Appends a timestamped message to {@code agentcat.log}.
     *
     * <p>If the file write fails, the message is printed to stderr as a fallback.
     * stderr is safe for stdio-based MCP servers — only stdout is reserved for JSON-RPC.
     *
     * @param message the diagnostic message to append
     */
    public static void writeToLog(String message) {
        String line = Instant.now() + " [AgentCat] " + message + System.lineSeparator();
        try {
            Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // File write failed — fall back to stderr. stdout is intentionally avoided
            // because stdio-based MCP transports use it for JSON-RPC framing.
            System.err.print(line);
        }
    }
}
