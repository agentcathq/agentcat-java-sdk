package com.agentcat.sdk.engine;

import io.modelcontextprotocol.spec.McpSchema;

import java.lang.reflect.Method;
import java.util.Map;

import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;


/**
 * Detects the MCP Java SDK major version at runtime and provides the matching
 * {@link McpSdkBridge} singleton.
 *
 * <h2>Detection strategy</h2>
 * <p>The return type of {@code McpSchema.Tool.inputSchema()} differs between SDK majors:
 * <ul>
 *   <li><b>2.x</b> — returns {@code Map<String,Object>}</li>
 *   <li><b>1.x</b> — returns {@code McpSchema.JsonSchema}</li>
 * </ul>
 * Inspecting the method's return type via reflection gives a definitive answer
 * without depending on version strings or class presence.
 *
 * <p>The bridge is resolved once on class load and cached for the JVM lifetime.
 */
public final class McpSdkDetector {

    private static final McpSdkBridge BRIDGE = resolve();

    private McpSdkDetector() {}

    /** Returns the {@link McpSdkBridge} appropriate for the SDK version in the current classpath. */
    public static McpSdkBridge bridge() {
        return BRIDGE;
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private static McpSdkBridge resolve() {
        try {
            Method inputSchema = McpSchema.Tool.class.getMethod("inputSchema");
            if (inputSchema.getReturnType() == Map.class) {
                return new McpSdkBridgeV2();
            } else {
                return new McpSdkBridgeV1();
            }
        } catch (Exception e) {
            writeToLog("McpSdkDetector: detection failed (" + e.getMessage()
                    + "), defaulting to 2.x bridge");
            return new McpSdkBridgeV2();
        }
    }
}
