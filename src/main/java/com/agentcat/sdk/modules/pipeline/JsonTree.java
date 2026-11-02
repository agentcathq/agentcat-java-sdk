package com.agentcat.sdk.modules.pipeline;

import io.modelcontextprotocol.json.McpJsonDefaults;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;

/**
 * Turns a value into a JSON tree: {@code Map}, {@code List}, {@code String},
 * {@code Number}, {@code Boolean} or {@code null}. Redaction, sanitization and
 * truncation walk only these types, so every value goes through this first.
 *
 * <p>A Java object (a record or bean as MCP 2.x {@code structuredContent}) becomes
 * the tree that the MCP SDK's own JSON mapper sends to the client
 * ({@link McpJsonDefaults#getMapper()}), so the event records what the client got.
 * If that fails, the object becomes its {@code toString()} text, which redaction
 * then also sees.
 */
public final class JsonTree {

    private JsonTree() {}

    public static Object toTree(Object value) {
        return toTree(value, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Object toTree(Object value, Set<Object> seen) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Character || value instanceof Enum<?>) {
            return value instanceof Enum<?> e ? e.name() : value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            if (!seen.add(map)) return "[Circular ~]";
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), toTree(e.getValue(), seen));
            }
            seen.remove(map);
            return out;
        }
        if (value instanceof Iterable<?> items) {
            if (!seen.add(items)) return "[Circular ~]";
            List<Object> out = new ArrayList<>();
            for (Object item : items) out.add(toTree(item, seen));
            seen.remove(items);
            return out;
        }
        // Jackson writes these two array types as strings, so the tree must too:
        // redaction then sees a char[] whole, and the sanitizer sees base64.
        if (value instanceof char[] chars) {
            return new String(chars);
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> out = new ArrayList<>(length);
            for (int i = 0; i < length; i++) out.add(toTree(Array.get(value, i), seen));
            return out;
        }
        try {
            Object tree = McpJsonDefaults.getMapper().convertValue(value, Object.class);
            if (tree == null || tree == value) return value.toString();
            return toTree(tree, seen);
        } catch (Throwable t) {
            writeToLog("JsonTree: could not convert " + value.getClass().getName()
                    + " with the MCP JSON mapper — recording its text: " + t);
            return value.toString();
        }
    }
}
