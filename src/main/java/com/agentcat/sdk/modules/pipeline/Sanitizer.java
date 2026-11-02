package com.agentcat.sdk.modules.pipeline;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sanitizes an event payload before it enters the truncation and send stages.
 *
 * <h3>What is removed</h3>
 * <ul>
 *   <li><b>Non-text content blocks</b> in {@code response.content}: {@code image},
 *       {@code audio}, binary {@code resource} (blob) are replaced with a short
 *       informational string so the event stays serialisable.</li>
 *   <li><b>Large base64 strings</b> (>10 KB) anywhere in {@code parameters} or
 *       {@code response.structuredContent}: replaced with {@code "[base64 content redacted]"}.</li>
 * </ul>
 *
 * <p>This class is stateless and thread-safe. All methods return new objects;
 * inputs are never mutated.
 */
public final class Sanitizer {

    /** Strings shorter than this are never tested for base64 (fast path). */
    private static final int BASE64_SIZE_GATE = 10_240; // 10 KB

    private static final Pattern BASE64_PATTERN =
            Pattern.compile("^[A-Za-z0-9+/\\n\\r]+=*$");

    private Sanitizer() {}

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns a sanitized copy of {@code params}. Only base64 large strings are
     * redacted; the structure is preserved.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> sanitizeParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return params;
        return (Map<String, Object>) sanitizeValue(params);
    }

    /**
     * Returns a sanitized copy of the serialised response map.
     * Content blocks are filtered; structuredContent is base64-scanned.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> sanitizeResponse(Map<String, Object> response) {
        if (response == null) return null;

        Map<String, Object> result = new LinkedHashMap<>(response);

        // Sanitize content blocks
        if (result.get("content") instanceof List<?> contentList) {
            List<Object> sanitized = new ArrayList<>(contentList.size());
            for (Object block : contentList) {
                sanitized.add(sanitizeContentBlock(block));
            }
            result.put("content", sanitized);
        }

        // Base64-scan structuredContent
        if (result.get("structuredContent") instanceof Map<?, ?> sc) {
            result.put("structuredContent", sanitizeValue(sc));
        }

        return result;
    }

    // ── Content block ─────────────────────────────────────────────────────────

    private static Object sanitizeContentBlock(Object block) {
        if (!(block instanceof Map<?, ?> map)) return block;

        Object type = map.get("type");
        if (!(type instanceof String typeStr)) return block;

        return switch (typeStr) {
            case "text"         -> block; // pass through as-is
            case "image"        -> textBlock("[image content redacted — not supported by AgentCat]");
            case "audio"        -> textBlock("[audio content redacted — not supported by AgentCat]");
            case "resource"     -> sanitizeResourceBlock(map);
            case "resource_link"-> block;
            default             -> textBlock("[unsupported content type \"" + typeStr + "\" redacted — not supported by AgentCat]");
        };
    }

    private static Object sanitizeResourceBlock(Map<?, ?> block) {
        Object resource = block.get("resource");
        if (resource instanceof Map<?, ?> r && r.containsKey("blob")) {
            return textBlock("[binary resource content redacted — not supported by AgentCat]");
        }
        return block;
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    // ── Base64 scanner ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Object sanitizeValue(Object value) {
        if (value instanceof String s) {
            return isLargeBase64(s) ? "[base64 content redacted]" : s;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(sanitizeValue(item));
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), sanitizeValue(entry.getValue()));
            }
            return out;
        }
        return value; // Number, Boolean, null — pass through
    }

    private static boolean isLargeBase64(String s) {
        if (s.length() < BASE64_SIZE_GATE) return false;
        String cleaned = s.replaceAll("[\\n\\r]", "");
        if (!BASE64_PATTERN.matcher(cleaned).matches()) return false;
        try {
            Base64.getDecoder().decode(cleaned);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
