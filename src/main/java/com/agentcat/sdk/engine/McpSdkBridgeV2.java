package com.agentcat.sdk.engine;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

/**
 * {@link McpSdkBridge} implementation for MCP Java SDK 2.x.
 *
 * <p>Uses the public 2.x API directly — no reflection required.
 * {@code Tool.inputSchema()} returns {@code Map<String,Object>} in 2.x,
 * and {@code Tool.builder(name, map)} is the canonical factory method.
 */
final class McpSdkBridgeV2 implements McpSdkBridge {

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getInputSchema(McpSchema.Tool tool) {
        return (Map<String, Object>) tool.inputSchema();
    }

    @Override
    public McpSchema.Tool rebuildTool(McpSchema.Tool original, Map<String, Object> newInputSchema) {
        McpSchema.Tool.Builder b = McpSchema.Tool.builder(original.name(), newInputSchema)
                .description(original.description());
        if (original.title() != null)        b.title(original.title());
        if (original.outputSchema() != null) b.outputSchema(original.outputSchema());
        if (original.annotations() != null)  b.annotations(original.annotations());
        if (original.meta() != null)         b.meta(original.meta());
        return b.build();
    }

    @Override
    public McpSchema.Tool withOutputSchema(McpSchema.Tool original, Map<String, Object> newOutputSchema) {
        McpSchema.Tool.Builder b = McpSchema.Tool.builder(original.name(), (Map<String, Object>) original.inputSchema())
                .description(original.description())
                .outputSchema(newOutputSchema);
        if (original.title() != null)       b.title(original.title());
        if (original.annotations() != null) b.annotations(original.annotations());
        if (original.meta() != null)        b.meta(original.meta());
        return b.build();
    }

    @Override
    public McpSchema.Tool buildTool(String name, String description,
                                     Map<String, Object> inputSchema,
                                     McpSchema.ToolAnnotations annotations) {
        McpSchema.Tool.Builder b = McpSchema.Tool.builder(name, inputSchema)
                .description(description);
        if (annotations != null) b.annotations(annotations);
        return b.build();
    }
}
