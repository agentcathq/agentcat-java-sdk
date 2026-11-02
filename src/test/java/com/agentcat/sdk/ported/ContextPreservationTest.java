package com.agentcat.sdk.ported;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/context-preservation.test.ts
 */
class ContextPreservationTest {

    private static AgentCatOptions contextOpts() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableToolCallContext(true);
        return opts;
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesExistingContextParameterWithCustomDescription() {
        // ported from: "should preserve existing context parameter with custom description"
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("context", Map.of(
                "type", "string",
                "description", "A custom context description that should be preserved"
        ));
        inputSchema.put("properties", properties);
        inputSchema.put("required", new java.util.ArrayList<>(List.of("context")));

        McpSchema.Tool tool = McpSchema.Tool.builder("test-tool", inputSchema).build();
        McpSchema.Tool result = ParameterInjector.injectParams(tool, contextOpts());

        Map<String, Object> resultProps = (Map<String, Object>) result.inputSchema().get("properties");
        Map<String, Object> contextProp = (Map<String, Object>) resultProps.get("context");
        assertEquals("A custom context description that should be preserved",
                contextProp.get("description"),
                "Custom context description must be preserved");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addsContextParameterWhenItDoesntExist() {
        // ported from: "should add context parameter when it doesn't exist"
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("foo", Map.of("type", "string"));
        inputSchema.put("properties", properties);
        inputSchema.put("required", new java.util.ArrayList<>(List.of("foo")));

        McpSchema.Tool tool = McpSchema.Tool.builder("test-tool", inputSchema).build();
        McpSchema.Tool result = ParameterInjector.injectParams(tool, contextOpts());

        Map<String, Object> resultProps = (Map<String, Object>) result.inputSchema().get("properties");
        assertNotNull(resultProps.get("context"), "context must be added");
        Map<String, Object> contextProp = (Map<String, Object>) resultProps.get("context");
        assertNotNull(contextProp.get("description"),
                "context must have a description (the default)");
    }
}
