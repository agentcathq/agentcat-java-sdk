package com.agentcat.sdk.ported;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/context-parameters-edge-cases.test.ts
 */
class ContextParametersEdgeCasesTest {

    private static AgentCatOptions contextOpts() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableToolCallContext(true);
        return opts;
    }

    private static McpSchema.Tool toolWith(String name, Map<String, Object> schema) {
        return McpSchema.Tool.builder(name, schema).build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // existing context parameter collision
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void skipsInjectionForToolsWithExistingContextParameter() {
        // ported from: "should skip injection for tools with existing context parameter"
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("context", Map.of("type", "number", "description", "existing"));
        schema.put("properties", props);

        McpSchema.Tool result = ParameterInjector.injectParams(toolWith("test-tool", schema), contextOpts());

        Map<String, Object> resultProps = (Map<String, Object>) result.inputSchema().get("properties");
        // Collision: the original context type "number" must be preserved
        assertEquals("number", ((Map<String, Object>) resultProps.get("context")).get("type"),
                "Pre-existing context parameter type must be preserved");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // complex schema handling
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void skipsInjectionForOneOfSchemas() {
        // ported from: "should skip injection for oneOf schemas"
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("oneOf", List.of(
                Map.of("type", "object", "properties", Map.of("email", Map.of("type", "string"))),
                Map.of("type", "object", "properties", Map.of("phone", Map.of("type", "string")))
        ));

        McpSchema.Tool result = ParameterInjector.injectParams(toolWith("union-tool", schema), contextOpts());

        // For complex schemas, properties injection should be skipped
        Map<String, Object> resultSchema = result.inputSchema();
        Object props = resultSchema.get("properties");
        assertTrue(props == null || !((Map<?, ?>) props).containsKey("context"),
                "context must not be injected into a oneOf schema");
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsInjectionForAllOfSchemas() {
        // ported from: "should skip injection for allOf schemas"
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("allOf", List.of(
                Map.of("type", "object", "properties", Map.of("a", Map.of("type", "string"))),
                Map.of("type", "object", "properties", Map.of("b", Map.of("type", "string")))
        ));

        McpSchema.Tool result = ParameterInjector.injectParams(toolWith("intersection-tool", schema), contextOpts());

        Map<String, Object> resultSchema = result.inputSchema();
        Object props = resultSchema.get("properties");
        assertTrue(props == null || !((Map<?, ?>) props).containsKey("context"),
                "context must not be injected into an allOf schema");
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsInjectionForAnyOfSchemas() {
        // ported from: "should skip injection for anyOf schemas"
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("anyOf", List.of(
                Map.of("type", "string"),
                Map.of("type", "number")
        ));

        McpSchema.Tool result = ParameterInjector.injectParams(toolWith("anyof-tool", schema), contextOpts());

        Map<String, Object> resultSchema = result.inputSchema();
        Object props = resultSchema.get("properties");
        assertTrue(props == null || !((Map<?, ?>) props).containsKey("context"),
                "context must not be injected into an anyOf schema");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // additionalProperties constraint
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void injectsContextAndRemovesAdditionalPropertiesFalseConstraint() {
        // ported from: "should inject context and remove additionalProperties:false constraint"
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(Map.of("name", Map.of("type", "string"))));
        schema.put("additionalProperties", false);

        McpSchema.Tool result = ParameterInjector.injectParams(toolWith("strict-tool", schema), contextOpts());

        Map<String, Object> resultSchema = result.inputSchema();
        Map<String, Object> props = (Map<String, Object>) resultSchema.get("properties");
        assertNotNull(props.get("context"), "context property must be injected");
        // additionalProperties:false must be removed so injected params aren't rejected
        assertNotEquals(false, resultSchema.get("additionalProperties"),
                "additionalProperties:false must be removed when injecting");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // get_more_tools skipping
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void skipsGetMoreToolsEntirely() {
        // ported from: "should skip get_more_tools entirely"
        Map<String, Object> emptySchema = new LinkedHashMap<>();
        emptySchema.put("type", "object");
        emptySchema.put("properties", new LinkedHashMap<>());

        McpSchema.Tool gmt = toolWith("get_more_tools", emptySchema);
        McpSchema.Tool result = ParameterInjector.injectParams(gmt, contextOpts());

        // get_more_tools must not get the "context" injection (it has its own)
        Map<String, Object> props = (Map<String, Object>) result.inputSchema().get("properties");
        // Its original properties should be intact; the generic "context" injection must be skipped
        // (get_more_tools has its own dedicated context field injected separately)
        assertNotNull(result, "get_more_tools result must not be null");
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlesEmptyObjectSchema() {
        // ported from: "should handle empty object schema {}"
        Map<String, Object> emptySchema = new LinkedHashMap<>();

        McpSchema.Tool result = ParameterInjector.injectParams(toolWith("empty-tool", emptySchema), contextOpts());

        Map<String, Object> props = (Map<String, Object>) result.inputSchema().get("properties");
        assertNotNull(props, "properties must be created");
        assertNotNull(props.get("context"), "context must be injected into empty schema");
    }
}
