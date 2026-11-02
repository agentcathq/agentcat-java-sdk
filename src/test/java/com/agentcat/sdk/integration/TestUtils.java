package com.agentcat.sdk.integration;

import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static helpers shared across all integration tests.
 */
public final class TestUtils {

    private TestUtils() {}

    // ── Session ID fixtures ───────────────────────────────────────────────────

    /**
     * Builds a valid 27-char session ID whose body is human-readable in failure messages.
     *
     * <pre>sid("fixed") → "ses_fixed000000000000000000000"</pre>
     */
    public static String sid(String label) {
        String body = (label.replaceAll("[^0-9A-Za-z]", "") + "0".repeat(27)).substring(0, 27);
        return "ses_" + body;
    }

    // ── Mint-back helpers ─────────────────────────────────────────────────────

    /**
     * Extracts the SDK-authored {@code [session_id …]} text block from a
     * {@code CallToolResult}. The block is prepended as the FIRST content element.
     *
     * @return the mint-back text, or {@code null} if absent
     */
    public static String mintBackOf(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return null;
        }
        Object first = result.content().get(0);
        if (first instanceof McpSchema.TextContent tc) {
            String text = tc.text();
            if (text != null && text.startsWith("[session_id")) {
                return text;
            }
        }
        return null;
    }

    /**
     * Reads {@code name: <value>} out of a mint-back block.
     *
     * <pre>handleFrom("[session_id issued: ses_abc…]", "session_id") → "ses_abc…"</pre>
     */
    public static String handleFrom(String mintBackText, String name) {
        Matcher m = Pattern.compile(name + ": (\\S+)").matcher(mintBackText);
        if (!m.find()) {
            throw new IllegalArgumentException(
                    "Handle '" + name + "' not found in mint-back: " + mintBackText);
        }
        return m.group(1);
    }

    // ── Port utilities ────────────────────────────────────────────────────────

    /** Finds a free TCP port on localhost. */
    public static int findFreePort() {
        try (ServerSocket s = new ServerSocket()) {
            s.bind(new InetSocketAddress(0));
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot find a free port", e);
        }
    }

    // ── CallToolResult helpers ────────────────────────────────────────────────

    /**
     * Extracts all {@code TextContent} items from a result as a list of strings,
     * excluding SDK-authored mint-back blocks.
     */
    public static List<String> textContents(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null) return List.of();
        return result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .filter(t -> t != null && !t.startsWith("[session_id"))
                .toList();
    }
}
