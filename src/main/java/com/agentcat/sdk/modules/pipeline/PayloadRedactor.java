package com.agentcat.sdk.modules.pipeline;

import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Applies an async string-level redaction function to every string value in a
 * nested {@code Map} structure (recursively covering nested maps and lists).
 *
 * <p>All string futures are fired <em>in parallel</em> (matching the TypeScript
 * {@code Promise.all} pattern), then awaited together within {@code timeoutMs}.
 * Strings whose future times out or throws are left unchanged.
 *
 * <p>This class is stateless and thread-safe. Input maps are never mutated.
 */
public final class PayloadRedactor {

    private PayloadRedactor() {}

    private static final long DEFAULT_TIMEOUT_MS = 5_000L;

    /**
     * Convenience overload using the default 5-second timeout.
     *
     * @see #apply(Map, Function, long)
     */
    public static Map<String, Object> apply(Map<String, Object> map,
                                             Function<String, CompletableFuture<String>> redact) throws Exception {
        return apply(map, redact, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Applies {@code redact} to a single string value.
     * Throws on timeout or any other hook error — the caller must drop the event.
     *
     * <p>Used to redact scalar fields like {@code userIntent} that sit outside the
     * params/response payload maps.
     */
    public static String applyToString(String value,
                                        Function<String, CompletableFuture<String>> redact,
                                        long timeoutMs) throws Exception {
        if (value == null) return null;
        try {
            CompletableFuture<String> f = redact.apply(value);
            if (f == null) return value;
            String result = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            return result != null ? result : value;
        } catch (TimeoutException e) {
            // Fail closed: an unredacted original must never reach the API.
            throw new TimeoutException("redactSensitiveInformation hook timed out after " + timeoutMs + " ms");
        }
        // Any other exception propagates: caller must drop the event
    }

    /**
     * Returns a copy of {@code map} with every string value replaced by the result
     * of {@code redact}. On timeout or any other hook error, throws — the caller must drop the event
     * (fail closed: an unredacted original never reaches the API).
     *
     * @param map       the payload to redact; returned unchanged if {@code null} or empty
     * @param redact    async redaction function supplied by the customer hook
     * @param timeoutMs maximum milliseconds to wait for all futures collectively
     * @return a redacted copy of {@code map}, never {@code null} if input is non-null
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> apply(Map<String, Object> map,
                                             Function<String, CompletableFuture<String>> redact,
                                             long timeoutMs) throws Exception {
        if (map == null) return null;

        List<String> originals = new ArrayList<>();
        List<CompletableFuture<String>> futures = new ArrayList<>();
        collectStringFutures(map, originals, futures, redact);

        if (futures.isEmpty()) return map;

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Fail closed: an unredacted original must never reach the API.
            throw new TimeoutException("redactSensitiveInformation hook timed out after " + timeoutMs + " ms");
        }
        // Non-timeout exceptions propagate: caller must drop the event

        AtomicInteger idx = new AtomicInteger(0);
        return rebuildMap(map, futures, originals, idx);
    }

    private static void collectStringFutures(Object value,
                                              List<String> originals,
                                              List<CompletableFuture<String>> futures,
                                              Function<String, CompletableFuture<String>> redact) throws Exception {
        if (value instanceof String s) {
            originals.add(s);
            CompletableFuture<String> f = redact.apply(s);
            futures.add(f != null ? f : CompletableFuture.completedFuture(s));
        } else if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) collectStringFutures(v, originals, futures, redact);
        } else if (value instanceof List<?> list) {
            for (Object v : list) collectStringFutures(v, originals, futures, redact);
        }
    }

    private static Map<String, Object> rebuildMap(Map<?, ?> map,
                                                   List<CompletableFuture<String>> futures,
                                                   List<String> originals,
                                                   AtomicInteger idx) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()),
                    rebuildValue(entry.getValue(), futures, originals, idx));
        }
        return out;
    }

    private static List<Object> rebuildList(List<?> list,
                                             List<CompletableFuture<String>> futures,
                                             List<String> originals,
                                             AtomicInteger idx) throws Exception {
        List<Object> out = new ArrayList<>(list.size());
        for (Object v : list) out.add(rebuildValue(v, futures, originals, idx));
        return out;
    }

    // Used when all futures completed successfully (no timeout).
    // Re-throws if any future completed exceptionally — caller drops the event.
    private static String rebuildString(String s,
                                         List<CompletableFuture<String>> futures,
                                         List<String> originals,
                                         AtomicInteger idx) throws Exception {
        int i = idx.getAndIncrement();
        String resolved = futures.get(i).get(); // throws on exceptional completion
        return resolved != null ? resolved : originals.get(i);
    }

    private static Object rebuildValue(Object value,
                                        List<CompletableFuture<String>> futures,
                                        List<String> originals,
                                        AtomicInteger idx) throws Exception {
        if (value instanceof String s)   return rebuildString(s, futures, originals, idx);
        if (value instanceof Map<?, ?> m) return rebuildMap(m, futures, originals, idx);
        if (value instanceof List<?> l)  return rebuildList(l, futures, originals, idx);
        return value;
    }
}
