package com.agentcat.sdk.modules.serverinfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Runs the customer's text redaction function on every string value of the title, the
 * instructions and the lists. The {@code name}, {@code uri} and {@code uriTemplate} of a list
 * item stay as they are, and no key ever changes.
 *
 * <p>The redaction fails closed: when the function throws, gives no string, or passes its
 * time limit, {@link #apply} throws and the heartbeat sends nothing.
 */
final class ServerInfoRedaction {

    static final long CALL_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final Set<String> NAME_KEYS = Set.of("name", "uri", "uriTemplate");

    private final Function<String, CompletableFuture<String>> redact;
    private final List<CompletableFuture<String>> calls = new ArrayList<>();
    private int next;

    private ServerInfoRedaction(Function<String, CompletableFuture<String>> redact) {
        this.redact = redact;
    }

    record Redacted(String title, String instructions, Map<String, Object> features) {}

    /**
     * @param deadlineNanos the {@link System#nanoTime()} at which the whole build stops
     * @throws Exception when the function fails, gives no string, or passes its time limit
     */
    static Redacted apply(String title, String instructions, Map<String, Object> features,
                          Function<String, CompletableFuture<String>> redact, long deadlineNanos) throws Exception {
        ServerInfoRedaction walk = new ServerInfoRedaction(redact);
        walk.call(title);
        walk.call(instructions);
        walk.callFeatures(features);

        long left = Math.min(CALL_LIMIT_NANOS, deadlineNanos - System.nanoTime());
        CompletableFuture.allOf(walk.calls.toArray(new CompletableFuture<?>[0])).get(left, TimeUnit.NANOSECONDS);

        return new Redacted(walk.take(), walk.take(), walk.rebuildFeatures(features));
    }

    // ── First pass: one call for each string, in walk order ───────────────────

    private void call(String text) {
        CompletableFuture<String> future = redact.apply(text);
        if (future == null) throw new IllegalStateException("the redaction function gave no future");
        calls.add(future);
    }

    private void callFeatures(Map<String, Object> features) {
        for (Object list : features.values()) {
            for (Object item : (List<?>) list) {
                for (Map.Entry<?, ?> field : ((Map<?, ?>) item).entrySet()) {
                    if (!NAME_KEYS.contains(String.valueOf(field.getKey()))) callAll(field.getValue());
                }
            }
        }
    }

    private void callAll(Object value) {
        if (value instanceof String text) {
            call(text);
        } else if (value instanceof Map<?, ?> map) {
            for (Object child : map.values()) callAll(child);
        } else if (value instanceof List<?> list) {
            for (Object child : list) callAll(child);
        }
    }

    // ── Second pass: the same walk, taking the results in order ───────────────

    private String take() {
        String result = calls.get(next++).join();
        if (result == null) throw new IllegalStateException("the redaction function gave no string");
        return result;
    }

    private Map<String, Object> rebuildFeatures(Map<String, Object> features) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> list : features.entrySet()) {
            List<Object> items = new ArrayList<>();
            for (Object item : (List<?>) list.getValue()) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> field : ((Map<?, ?>) item).entrySet()) {
                    String key = String.valueOf(field.getKey());
                    copy.put(key, NAME_KEYS.contains(key) ? field.getValue() : rebuild(field.getValue()));
                }
                items.add(copy);
            }
            out.put(list.getKey(), items);
        }
        return out;
    }

    private Object rebuild(Object value) {
        if (value instanceof String) return take();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) copy.put(String.valueOf(e.getKey()), rebuild(e.getValue()));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object child : list) copy.add(rebuild(child));
            return copy;
        }
        return value;
    }
}
