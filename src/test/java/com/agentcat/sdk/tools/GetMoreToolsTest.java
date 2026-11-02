package com.agentcat.sdk.tools;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GetMoreTools}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>The wire-level tool name constant.</li>
 *   <li>{@link GetMoreTools#extractUserIntent} — all edge cases.</li>
 *   <li>The tool's input schema shape (type, required {@code context} field).</li>
 * </ul>
 */
class GetMoreToolsTest {

    // ── Tool name constant ────────────────────────────────────────────────────

    @Test
    void toolNameIsGetMoreTools() {
        assertEquals("get_more_tools", GetMoreTools.GET_MORE_TOOLS_NAME,
                "GetMoreTools.GET_MORE_TOOLS_NAME must be the exact wire-level string \"get_more_tools\"");
    }

    // ── extractUserIntent ─────────────────────────────────────────────────────

    @Test
    void extractsContextStringFromArgs() {
        Map<String, Object> args = Map.of("context", "I need a database query tool");
        assertEquals("I need a database query tool", GetMoreTools.extractUserIntent(args));
    }

    @Test
    void returnsNullForNullArgs() {
        assertNull(GetMoreTools.extractUserIntent(null),
                "null args must produce null intent");
    }

    @Test
    void returnsNullForEmptyArgs() {
        assertNull(GetMoreTools.extractUserIntent(Map.of()),
                "empty args map must produce null intent");
    }

    @Test
    void returnsNullWhenContextKeyMissing() {
        Map<String, Object> args = Map.of("other_key", "some value");
        assertNull(GetMoreTools.extractUserIntent(args),
                "missing context key must produce null intent");
    }

    @Test
    void returnsNullWhenContextValueIsNotString() {
        Map<String, Object> args = new HashMap<>();
        args.put("context", 42); // Integer, not String
        assertNull(GetMoreTools.extractUserIntent(args),
                "non-String context value must produce null intent");
    }

    @Test
    void returnsNullWhenContextValueIsNull() {
        Map<String, Object> args = new HashMap<>();
        args.put("context", null);
        assertNull(GetMoreTools.extractUserIntent(args),
                "null context value must produce null intent");
    }

    @Test
    void returnsEmptyStringWhenContextIsEmpty() {
        Map<String, Object> args = Map.of("context", "");
        assertEquals("", GetMoreTools.extractUserIntent(args),
                "empty string context must be returned as-is (not converted to null)");
    }

    @Test
    void preservesWhitespaceInContext() {
        String intent = "  find a  csv export tool  ";
        Map<String, Object> args = Map.of("context", intent);
        assertEquals(intent, GetMoreTools.extractUserIntent(args),
                "extractUserIntent must not trim whitespace from the context value");
    }

    // ── CONTEXT_INPUT_SCHEMA ──────────────────────────────────────────────────

    @Test
    void inputSchemaTypeIsObject() {
        assertEquals("object", GetMoreTools.CONTEXT_INPUT_SCHEMA.get("type"),
                "get_more_tools input schema type must be \"object\"");
    }

    @Test
    void inputSchemaHasContextProperty() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props =
                (Map<String, Object>) GetMoreTools.CONTEXT_INPUT_SCHEMA.get("properties");
        assertNotNull(props, "input schema must have a properties map");
        assertTrue(props.containsKey("context"),
                "input schema properties must include \"context\"");
    }

    @Test
    void inputSchemaContextPropertyTypeIsString() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props =
                (Map<String, Object>) GetMoreTools.CONTEXT_INPUT_SCHEMA.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> contextProp = (Map<String, Object>) props.get("context");
        assertNotNull(contextProp, "context property definition must not be null");
        assertEquals("string", contextProp.get("type"),
                "context property type must be \"string\"");
    }

    @Test
    void inputSchemaContextIsRequired() {
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) GetMoreTools.CONTEXT_INPUT_SCHEMA.get("required");
        assertNotNull(required, "input schema must have a required list");
        assertTrue(required.contains("context"),
                "\"context\" must be in the required list so agents always fill it in");
    }

    // ── buildTool() — stateful spec ───────────────────────────────────────────

    @Test
    void buildToolReturnsNonNull() {
        assertNotNull(GetMoreTools.buildTool());
    }

    @Test
    void buildToolHasCorrectToolName() {
        McpServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildTool();
        assertEquals(GetMoreTools.GET_MORE_TOOLS_NAME, spec.tool().name(),
                "built tool name must be get_more_tools");
    }

    @Test
    void buildToolHasCallHandlerSlotFilled() {
        McpServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildTool();
        assertNotNull(spec.callHandler(),
                "buildTool must place the handler in the callHandler slot");
    }

    @Test
    void buildToolHandlerReturnsCannedResponse() {
        McpServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildTool();
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new McpSchema.CallToolRequest(
                        GetMoreTools.GET_MORE_TOOLS_NAME,
                        Map.of("context", "I need a CSV export tool")))
                .block();
        assertNotNull(result);
        assertFalse(result.content().isEmpty(), "result must have at least one content item");
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertFalse(text.isBlank(), "canned response text must not be blank");
    }

    @Test
    void buildToolHandlerReturnsCannedResponseWhenContextIsMissing() {
        McpServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildTool();
        // No context argument — handler must not throw
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new McpSchema.CallToolRequest(
                        GetMoreTools.GET_MORE_TOOLS_NAME, Map.of()))
                .block();
        assertNotNull(result);
        assertFalse(result.content().isEmpty());
    }

    @Test
    void buildToolHandlerReturnsCannedResponseWhenArgsAreNull() {
        McpServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildTool();
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new McpSchema.CallToolRequest(
                        GetMoreTools.GET_MORE_TOOLS_NAME, null))
                .block();
        assertNotNull(result);
        assertFalse(result.content().isEmpty());
    }

    @Test
    void buildToolResultIsNotError() {
        McpServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildTool();
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new McpSchema.CallToolRequest(
                        GetMoreTools.GET_MORE_TOOLS_NAME,
                        Map.of("context", "need db tool")))
                .block();
        assertNotNull(result);
        assertFalse(Boolean.TRUE.equals(result.isError()),
                "get_more_tools must not return an error result");
    }

    @Test
    void buildToolHasContextPropertyInSchema() {
        McpServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildTool();
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) spec.tool().inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull(props, "tool schema must have properties");
        assertTrue(props.containsKey("context"),
                "tool schema must include context property");
    }

    @Test
    void buildToolProducesNewInstanceOnEachCall() {
        assertNotSame(GetMoreTools.buildTool(), GetMoreTools.buildTool(),
                "each buildTool() call must return a new specification object");
    }

    // ── buildStatelessTool() — stateless spec ─────────────────────────────────

    @Test
    void buildStatelessToolReturnsNonNull() {
        assertNotNull(GetMoreTools.buildStatelessTool());
    }

    @Test
    void buildStatelessToolHasCorrectToolName() {
        McpStatelessServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildStatelessTool();
        assertEquals(GetMoreTools.GET_MORE_TOOLS_NAME, spec.tool().name(),
                "stateless built tool name must be get_more_tools");
    }

    @Test
    void buildStatelessToolHasCallHandlerSlotFilled() {
        McpStatelessServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildStatelessTool();
        assertNotNull(spec.callHandler(),
                "buildStatelessTool must have a non-null callHandler");
    }

    @Test
    void buildStatelessToolHandlerReturnsCannedResponse() {
        McpStatelessServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildStatelessTool();
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new McpSchema.CallToolRequest(
                        GetMoreTools.GET_MORE_TOOLS_NAME,
                        Map.of("context", "I need a SQL tool")))
                .block();
        assertNotNull(result);
        assertFalse(result.content().isEmpty());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertFalse(text.isBlank(), "stateless canned response must not be blank");
    }

    @Test
    void buildStatelessToolHandlerReturnsCannedResponseWhenContextMissing() {
        McpStatelessServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildStatelessTool();
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new McpSchema.CallToolRequest(
                        GetMoreTools.GET_MORE_TOOLS_NAME, Map.of()))
                .block();
        assertNotNull(result);
        assertFalse(result.content().isEmpty());
    }

    @Test
    void buildStatelessToolHandlerReturnsCannedResponseWhenArgsAreNull() {
        McpStatelessServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildStatelessTool();
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new McpSchema.CallToolRequest(
                        GetMoreTools.GET_MORE_TOOLS_NAME, null))
                .block();
        assertNotNull(result);
        assertFalse(result.content().isEmpty());
    }

    @Test
    void buildStatelessToolResultIsNotError() {
        McpStatelessServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildStatelessTool();
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new McpSchema.CallToolRequest(
                        GetMoreTools.GET_MORE_TOOLS_NAME,
                        Map.of("context", "need export tool")))
                .block();
        assertNotNull(result);
        assertFalse(Boolean.TRUE.equals(result.isError()),
                "stateless get_more_tools must not return an error result");
    }

    @Test
    void buildStatelessToolHasContextPropertyInSchema() {
        McpStatelessServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildStatelessTool();
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) spec.tool().inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull(props);
        assertTrue(props.containsKey("context"));
    }

    @Test
    void buildStatelessToolProducesNewInstanceOnEachCall() {
        assertNotSame(GetMoreTools.buildStatelessTool(), GetMoreTools.buildStatelessTool(),
                "each buildStatelessTool() call must return a new specification object");
    }

    // ── buildTool() — fault-containment: catch(Exception e) path ─────────────
    //
    // The handler wraps its body in try/catch so that any unexpected exception
    // still returns CANNED_RESPONSE rather than propagating as a Mono error.
    // Passing null as the CallToolRequest causes request.arguments() → NPE,
    // which triggers the catch block.

    @Test
    void buildToolCatchPathDoesNotThrow() {
        McpServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildTool();
        // null request → NullPointerException inside try block → caught → CANNED_RESPONSE
        assertDoesNotThrow(() -> spec.callHandler().apply(null, null).block(),
                "handler catch block must not propagate the exception as a Mono error");
    }

    @Test
    void buildToolCatchPathReturnsNonNull() {
        McpServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildTool();
        McpSchema.CallToolResult result = spec.callHandler().apply(null, null).block();
        assertNotNull(result, "catch path must return a non-null result");
    }

    @Test
    void buildToolCatchPathReturnsCannedResponse() {
        McpServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildTool();
        McpSchema.CallToolResult result = spec.callHandler().apply(null, null).block();
        assertNotNull(result);
        assertFalse(result.content().isEmpty(), "catch path result must have content");
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertFalse(text.isBlank(), "catch path must return the non-blank canned response text");
    }

    @Test
    void buildToolCatchPathResultIsNotError() {
        McpServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildTool();
        McpSchema.CallToolResult result = spec.callHandler().apply(null, null).block();
        assertNotNull(result);
        assertFalse(Boolean.TRUE.equals(result.isError()),
                "catch path must not set isError=true");
    }

    // ── buildStatelessTool() — fault-containment: catch(Exception e) path ─────

    @Test
    void buildStatelessToolCatchPathDoesNotThrow() {
        McpStatelessServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildStatelessTool();
        assertDoesNotThrow(() -> spec.callHandler().apply(null, null).block(),
                "stateless handler catch block must not propagate the exception");
    }

    @Test
    void buildStatelessToolCatchPathReturnsNonNull() {
        McpStatelessServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildStatelessTool();
        McpSchema.CallToolResult result = spec.callHandler().apply(null, null).block();
        assertNotNull(result, "stateless catch path must return a non-null result");
    }

    @Test
    void buildStatelessToolCatchPathReturnsCannedResponse() {
        McpStatelessServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildStatelessTool();
        McpSchema.CallToolResult result = spec.callHandler().apply(null, null).block();
        assertNotNull(result);
        assertFalse(result.content().isEmpty());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertFalse(text.isBlank(), "stateless catch path must return the canned response text");
    }

    @Test
    void buildStatelessToolCatchPathResultIsNotError() {
        McpStatelessServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildStatelessTool();
        McpSchema.CallToolResult result = spec.callHandler().apply(null, null).block();
        assertNotNull(result);
        assertFalse(Boolean.TRUE.equals(result.isError()),
                "stateless catch path must not set isError=true");
    }

    // ── handle() — real catch(Exception) path ────────────────────────────────
    //
    // Passing a Map whose get() throws causes an Exception inside the try block,
    // which is the only way to reach the catch branch at runtime.

    @Test
    void handleCatchPath_stateful_returnsCannedResponse() {
        Map<String, Object> throwingArgs = new AbstractMap<>() {
            @Override
            public Object get(Object key) { throw new RuntimeException("simulated map error"); }
            @Override
            public Set<Map.Entry<String, Object>> entrySet() { return Set.of(); }
        };

        McpServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildTool();
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new McpSchema.CallToolRequest(GetMoreTools.GET_MORE_TOOLS_NAME, throwingArgs))
                .block();

        assertNotNull(result);
        assertFalse(result.content().isEmpty(), "catch path must return content");
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertFalse(text.isBlank(), "catch path must return the canned response text");
        assertFalse(Boolean.TRUE.equals(result.isError()), "catch path must not set isError=true");
    }

    @Test
    void handleCatchPath_stateless_returnsCannedResponse() {
        Map<String, Object> throwingArgs = new AbstractMap<>() {
            @Override
            public Object get(Object key) { throw new RuntimeException("simulated map error"); }
            @Override
            public Set<Map.Entry<String, Object>> entrySet() { return Set.of(); }
        };

        McpStatelessServerFeatures.AsyncToolSpecification spec = GetMoreTools.buildStatelessTool();
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new McpSchema.CallToolRequest(GetMoreTools.GET_MORE_TOOLS_NAME, throwingArgs))
                .block();

        assertNotNull(result);
        assertFalse(result.content().isEmpty(), "stateless catch path must return content");
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertFalse(text.isBlank(), "stateless catch path must return the canned response text");
        assertFalse(Boolean.TRUE.equals(result.isError()), "stateless catch path must not set isError=true");
    }

    // ── buildTool() vs buildStatelessTool() parity ────────────────────────────

    @Test
    void statefulAndStatelessToolsHaveSameName() {
        assertEquals(
                GetMoreTools.buildTool().tool().name(),
                GetMoreTools.buildStatelessTool().tool().name(),
                "stateful and stateless get_more_tools must have the same tool name");
    }

    @Test
    void statefulAndStatelessToolsHaveSameSchema() {
        @SuppressWarnings("unchecked")
        Map<String, Object> statefulSchema =
                (Map<String, Object>) GetMoreTools.buildTool().tool().inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> statelessSchema =
                (Map<String, Object>) GetMoreTools.buildStatelessTool().tool().inputSchema();
        assertEquals(statefulSchema.get("type"), statelessSchema.get("type"),
                "both variants must share the same schema type");
        @SuppressWarnings("unchecked")
        Map<String, Object> statefulProps  = (Map<String, Object>) statefulSchema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> statelessProps = (Map<String, Object>) statelessSchema.get("properties");
        assertEquals(statefulProps.keySet(), statelessProps.keySet(),
                "stateful and stateless get_more_tools must expose identical properties");
    }

    @Test
    void statefulAndStatelessHandlersReturnSameCannedText() {
        McpSchema.CallToolRequest req = new McpSchema.CallToolRequest(
                GetMoreTools.GET_MORE_TOOLS_NAME, Map.of("context", "need something"));

        String statefulText = ((McpSchema.TextContent)
                GetMoreTools.buildTool().callHandler().apply(null, req).block()
                        .content().get(0)).text();

        String statelessText = ((McpSchema.TextContent)
                GetMoreTools.buildStatelessTool().callHandler().apply(null, req).block()
                        .content().get(0)).text();

        assertEquals(statefulText, statelessText,
                "stateful and stateless variants must return identical canned response text");
    }
}
