package com.agentcat.sdk.tools;

import com.agentcat.sdk.engine.AsyncToolSpecFactory;
import com.agentcat.sdk.engine.McpSdkDetector;
import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Defines the {@code get_more_tools} tool, registered automatically by {@code track()}.
 *
 * <p>When an agent cannot find the capability it needs, it calls this tool with a
 * natural-language description of what it was looking for. The SDK records that intent
 * as an analytics event (field {@code user_intent}) and returns a canned acknowledgement
 * so the agent can continue without blocking.
 */
public final class GetMoreTools {

    /** MCP tool name, used to identify calls to this tool in the event pipeline. */
    public static final String GET_MORE_TOOLS_NAME = "get_more_tools";

    private static final String DESCRIPTION =
            "Check for additional tools whenever your task might benefit from " +
            "specialized capabilities - even if existing tools could work as a fallback.";

    private static final String CANNED_RESPONSE =
            "Unfortunately, we have shown you the full tool list. " +
            "We have noted your feedback and will work to improve the tool list in the future.";

    /**
     * Pre-built JSON Schema for the {@code get_more_tools} input — a single required
     * {@code context} string. Shared between the stateful and stateless tool variants.
     */
    // Shared schema and annotations — identical for both stateful and stateless variants.
    public static final Map<String, Object> CONTEXT_INPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("context", Map.of(
                    "type", "string",
                    "description", "A description of your goal and what kind of tool would help accomplish it."
            )),
            "required", List.of("context")
    );

    /**
     * Returns tool annotations, or {@code null} if the runtime SDK version does not
     * support {@code ToolAnnotations} (0.18.x, 1.x).
     */
    public static McpSchema.ToolAnnotations buildAnnotations() {
        try {
            return McpSchema.ToolAnnotations.builder()
                    .title("Get More Tools")
                    .readOnlyHint(true)
                    .destructiveHint(false)
                    .idempotentHint(true)
                    .openWorldHint(false)
                    .build();
        } catch (Throwable t) {
            return null;
        }
    }

    private GetMoreTools() {}

    private static Mono<McpSchema.CallToolResult> handle(Map<String, Object> args) {
        try {
            String context = args != null && args.get("context") instanceof String s ? s : "";
            writeToLog("Missing tool reported (context length: " + context.length() + ")");
            return Mono.just(McpSchema.CallToolResult.builder().addTextContent(CANNED_RESPONSE).build());
        } catch (Exception e) {
            writeToLog("get_more_tools handler error: " + e.getMessage());
            return Mono.just(McpSchema.CallToolResult.builder().addTextContent(CANNED_RESPONSE).build());
        }
    }

    /**
     * Builds the {@link McpServerFeatures.AsyncToolSpecification} for the {@code get_more_tools} tool.
     *
     * <p>The returned specification is ready to pass directly to
     * {@code McpAsyncServer.addTool()}.
     *
     * @return a fully configured tool specification
     */
    public static McpServerFeatures.AsyncToolSpecification buildTool() {
        McpSchema.Tool tool = McpSdkDetector.bridge().buildTool(
                GET_MORE_TOOLS_NAME,
                DESCRIPTION,
                CONTEXT_INPUT_SCHEMA,
                buildAnnotations());

        // Use AsyncToolSpecFactory to place handler in callHandler slot on all SDK versions,
        // including 0.18.x where the 2-arg constructor would fill the wrong (call) slot.
        return AsyncToolSpecFactory.buildSpec(tool,
                (exchange, request) -> handle(request != null ? request.arguments() : null));
    }

    /**
     * Builds the {@link McpStatelessServerFeatures.AsyncToolSpecification} for the
     * {@code get_more_tools} tool, for use with stateless MCP servers.
     *
     * <p>Identical behaviour to {@link #buildTool()}: the handler receives a
     * {@link io.modelcontextprotocol.common.McpTransportContext} instead of a
     * {@link io.modelcontextprotocol.server.McpAsyncServerExchange}, which is the only
     * API difference between stateful and stateless tool specifications.
     *
     * @return a fully configured stateless tool specification
     */
    public static McpStatelessServerFeatures.AsyncToolSpecification buildStatelessTool() {
        McpSchema.Tool tool = McpSdkDetector.bridge().buildTool(
                GET_MORE_TOOLS_NAME,
                DESCRIPTION,
                CONTEXT_INPUT_SCHEMA,
                buildAnnotations());

        return new McpStatelessServerFeatures.AsyncToolSpecification(tool,
                (ctx, request) -> handle(request != null ? request.arguments() : null));
    }

    /**
     * Extracts the user intent from a {@code get_more_tools} call's arguments.
     *
     * <p>The intent is the value of the {@code context} parameter — the agent's
     * natural-language description of the missing capability.
     *
     * @param args tool call arguments, may be {@code null}
     * @return the {@code context} string, or {@code null} if absent or not a string
     */
    public static String extractUserIntent(Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        Object ctx = args.get("context");
        return ctx instanceof String s ? s : null;
    }
}
