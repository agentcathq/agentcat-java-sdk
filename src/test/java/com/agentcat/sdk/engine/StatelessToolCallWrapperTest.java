package com.agentcat.sdk.engine;

import com.agentcat.sdk.AgentCatOptions;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StatelessToolCallWrapper}.
 *
 * <p>Mirrors {@link StatefulToolCallWrapperTest} for the stateless path
 * ({@code McpStatelessSyncServer} / {@code McpStatelessAsyncServer}).
 */
class StatelessToolCallWrapperTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AgentCatOptions defaultOpts() {
        return new AgentCatOptions();
    }

    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder(name, Map.of())
                .description("Test tool " + name)
                .build();
    }

    private static BiFunction<McpTransportContext, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>>
    handlerReturning(String text) {
        return (ctx, req) -> Mono.just(
                McpSchema.CallToolResult.builder().addTextContent(text).build());
    }

    private static AsyncToolSpecification unwrapped(String name) {
        return new AsyncToolSpecification(tool(name), handlerReturning("ok"));
    }

    private static StatelessToolCallWrapper wrapper() {
        return new StatelessToolCallWrapper("proj-1", "test-server", "1.0", defaultOpts());
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
        assertEquals("echo", wrapper().wrap(unwrapped("echo")).tool().name());
    }

    @Test
    void wrappedSpecPreservesToolDescription() {
        McpSchema.Tool t = McpSchema.Tool.builder("desc_tool", Map.of())
                .description("stateless desc").build();
        AsyncToolSpecification original = new AsyncToolSpecification(t, handlerReturning("x"));
        assertEquals("stateless desc", wrapper().wrap(original).tool().description());
    }

    @Test
    void wrapInstallsStatelessCallHandler() {
        AsyncToolSpecification wrapped = wrapper().wrap(unwrapped("handler_tool"));
        assertNotNull(wrapped.callHandler(), "callHandler must not be null after wrapping");
        assertInstanceOf(StatelessCallHandler.class, wrapped.callHandler(),
                "wrap() must install a StatelessCallHandler");
    }

    @Test
    void wrappedSpecHandlerIsInvocable() {
        AsyncToolSpecification wrapped = wrapper().wrap(unwrapped("invoke_test"));
        assertDoesNotThrow(() ->
                wrapped.callHandler()
                        .apply(null, new McpSchema.CallToolRequest("invoke_test", Map.of()))
                        .block()
        );
    }

    @Test
    void wrapProducesNewObjectEachTime() {
        AsyncToolSpecification original = unwrapped("tool_c");
        StatelessToolCallWrapper w = wrapper();
        assertNotSame(w.wrap(original), w.wrap(original),
                "each wrap() call must return a new spec object");
    }

    @Test
    void wrappedSpecInjectsSessionIdIntoSchema() {
        AsyncToolSpecification wrapped = wrapper().wrap(unwrapped("inject_test"));
        @SuppressWarnings("unchecked")
        Map<String, Object> schema     = (Map<String, Object>) wrapped.tool().inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
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
        assertFalse(StatelessToolCallWrapper.isWrapped(unwrapped("raw_tool")));
    }

    @Test
    void isWrappedReturnsTrueForWrappedSpec() {
        assertTrue(StatelessToolCallWrapper.isWrapped(wrapper().wrap(unwrapped("wrapped_tool"))));
    }

    @Test
    void isWrappedReturnsTrueForAllWrappedSpecs() {
        StatelessToolCallWrapper w = wrapper();
        for (String name : new String[]{"tool_1", "tool_2", "tool_3"}) {
            assertTrue(StatelessToolCallWrapper.isWrapped(w.wrap(unwrapped(name))),
                    "isWrapped must be true for: " + name);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // newToolList()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void newToolListReturnsStatelessToolList() {
        assertInstanceOf(StatelessToolList.class, wrapper().newToolList(List.of()));
    }

    @Test
    void newToolListPreservesInitialTools() {
        StatelessToolCallWrapper w = wrapper();
        AsyncToolSpecification s1 = w.wrap(unwrapped("pre_a"));
        AsyncToolSpecification s2 = w.wrap(unwrapped("pre_b"));
        assertEquals(2, w.newToolList(List.of(s1, s2)).size());
    }

    @Test
    void newToolListAutoWrapsToolsAddedAfterCreation() {
        StatelessToolCallWrapper w = wrapper();
        StatelessToolList list = w.newToolList(List.of());
        list.add(unwrapped("late_tool"));
        assertEquals(1, list.size());
        assertTrue(StatelessToolCallWrapper.isWrapped(list.get(0)),
                "tool added after creation must be auto-wrapped");
    }

    @Test
    void newToolListDoesNotDoubleWrapAlreadyWrapped() {
        StatelessToolCallWrapper w = wrapper();
        AsyncToolSpecification alreadyWrapped = w.wrap(unwrapped("double_wrap_test"));
        StatelessToolList list = w.newToolList(List.of());
        list.add(alreadyWrapped);
        assertSame(alreadyWrapped, list.get(0),
                "already-wrapped spec must be added as-is");
    }
}
