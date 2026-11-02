package com.agentcat.sdk.engine;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks tool names whose {@code outputSchema} was successfully extended with
 * {@code mcp_session} by {@link com.agentcat.sdk.modules.handles.ParameterInjector}.
 *
 * <p>Used at call time to gate structured mint-back: only tools whose output
 * schema was extended should receive the {@code mcp_session} mirror in
 * {@code structuredContent}. Without this check, tools that have a
 * {@code structuredContent} but no declared {@code mcp_session} in their output
 * schema would get an unexpected key injected.
 *
 * <p>Mirrors TypeScript SDK's {@code OutputInjectionRegistry} in
 * {@code engine/registry.ts}.
 */
public final class OutputInjectionRegistry {

    private final Set<String> toolNames = ConcurrentHashMap.newKeySet();

    /** Records that {@code toolName}'s outputSchema was extended with mcp_session. */
    public void add(String toolName) {
        toolNames.add(toolName);
    }

    /** Returns {@code true} if the tool's outputSchema was extended. */
    public boolean has(String toolName) {
        return toolNames.contains(toolName);
    }
}
