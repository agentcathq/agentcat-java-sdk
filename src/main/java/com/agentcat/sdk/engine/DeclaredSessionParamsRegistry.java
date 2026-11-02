package com.agentcat.sdk.engine;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks tool names whose input schema declares its own {@code session_id}
 * parameter — the customer's parameter, never ours to read.
 *
 * <p>This is the positive signal for ownership: membership only ever grows,
 * so a tool that once declared the name stays foreign until the process restarts.
 * The conservative direction, since the alternative is adopting a value that is
 * not ours.
 *
 * <p>Mirrors TypeScript SDK's {@code declaredSessionParams} in
 * {@code engine/registry.ts}.
 */
public final class DeclaredSessionParamsRegistry {

    private final Set<String> toolNames = ConcurrentHashMap.newKeySet();

    /** Records that {@code toolName} declares its own {@code session_id}. */
    public void add(String toolName) {
        toolNames.add(toolName);
    }

    /** Returns {@code true} if the tool declares its own {@code session_id}. */
    public boolean has(String toolName) {
        return toolNames.contains(toolName);
    }
}
