package com.agentcat.sdk.modules.handles;

import com.agentcat.sdk.AgentCatOptions;
import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;
import com.agentcat.sdk.model.HandleResolution;
import com.agentcat.sdk.model.SessionSource;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mint-back: appends the resolved {@code session_id} to the tool result so the
 * agent can read it and carry it forward on the next call.
 *
 * <h3>Text block (content prepend)</h3>
 * A human-readable block is inserted as the <em>first</em> content element —
 * placing it at the top guards against client-side truncation of long responses.
 * <ul>
 *   <li>{@link SessionSource#MINTED} — announces the newly issued {@code session_id}.</li>
 *   <li>{@link SessionSource#INVALID} — reports that the sent value was unrecognised.</li>
 *   <li>{@link SessionSource#SUPPLIED} — nothing added; session continuity is working.</li>
 * </ul>
 *
 * <h3>Structured mirror ({@code structuredContent._mcp_session})</h3>
 * When the tool result already carries a plain-object {@code structuredContent},
 * a machine-readable {@code _mcp_session} key is injected as its first entry.
 * This is skipped when {@code structuredContent} is absent (most tools) or is not
 * a plain object.
 *
 * <h3>Fault containment</h3>
 * Any error inside {@link #applyMintBack} is caught, logged, and the original
 * result is returned untouched — the customer's response is never withheld.
 */
public final class MintBackHandler {

    // ── Wire key ─────────────────────────────────────────────────────────────

    /** Key injected into {@code structuredContent} to carry session state. */
    public static final String MCP_SESSION_KEY = "mcp_session";

    // ── Agent-facing text (verbatim from TypeScript SDK constants.ts) ─────────

    private static final String HEADER_ISSUED =
            "[session_id issued — see this tool's session_id parameter description]";

    private static final String BODY_ISSUED =
            "This is the first-call issuance described in this tool's session_id parameter description.";

    private static final String HEADER_UNRECOGNIZED =
            "[session_id unrecognized — see this tool's session_id parameter description]";

    private static final String BODY_UNRECOGNIZED =
            "The value sent was not issued by this server. Re-send the session_id issued earlier " +
            "for this task; if none was issued yet, send start and one will be issued.";

    private MintBackHandler() {}

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Applies both text and structured mint-back to {@code result}, guarded by
     * fault containment. When tracing is disabled no mint-back is performed.
     *
     * @param result   the tool result returned by the user's handler
     * @param handles  resolved handle state for this call
     * @param opts     AgentCat runtime options
     * @return the decorated result, or the original on any error
     */
    public static McpSchema.CallToolResult applyMintBack(
            McpSchema.CallToolResult result,
            HandleResolution handles,
            AgentCatOptions opts) {
        return applyMintBack(result, handles, opts, null, null);
    }

    /**
     * Applies both text and structured mint-back to {@code result}, guarded by
     * fault containment. Structured mint-back is gated by the output injection
     * registry: only tools whose outputSchema was extended receive the
     * {@code mcp_session} mirror in {@code structuredContent}.
     *
     * @param result          the tool result returned by the user's handler
     * @param handles         resolved handle state for this call
     * @param opts            AgentCat runtime options
     * @param toolName        name of the tool (for output registry lookup); may be null
     * @param outputRegistry  output injection registry; may be null (fallback: always mirror)
     * @return the decorated result, or the original on any error
     */
    public static McpSchema.CallToolResult applyMintBack(
            McpSchema.CallToolResult result,
            HandleResolution handles,
            AgentCatOptions opts,
            String toolName,
            com.agentcat.sdk.engine.OutputInjectionRegistry outputRegistry) {

        if (!opts.isEnableTracing() || result == null) {
            return result;
        }

        try {
            String text = buildMintBackText(handles);
            McpSchema.CallToolResult withText = (text != null)
                    ? appendMintBack(result, text)
                    : result;

            // Structured mirror is gated by the output-injection registry:
            // only tools whose outputSchema was extended with mcp_session get it.
            // When registry is null (fallback), mirror anyway — mirrors TS behaviour:
            // "a missing registry rare (rebuild failed); in that case mirror anyway"
            boolean shouldMirror = outputRegistry == null
                    || (toolName != null && outputRegistry.has(toolName));

            if (shouldMirror) {
                Map<String, Object> structured = buildStructuredMintBack(handles);
                return (structured != null)
                        ? mirrorStructuredMintBack(withText, structured)
                        : withText;
            }
            return withText;

        } catch (Throwable e) {
            writeToLog("MintBackHandler error: " + e.getMessage());
            return result;
        }
    }

    // ── Text block ────────────────────────────────────────────────────────────

    /**
     * Builds the text block to prepend for {@code MINTED} and {@code INVALID} sessions.
     * Returns {@code null} when nothing needs to be announced ({@code SUPPLIED}).
     */
    public static String buildMintBackText(HandleResolution handles) {
        return switch (handles.getSessionSource()) {
            case MINTED -> HEADER_ISSUED + "\n"
                    + "session_id: " + handles.getSessionId() + "\n"
                    + BODY_ISSUED;
            case INVALID -> HEADER_UNRECOGNIZED + "\n"
                    + BODY_UNRECOGNIZED;
            default -> null; // SUPPLIED, HOOK_MODE, FOREIGN — nothing to announce
        };
    }

    /**
     * Prepends {@code text} as the first content element of {@code result}.
     * Never mutates the original; returns a shallow copy with the extra element.
     */
    public static McpSchema.CallToolResult appendMintBack(McpSchema.CallToolResult result, String text) {
        List<McpSchema.Content> existing = result.content() != null ? result.content() : List.of();
        List<McpSchema.Content> newContent = new ArrayList<>(existing.size() + 1);
        // Use constructor directly — TextContent.builder(String) was added only in SDK 2.x.
        newContent.add(new McpSchema.TextContent(text));
        newContent.addAll(existing);

        McpSchema.CallToolResult.Builder builder = McpSchema.CallToolResult.builder()
                .content(newContent)
                .isError(result.isError());
        if (result.structuredContent() != null) {
            builder.structuredContent(result.structuredContent());
        }
        if (result.meta() != null) {
            builder.meta(result.meta());
        }
        return builder.build();
    }

    // ── Structured mirror ─────────────────────────────────────────────────────

    /**
     * Builds the {@code mcp_session} payload for mirroring into {@code structuredContent}.
     * Returns {@code null} when the payload would be empty.
     *
     * <p>Mirrors TypeScript {@code buildStructuredMintBack} exactly:
     * <ul>
     *   <li>In hook mode: session fields are omitted; {@code agent_id} is still echoed when
     *       present. Returns {@code null} only if there is nothing to report.</li>
     *   <li>For {@code INVALID}: {@code session_id} is omitted (no replacement issued); status
     *       is still {@code "unrecognized"}.</li>
     *   <li>For {@code MINTED}/{@code SUPPLIED}: both {@code session_id} and {@code status}
     *       are included.</li>
     * </ul>
     */
    public static Map<String, Object> buildStructuredMintBack(HandleResolution handles) {
        boolean hookMode   = handles.getSessionSource() == SessionSource.HOOK_MODE;
        boolean sessionOurs = !hookMode && handles.getSessionSource() != SessionSource.FOREIGN;

        Map<String, Object> mint = new LinkedHashMap<>();

        // session_id: present when it's ours AND the call is not INVALID
        if (sessionOurs && handles.getSessionSource() != SessionSource.INVALID) {
            mint.put("session_id", handles.getSessionId());
        }

        // agent_id: echoed whenever the agent supplied one (even in hook mode)
        if (handles.getAgentId() != null) {
            mint.put("agent_id", handles.getAgentId());
        }

        // status: only when session tracking is ours
        if (sessionOurs) {
            String status = switch (handles.getSessionSource()) {
                case MINTED   -> "issued";
                case SUPPLIED -> "active";
                case INVALID  -> "unrecognized";
                default       -> "active";
            };
            mint.put("status", status);
        }

        return mint.isEmpty() ? null : mint;
    }

    /**
     * Mirrors {@code mint} into {@code result.structuredContent} as its first key,
     * only when {@code structuredContent} is a plain object and does not already
     * declare {@value #MCP_SESSION_KEY} (customer-owned key always wins).
     * Returns the original result unchanged when the preconditions are not met.
     */
    @SuppressWarnings("unchecked")
    public static McpSchema.CallToolResult mirrorStructuredMintBack(
            McpSchema.CallToolResult result,
            Map<String, Object> mint) {

        Object sc = result.structuredContent();
        if (!(sc instanceof Map)) {
            return result; // absent or not a plain object — skip
        }

        Map<String, Object> existing = (Map<String, Object>) sc;
        if (existing.containsKey(MCP_SESSION_KEY)) {
            return result; // customer owns this key — never overwrite
        }

        Map<String, Object> newSc = new LinkedHashMap<>();
        newSc.put(MCP_SESSION_KEY, mint);
        newSc.putAll(existing);

        McpSchema.CallToolResult.Builder builder = McpSchema.CallToolResult.builder()
                .content(result.content())
                .isError(result.isError())
                .structuredContent(newSc);
        if (result.meta() != null) {
            builder.meta(result.meta());
        }
        return builder.build();
    }
}
