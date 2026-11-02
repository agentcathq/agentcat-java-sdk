package com.agentcat.sdk.engine;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks exactly which AgentCat handle parameters were successfully injected
 * into each tool's input schema.
 *
 * <p>One registry per {@code track()} call (i.e. per server instance). Populated
 * by {@link com.agentcat.sdk.modules.handles.ParameterInjector} at tool-registration
 * time; consumed by {@link ToolCallWrapper#stripHandleArgs} at call time to ensure
 * only parameters that AgentCat actually injected are stripped — never customer-owned
 * parameters that happen to share a name.
 *
 * <p>Mirrors TypeScript SDK's {@code InjectedParamsRegistry} in
 * {@code modules/handle-injection.ts}.
 */
public final class InjectedParamsRegistry {

    private final Map<String, Set<String>> map = new ConcurrentHashMap<>();

    /**
     * Records that {@code toolName} went through injection, with no parameter injected yet.
     * Called once per tool registration, before any {@link #record}, so a tool where every
     * injection was skipped (name collision, composed schema, option off) still has an entry,
     * and the call-time strip removes nothing from it. Mirrors TS {@code listWrap.ts}, which
     * records an empty set for such tools. A re-registered tool starts from an empty set.
     */
    public void markRegistered(String toolName) {
        map.put(toolName, ConcurrentHashMap.newKeySet());
    }

    /**
     * Records that {@code paramName} was injected into {@code toolName}'s schema.
     * Safe to call from any thread.
     */
    public void record(String toolName, String paramName) {
        map.computeIfAbsent(toolName, k -> ConcurrentHashMap.newKeySet()).add(paramName);
    }

    /**
     * Returns the set of parameter names that were injected for {@code toolName},
     * or an empty set if the tool was never registered through this registry.
     */
    public Set<String> get(String toolName) {
        return map.getOrDefault(toolName, Collections.emptySet());
    }

    /**
     * Returns {@code true} if {@code toolName} went through injection (even with nothing injected).
     */
    public boolean has(String toolName) {
        return map.containsKey(toolName);
    }
}
