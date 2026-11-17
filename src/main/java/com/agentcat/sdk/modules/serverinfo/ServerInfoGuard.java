package com.agentcat.sdk.modules.serverinfo;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Decides on the request path whether a heartbeat is due. Its only state is in the memory of
 * the process: the time of the first check, and the time of the last attempt for each
 * (project ID, server name). It only limits bursts: when the memory is gone, the SDK sends again.
 */
final class ServerInfoGuard {

    static final long INTERVAL_MS = 3_600_000L;
    static final long MIN_MEMORY_AGE_MS = 60_000L;
    static final int MAX_KEYS = 1_000;

    private final LongSupplier clockMs;
    private final Map<String, Long> attempts = new HashMap<>();
    private boolean memoryStarted;
    private long memoryStartMs;

    ServerInfoGuard(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    /**
     * Records an attempt and returns {@code true} when a heartbeat is due. A memory younger than
     * {@value #MIN_MEMORY_AGE_MS} ms counts as no memory, so then only a feedback note starts one.
     */
    synchronized boolean tryStart(String projectId, String serverName, boolean feedbackNote) {
        long now = clockMs.getAsLong();
        if (!memoryStarted) {
            memoryStarted = true;
            memoryStartMs = now;
        }
        if (!feedbackNote && now - memoryStartMs < MIN_MEMORY_AGE_MS) return false;

        String key = projectId + '\u0000' + (serverName != null ? serverName : "");
        Long last = attempts.get(key);
        if (last != null && now - last < INTERVAL_MS) return false;
        if (last == null && attempts.size() >= MAX_KEYS) attempts.clear();
        attempts.put(key, now);
        return true;
    }

    synchronized void startMemoryAt(long ms) {
        memoryStarted = true;
        memoryStartMs = ms;
    }

    synchronized int keyCount() {
        return attempts.size();
    }
}
