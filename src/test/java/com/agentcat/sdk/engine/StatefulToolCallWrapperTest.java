package com.agentcat.sdk.engine;

import com.agentcat.sdk.AgentCatOptions;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StatefulToolCallWrapper}.
 *
 * <p>Verifies that wrapping injects AgentCat instrumentation into tool specs,
 * that {@link StatefulToolCallWrapper#isWrapped} correctly detects wrapped specs,
 * and that {@link StatefulToolCallWrapper#newToolList} returns an auto-wrapping list.
 */
class StatefulToolCallWrapperTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AgentCatOptions defaultOpts() {
        return new AgentCatOptions();
    }

    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder(name, Map.of())
                .description("Test tool " + name)
                .build();
    }

    private static BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>>
    handlerReturning(String text) {
        return (ex, req) -> Mono.just(
                McpSchema.CallToolResult.builder().addTextContent(text).build());
    }

    /** Builds an unwrapped spec (no AgentCat handler). */
    private static AsyncToolSpecification unwrapped(String name) {
        return AsyncToolSpecFactory.buildSpec(tool(name), handlerReturning("ok"));
    }

    private static StatefulToolCallWrapper wrapper() {
        return new StatefulToolCallWrapper("proj-1", "test-server", "1.0", defaultOpts());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // wrap()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void wrapReturnsNonNullSpec() {
        assertNotNull(wrapper().wrap(unwrapped("my_tool")));
    }

    @Test
    void wrappedSpecPreservesToolName() {
        AsyncToolSpecification wrapped = wrapper().wrap(unwrapped("echo"));
        assertEquals("echo", wrapped.tool().name());
    }

    @Test
    void wrappedSpecPreservesToolDescription() {
        McpSchema.Tool t = McpSchema.Tool.builder("describe_it", Map.of())
                .description("a description").build();
        AsyncToolSpecification original = AsyncToolSpecFactory.buildSpec(t, handlerReturning("x"));
        AsyncToolSpecification wrapped  = wrapper().wrap(original);
        assertEquals("a description", wrapped.tool().description());
    }

    @Test
    void wrapInstallsStatefulCallHandler() {
        AsyncToolSpecification wrapped = wrapper().wrap(unwrapped("tool_a"));
        assertNotNull(wrapped.callHandler(), "callHandler must not be null after wrapping");
        assertInstanceOf(StatefulCallHandler.class, wrapped.callHandler(),
                "wrap() must install a StatefulCallHandler in the callHandler slot");
    }

    @Test
    void wrappedSpecHasCallHandlerSlotFilled() {
        AsyncToolSpecification wrapped = wrapper().wrap(unwrapped("tool_b"));
        // Verify the handler is actually callable (returns a Mono, does not throw)
        assertDoesNotThrow(() ->
                wrapped.callHandler()
                        .apply(null, new McpSchema.CallToolRequest("tool_b", Map.of()))
                        .block()
        );
    }

    @Test
    void wrapProducesNewObjectEachTime() {
        AsyncToolSpecification original = unwrapped("tool_c");
        StatefulToolCallWrapper w = wrapper();
        AsyncToolSpecification a = w.wrap(original);
        AsyncToolSpecification b = w.wrap(original);
        assertNotSame(a, b, "each wrap() call must return a new spec object");
    }

    @Test
    void wrappedSpecInjectsSessionIdIntoSchema() {
        AsyncToolSpecification wrapped = wrapper().wrap(unwrapped("inject_test"));
        @SuppressWarnings("unchecked")
        Map<String, Object> schema     = (Map<String, Object>) wrapped.tool().inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties, "injected schema must have a 'properties' map");
        assertTrue(properties.containsKey("session_id"),
                "session_id must be injected into the tool's input schema");
    }

    @Test
    void wrapDoesNotMutateOriginalSpec() {
        AsyncToolSpecification original = unwrapped("no_mutation");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemaBefore = (Map<String, Object>) original.tool().inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> propsBefore  = (Map<String, Object>) schemaBefore.getOrDefault("properties", Map.of());
        boolean hadSessionId = propsBefore.containsKey("session_id");

        wrapper().wrap(original);

        // Original tool's schema must not have been mutated
        @SuppressWarnings("unchecked")
        Map<String, Object> schemaAfter = (Map<String, Object>) original.tool().inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> propsAfter  = (Map<String, Object>) schemaAfter.getOrDefault("properties", Map.of());
        assertEquals(hadSessionId, propsAfter.containsKey("session_id"),
                "wrap() must not mutate the original tool's schema");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // isWrapped()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void isWrappedReturnsFalseForUnwrappedSpec() {
        assertFalse(StatefulToolCallWrapper.isWrapped(unwrapped("raw_tool")),
                "isWrapped must return false for a spec that has not been wrapped");
    }

    @Test
    void isWrappedReturnsTrueForWrappedSpec() {
        AsyncToolSpecification wrapped = wrapper().wrap(unwrapped("wrapped_tool"));
        assertTrue(StatefulToolCallWrapper.isWrapped(wrapped),
                "isWrapped must return true after wrap()");
    }

    @Test
    void isWrappedReturnsTrueForAnyWrappedSpec() {
        StatefulToolCallWrapper w = wrapper();
        String[] names = {"tool_1", "tool_2", "tool_3"};
        for (String name : names) {
            AsyncToolSpecification wrapped = w.wrap(unwrapped(name));
            assertTrue(StatefulToolCallWrapper.isWrapped(wrapped),
                    "isWrapped must be true for wrapped spec: " + name);
        }
    }

    @Test
    void isWrappedRemainsConsistentAfterRewrap() {
        StatefulToolCallWrapper w = wrapper();
        AsyncToolSpecification once  = w.wrap(unwrapped("rewrap_tool"));
        AsyncToolSpecification twice = w.wrap(once);
        assertTrue(StatefulToolCallWrapper.isWrapped(once));
        assertTrue(StatefulToolCallWrapper.isWrapped(twice));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // newToolList()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void newToolListReturnsStatefulToolList() {
        StatefulToolList list = wrapper().newToolList(List.of());
        assertNotNull(list);
        assertInstanceOf(StatefulToolList.class, list);
    }

    @Test
    void newToolListPreservesInitialTools() {
        AsyncToolSpecification s1 = wrapper().wrap(unwrapped("pre_a"));
        AsyncToolSpecification s2 = wrapper().wrap(unwrapped("pre_b"));
        StatefulToolList list = wrapper().newToolList(List.of(s1, s2));
        assertEquals(2, list.size());
    }

    @Test
    void newToolListAutoWrapsToolsAddedAfterCreation() {
        StatefulToolCallWrapper w = wrapper();
        StatefulToolList list = w.newToolList(List.of());
        list.add(unwrapped("late_tool"));
        assertEquals(1, list.size());
        assertTrue(StatefulToolCallWrapper.isWrapped(list.get(0)),
                "tool added to the list after creation must be auto-wrapped");
    }

    @Test
    void newToolListDoesNotDoubleWrapAlreadyWrapped() {
        StatefulToolCallWrapper w = wrapper();
        AsyncToolSpecification alreadyWrapped = w.wrap(unwrapped("double_wrap_test"));
        StatefulToolList list = w.newToolList(List.of());
        list.add(alreadyWrapped);
        assertSame(alreadyWrapped, list.get(0),
                "already-wrapped spec must be added as-is without re-wrapping");
    }
}
