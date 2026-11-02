package com.agentcat.sdk.modules.events;

import com.agentcat.sdk.modules.pipeline.JsonTree;

import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;
import com.agentcat.sdk.model.EventData;
import com.agentcat.sdk.model.PendingHooks;
import com.agentcat.sdk.model.QueuedEvent;
import com.agentcat.sdk.model.ServerContext;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a raw tool call into a {@link QueuedEvent} and hands it off to
 * {@link EventQueue} for asynchronous processing.
 *
 * <p>All methods are fire-and-forget: serialisation errors are logged and
 * swallowed so the caller's response path is never affected.
 */
public final class EventPublisher {

    private EventPublisher() {}

    /**
     * Submits a tool call event to the {@link EventQueue}. Returns immediately.
     *
     * @param data          core event data (tool name, params, response, timing)
     * @param serverContext MCP server and connecting client identifiers
     * @param pendingHooks  async hook futures and redaction functions
     */
    public static void publishEvent(EventData data,
                                    ServerContext serverContext,
                                    PendingHooks pendingHooks) {
        publishEvent(data, serverContext, pendingHooks, null);
    }

    /**
     * Same as {@link #publishEvent(EventData, ServerContext, PendingHooks)}, with the
     * fully qualified class name of the thrown exception for {@code error.type}.
     */
    public static void publishEvent(EventData data,
                                    ServerContext serverContext,
                                    PendingHooks pendingHooks,
                                    String errorType) {
        try {
            QueuedEvent event = new QueuedEvent(data, serverContext, pendingHooks);
            event.setErrorType(errorType);
            EventQueue.getInstance().submit(event);
        } catch (Exception e) {
            writeToLog("publishEvent failed: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Converts a {@link McpSchema.CallToolResult} to a plain {@code Map<String,Object>}
     * suitable for serialisation and sanitisation. Returns {@code null} when
     * {@code result} is {@code null}.
     *
     * <p>Implemented without Jackson to avoid version conflicts between
     * MCP SDK versions (0.18.x uses com.fasterxml.jackson 2.x, 2.x uses tools.jackson 3.x).
     */
    public static Map<String, Object> toMap(McpSchema.CallToolResult result) {
        if (result == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        if (result.content() != null) {
            List<Map<String, Object>> contentList = result.content().stream()
                    .map(EventPublisher::contentToMap)
                    .toList();
            map.put("content", contentList);
        }
        if (result.isError() != null) map.put("isError", result.isError());
        // Object-typed structuredContent (MCP 2.x) becomes the JSON tree the client got,
        // so redaction and truncation see every string in it.
        if (result.structuredContent() != null) map.put("structuredContent", JsonTree.toTree(result.structuredContent()));
        return map;
    }

    private static Map<String, Object> contentToMap(McpSchema.Content content) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (content instanceof McpSchema.TextContent tc) {
            m.put("type", "text");
            if (tc.text() != null) m.put("text", tc.text());
        } else if (content instanceof McpSchema.ImageContent ic) {
            m.put("type", "image");
            if (ic.data() != null) m.put("data", ic.data());
            if (ic.mimeType() != null) m.put("mimeType", ic.mimeType());
        } else {
            // Audio, embedded resources, resource links: the JSON the client got. The
            // sanitizer then removes binary data, as in TS.
            Object tree = JsonTree.toTree(content);
            if (tree instanceof Map<?, ?> treeMap) {
                for (Map.Entry<?, ?> e : treeMap.entrySet()) m.put(String.valueOf(e.getKey()), e.getValue());
            }
            if (!(m.get("type") instanceof String)) {
                m.put("type", content.getClass().getSimpleName());
            }
        }
        return m;
    }
}
