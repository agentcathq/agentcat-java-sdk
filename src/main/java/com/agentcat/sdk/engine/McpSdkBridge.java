package com.agentcat.sdk.engine;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

/**
 * Version-agnostic bridge over {@code McpSchema.Tool} API.
 *
 * <p>The MCP Java SDK changed the type of {@code Tool.inputSchema()} between major versions:
 * <ul>
 *   <li><b>1.x</b> — returns {@code McpSchema.JsonSchema} (a typed record)</li>
 *   <li><b>2.x</b> — returns {@code Map<String,Object>} (raw JSON map)</li>
 * </ul>
 * The builder signature for {@code McpSchema.Tool} also differs:
 * <ul>
 *   <li><b>1.x</b> — {@code Tool.builder()} (no-arg), then {@code .name(...).inputSchema(JsonSchema)}</li>
 *   <li><b>2.x</b> — {@code Tool.builder(name, Map)} or {@code Tool.builder(name)}</li>
 * </ul>
 *
 * <p>All callers ({@link com.agentcat.sdk.modules.handles.ParameterInjector},
 * {@link com.agentcat.sdk.tools.GetMoreTools}) go through this interface.
 * The correct implementation is selected once at startup by {@link McpSdkDetector}.
 */
public interface McpSdkBridge {

    /**
     * Returns the tool's input schema as a {@code Map<String,Object>}.
     * On 1.x, converts {@code McpSchema.JsonSchema} to a map; on 2.x, casts directly.
     */
    Map<String, Object> getInputSchema(McpSchema.Tool tool);

    /**
     * Rebuilds {@code original} with a new input schema map, preserving all other fields
     * (name, description, title, outputSchema, annotations, meta).
     */
    McpSchema.Tool rebuildTool(McpSchema.Tool original, Map<String, Object> newInputSchema);

    /**
     * Rebuilds {@code original} with a new output schema map, preserving all other fields
     * (name, description, title, inputSchema, annotations, meta).
     */
    McpSchema.Tool withOutputSchema(McpSchema.Tool original, Map<String, Object> newOutputSchema);

    /**
     * Builds a new {@link McpSchema.Tool} from scratch.
     *
     * @param name        tool name
     * @param description tool description
     * @param inputSchema input schema as a plain map
     * @param annotations optional tool annotations (may be {@code null})
     */
    McpSchema.Tool buildTool(String name, String description,
                              Map<String, Object> inputSchema,
                              McpSchema.ToolAnnotations annotations);
}
