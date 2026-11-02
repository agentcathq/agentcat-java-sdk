package com.agentcat.sdk.e2e;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe record of what customer tool handlers actually received,
 * in call order — after AgentCat's inner tap stripped injected params.
 *
 * <p>Java analogue of the TypeScript {@code ToolSink} / {@code SinkEntry} types.
 * The sink is the strip-proof channel: {@code session_id}, {@code context},
 * and {@code agent_id} must NOT appear in recorded args.
 */
public final class ToolSink {

    /** A single recorded invocation. */
    public record Entry(String tool, Map<String, Object> args) {}

    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

    /** Records an invocation (called from inside the tool handler). */
    public void record(String tool, Map<String, Object> args) {
        entries.add(new Entry(tool, Map.copyOf(args)));
    }

    /** Returns all recorded entries in arrival order. */
    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /** Returns entries matching the given tool name. */
    public List<Entry> filter(String tool) {
        return entries.stream()
                .filter(e -> tool.equals(e.tool()))
                .toList();
    }

    /** Number of recorded invocations across all tools. */
    public int size() {
        return entries.size();
    }
}
