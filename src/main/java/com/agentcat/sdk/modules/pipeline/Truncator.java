package com.agentcat.sdk.modules.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.IdentityHashMap;

/**
 * Truncates event payload fields so every event stays within the AgentCat
 * ingest limits.
 *
 * <h2>Limits</h2>
 * <ul>
 *   <li>Strings: truncated to {@value #MAX_STRING_LENGTH} characters.</li>
 *   <li>Object depth: values beyond {@value #MAX_DEPTH} levels become {@code "[Object]"}
 *       or {@code "[Array]"}.</li>
 *   <li>Object breadth: maps and lists are capped at {@value #MAX_BREADTH} entries.</li>
 * </ul>
 *
 * <p>Circular references are detected via identity tracking and replaced with
 * {@code "[Circular ~]"}.
 *
 * <p>In the current implementation the 100 KB total-event limit is enforced by
 * the caller (EventQueue) after JSON serialisation; this class handles per-value
 * normalisation only.
 */
public final class Truncator {

    /** Maximum recursion depth for {@link #normalize}; objects deeper than this become {@code "[Object]"}. */
    public static final int MAX_DEPTH         = 10;
    /** Maximum number of entries per map or list; excess entries are replaced with {@code "[MaxProperties ~]"}. */
    public static final int MAX_BREADTH       = 100;
    /** Maximum string length in the payload; longer strings are truncated with {@code "..."}. */
    public static final int MAX_STRING_LENGTH = 32_768; // 32 KB
    /** Maximum combined byte size of params + response; enforced by {@link #enforceEventSize}. */
    public static final int MAX_EVENT_BYTES   = 102_400; // 100 KB

    /** Maximum length for {@code userIntent} and {@code error.message} fields. */
    public static final int MAX_USER_INTENT_LENGTH = 2_048;
    /** Maximum length for name/version fields: toolName, serverName, serverVersion. */
    public static final int MAX_FIELD_NAME_LENGTH  = 256;

    private static final String TRUNCATION_SUFFIX = "...";

    private Truncator() {}

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Truncates a single string field to at most {@code maxLength} characters,
     * appending {@code "..."} when truncated. Returns {@code null} unchanged.
     *
     * <p>Use this for named event fields (e.g. {@code userIntent}, {@code toolName},
     * {@code serverName}) that carry fixed limits independent of the 32 KB per-string limit
     * applied by {@link #normalize}.
     *
     * @param value     the string to truncate, or {@code null}
     * @param maxLength maximum number of characters to keep before appending {@code "..."}
     * @return the (possibly truncated) string, or {@code null} if input was {@code null}
     */
    public static String truncateField(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + TRUNCATION_SUFFIX;
    }

    /**
     * Normalises {@code value} recursively, applying depth, breadth, and string limits.
     * Returns a new object; the input is never mutated.
     */
    public static Object normalize(Object value) {
        return visit(value, MAX_DEPTH, MAX_BREADTH, newSeen());
    }

    /**
     * Normalises {@code value} with a custom depth limit.
     * Mirrors TypeScript {@code normalize(value, maxDepth)}.
     */
    public static Object normalize(Object value, int maxDepth) {
        return visit(value, maxDepth, MAX_BREADTH, newSeen());
    }

    /**
     * Normalises {@code value} with custom depth and breadth limits.
     * Mirrors TypeScript {@code normalize(value, maxDepth, maxBreadth)}.
     */
    public static Object normalize(Object value, int maxDepth, int maxBreadth) {
        return visit(value, maxDepth, maxBreadth, newSeen());
    }

    /**
     * Normalises a top-level map (e.g. params or response).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizeMap(Map<String, Object> map) {
        if (map == null) return null;
        Object result = visit(map, MAX_DEPTH, MAX_BREADTH, newSeen());
        return result instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    /**
     * Enforces the 100 KB total event size limit on the combined {@code params} and
     * {@code response} maps by progressively reducing the depth limit until the payload fits.
     *
     * <p>This is called <em>after</em> {@link #normalizeMap} has already applied the
     * standard depth/breadth/string limits. If the combined JSON is still over 100 KB,
     * the depth is reduced by 1 and both maps are re-normalised — repeated until the
     * payload fits or depth reaches 0.
     *
     * @param params   already-normalised params map (may be {@code null})
     * @param response already-normalised response map (may be {@code null})
     * @return a two-element array {@code [params, response]} that together fit in 100 KB
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object>[] enforceEventSize(
            Map<String, Object> params, Map<String, Object> response) {

        int depth = MAX_DEPTH;
        while (depth >= 0) {
            int totalBytes = jsonBytes(params) + jsonBytes(response);
            if (totalBytes <= MAX_EVENT_BYTES) {
                break;
            }
            depth--;
            if (depth < 0) break;
            params   = params   != null ? shrink(params,   depth) : null;
            response = response != null ? shrink(response, depth) : null;
        }
        return new Map[] { params, response };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shrink(Map<String, Object> map, int depth) {
        if (depth <= 0) return new LinkedHashMap<>();
        Object result = visit(map, depth, MAX_BREADTH, newSeen());
        return result instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    private static int jsonBytes(Map<String, Object> map) {
        if (map == null) return 0;
        StringBuilder sb = new StringBuilder();
        appendJson(sb, map);
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /** Minimal JSON serializer — handles Map, List, String, Number, Boolean, null. */
    @SuppressWarnings("unchecked")
    private static void appendJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else if (value instanceof String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"'  -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default   -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                                   else sb.append(c); }
                }
            }
            sb.append('"');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                appendJson(sb, list.get(i));
            }
            sb.append(']');
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                appendJson(sb, e.getKey().toString());
                sb.append(':');
                appendJson(sb, e.getValue());
            }
            sb.append('}');
        } else {
            appendJson(sb, value.toString());
        }
    }

    // ── Core visitor ─────────────────────────────────────────────────────────

    private static Object visit(Object value, int depth, int maxBreadth, Set<Object> seen) {
        if (value == null)           return null;
        if (value instanceof Boolean) return value;
        if (value instanceof Number)  return value;

        if (value instanceof String s) {
            if (s.length() > MAX_STRING_LENGTH) {
                return s.substring(0, MAX_STRING_LENGTH) + TRUNCATION_SUFFIX;
            }
            return s;
        }

        if (value instanceof List<?> list) {
            if (seen.contains(list)) return "[Circular ~]";
            if (depth <= 0)          return "[Array]";
            seen.add(list);

            int limit = Math.min(list.size(), maxBreadth);
            List<Object> out = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                out.add(visit(list.get(i), depth - 1, maxBreadth, seen));
            }
            if (list.size() > maxBreadth) {
                out.add("[MaxProperties ~]");
            }
            seen.remove(list);
            return out;
        }

        if (value instanceof Map<?, ?> map) {
            if (seen.contains(map)) return "[Circular ~]";
            if (depth <= 0)         return "[Object]";
            seen.add(map);

            Map<String, Object> out = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count >= maxBreadth) {
                    out.put("...", "[MaxProperties ~]");
                    break;
                }
                out.put(String.valueOf(entry.getKey()),
                        visit(entry.getValue(), depth - 1, maxBreadth, seen));
                count++;
            }
            seen.remove(map);
            return out;
        }

        // Anything else (unlikely in a JSON-sourced map) — stringify
        return value.toString();
    }

    /** Identity-based seen set for circular reference detection. */
    private static Set<Object> newSeen() {
        return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
