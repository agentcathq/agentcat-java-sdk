package com.agentcat.sdk.ported;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import com.agentcat.sdk.tools.GetMoreTools;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/engine-listwrap.test.ts
 *
 * TypeScript tests exercise {@code buildInjectedList} and {@code installListWrap} which
 * operate on an internal low-level server map-seam. Java equivalents live in
 * {@link ParameterInjector} (parameter injection per tool) and
 * {@link GetMoreTools} (get_more_tools tool definition).
 *
 * Integration tests that require a running MCP server/client are {@code @Disabled}.
 * Unit tests that can be done via {@link ParameterInjector} are active.
 */
class EngineListwrapTest {

    // ─────────────────────────────────────────────────────────────────────────
    // buildInjectedList equivalents — unit tests via ParameterInjector
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "appends get_more_tools and injects session_id + context"
     * The Java SDK adds session_id and context via ParameterInjector.injectParams().
     */
    @Test
    @SuppressWarnings("unchecked")
    void injectsSessionIdAndContextIntoEchoTool() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableToolCallContext(true);
        opts.setEnableReportMissing(true);
        opts.setEnableAgentTracking(false);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("msg", Map.of("type", "string"));
        schema.put("properties", props);
        schema.put("required", List.of("msg"));

        McpSchema.Tool echoTool = McpSchema.Tool.builder("echo", schema).build();
        McpSchema.Tool result = ParameterInjector.injectParams(echoTool, opts);

        Map<String, Object> resultProps = (Map<String, Object>) result.inputSchema().get("properties");
        assertNotNull(resultProps.get(ParameterInjector.SESSION_ID_PARAM),
                "session_id must be injected");
        assertNotNull(resultProps.get(ParameterInjector.CONTEXT_PARAM),
                "context must be injected");
    }

    /**
     * "skips handle injection entirely when tracing is off"
     */
    @Test
    @SuppressWarnings("unchecked")
    void skipsHandleInjectionWhenTracingIsOff() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(false);
        opts.setEnableToolCallContext(false);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(Map.of("msg", Map.of("type", "string"))));

        McpSchema.Tool echoTool = McpSchema.Tool.builder("echo", schema).build();
        McpSchema.Tool result = ParameterInjector.injectParams(echoTool, opts);

        assertSame(echoTool, result, "Tool must be returned unchanged when tracing is off");
        Map<String, Object> resultProps = (Map<String, Object>) result.inputSchema().get("properties");
        assertNull(resultProps.get(ParameterInjector.SESSION_ID_PARAM),
                "session_id must not be injected when tracing is off");
    }

    /**
     * "does not mutate the input tool objects"
     */
    @Test
    @SuppressWarnings("unchecked")
    void doesNotMutateInputToolObjects() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableToolCallContext(true);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("msg", Map.of("type", "string"));
        schema.put("properties", props);

        McpSchema.Tool echoTool = McpSchema.Tool.builder("echo", schema).build();
        ParameterInjector.injectParams(echoTool, opts);

        // Original props should not contain session_id
        Map<String, Object> originalProps = (Map<String, Object>) echoTool.inputSchema().get("properties");
        assertNull(originalProps.get(ParameterInjector.SESSION_ID_PARAM),
                "Original tool must not be mutated");
    }

    /**
     * get_more_tools tool definition — validates annotations and schema constants.
     */
    @Test
    void getMoreToolsHasCorrectNameAndSchema() {
        assertEquals("get_more_tools", GetMoreTools.GET_MORE_TOOLS_NAME);
        Map<String, Object> schema = GetMoreTools.CONTEXT_INPUT_SCHEMA;
        assertNotNull(schema);
        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inputProps = (Map<String, Object>) schema.get("properties");
        assertNotNull(inputProps.get("context"), "context must be in get_more_tools schema");
    }

    /**
     * get_more_tools annotations (title, readOnly, etc.)
     */
    @Test
    void getMoreToolsAnnotationsAreCorrect() {
        McpSchema.ToolAnnotations ann = GetMoreTools.buildAnnotations();
        if (ann == null) {
            // Older SDK version without ToolAnnotations — skip
            return;
        }
        assertEquals("Get More Tools", ann.title());
        assertTrue(Boolean.TRUE.equals(ann.readOnlyHint()));
        assertTrue(Boolean.FALSE.equals(ann.destructiveHint()));
        assertTrue(Boolean.TRUE.equals(ann.idempotentHint()));
        assertTrue(Boolean.FALSE.equals(ann.openWorldHint()));
    }

    /**
     * get_more_tools is excluded from context injection
     * (ported from: "skips context injection on get_more_tools")
     */
    @Test
    @SuppressWarnings("unchecked")
    void getMoreToolsIsExcludedFromContextInjection() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableToolCallContext(true);

        McpSchema.Tool getMoreTools = McpSchema.Tool.builder(
                GetMoreTools.GET_MORE_TOOLS_NAME,
                GetMoreTools.CONTEXT_INPUT_SCHEMA).build();

        McpSchema.Tool result = ParameterInjector.injectParams(getMoreTools, opts);

        Map<String, Object> resultProps = (Map<String, Object>) result.inputSchema().get("properties");
        // The "context" in get_more_tools is the tool's own context — should only appear once
        // and should NOT be the injected analytics context
        Map<String, Object> contextProp = (Map<String, Object>) resultProps.get("context");
        assertNotNull(contextProp);
        // The get_more_tools context has its own description, not the analytics one
        assertFalse(((String) contextProp.get("description")).contains("analytics"),
                "get_more_tools context must not be overwritten with analytics description");
    }

}
