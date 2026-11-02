package com.agentcat.sdk.engine;

import com.agentcat.sdk.AgentCatOptions;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StatefulToolList}.
 *
 * <p>Verifies that tools added to the list after {@code track()} are automatically wrapped,
 * that already-wrapped tools are not double-wrapped, and that the list behaves as a
 * correct {@link java.util.concurrent.CopyOnWriteArrayList}.
 */
class StatefulToolListTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AsyncToolSpecification unwrapped(String name) {
        McpSchema.Tool tool = McpSchema.Tool.builder(name, Map.of()).build();
        return AsyncToolSpecFactory.buildSpec(tool,
                (McpAsyncServerExchange ex, McpSchema.CallToolRequest req) ->
                        Mono.just(McpSchema.CallToolResult.builder().addTextContent("ok").build()));
    }

    private static StatefulToolCallWrapper wrapper() {
        return new StatefulToolCallWrapper("proj", "srv", "1.0", new AgentCatOptions());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Constructor — initial tools preserved
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void emptyInitialListProducesEmptyList() {
        StatefulToolList list = wrapper().newToolList(List.of());
        assertEquals(0, list.size());
    }

    @Test
    void initialToolsArePreserved() {
        StatefulToolCallWrapper w = wrapper();
        AsyncToolSpecification s1 = w.wrap(unwrapped("pre_a"));
        AsyncToolSpecification s2 = w.wrap(unwrapped("pre_b"));
        StatefulToolList list = w.newToolList(List.of(s1, s2));
        assertEquals(2, list.size());
        assertSame(s1, list.get(0));
        assertSame(s2, list.get(1));
    }

    @Test
    void initialToolOrderIsPreserved() {
        StatefulToolCallWrapper w = wrapper();
        List<AsyncToolSpecification> specs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            specs.add(w.wrap(unwrapped("tool_" + i)));
        }
        StatefulToolList list = w.newToolList(specs);
        for (int i = 0; i < 5; i++) {
            assertEquals("tool_" + i, list.get(i).tool().name(),
                    "tool order must be preserved from the initial list");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // add() — auto-wrapping
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void addWrapsUnwrappedSpec() {
        StatefulToolCallWrapper w = wrapper();
        StatefulToolList list = w.newToolList(List.of());
        list.add(unwrapped("late_tool"));
        assertEquals(1, list.size());
        assertTrue(StatefulToolCallWrapper.isWrapped(list.get(0)),
                "spec added via add() must be automatically wrapped");
    }

    @Test
    void addPreservesToolName() {
        StatefulToolCallWrapper w = wrapper();
        StatefulToolList list = w.newToolList(List.of());
        list.add(unwrapped("named_tool"));
        assertEquals("named_tool", list.get(0).tool().name(),
                "auto-wrapping must preserve the original tool name");
    }

    @Test
    void addReturnsTrueOnSuccess() {
        StatefulToolCallWrapper w = wrapper();
        StatefulToolList list = w.newToolList(List.of());
        assertTrue(list.add(unwrapped("any_tool")));
    }

    @Test
    void multipleAddsAreAllWrapped() {
        StatefulToolCallWrapper w = wrapper();
        StatefulToolList list = w.newToolList(List.of());
        list.add(unwrapped("alpha"));
        list.add(unwrapped("beta"));
        list.add(unwrapped("gamma"));
        assertEquals(3, list.size());
        for (AsyncToolSpecification spec : list) {
            assertTrue(StatefulToolCallWrapper.isWrapped(spec),
                    "every spec added must be wrapped: " + spec.tool().name());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // add() — no double-wrapping
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void addDoesNotDoubleWrapAlreadyWrappedSpec() {
        StatefulToolCallWrapper w = wrapper();
        AsyncToolSpecification alreadyWrapped = w.wrap(unwrapped("already_wrapped"));
        StatefulToolList list = w.newToolList(List.of());
        list.add(alreadyWrapped);
        assertSame(alreadyWrapped, list.get(0),
                "an already-wrapped spec must be added as-is, not re-wrapped");
    }

    @Test
    void handlerRemainsStatefulCallHandlerAfterAddingWrapped() {
        StatefulToolCallWrapper w = wrapper();
        AsyncToolSpecification wrapped = w.wrap(unwrapped("check_handler"));
        StatefulToolList list = w.newToolList(List.of());
        list.add(wrapped);
        // The handler on the stored spec must still be the same StatefulCallHandler instance
        assertSame(wrapped.callHandler(), list.get(0).callHandler(),
                "adding an already-wrapped spec must not replace its handler");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Mixed initial + late-added tools
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void mixedInitialAndLateToolsAreAllWrapped() {
        StatefulToolCallWrapper w = wrapper();
        // Pre-wrap two tools
        AsyncToolSpecification pre1 = w.wrap(unwrapped("pre_1"));
        AsyncToolSpecification pre2 = w.wrap(unwrapped("pre_2"));
        StatefulToolList list = w.newToolList(List.of(pre1, pre2));
        // Add two more after construction
        list.add(unwrapped("late_1"));
        list.add(unwrapped("late_2"));
        assertEquals(4, list.size());
        for (AsyncToolSpecification spec : list) {
            assertTrue(StatefulToolCallWrapper.isWrapped(spec),
                    "all tools must be wrapped: " + spec.tool().name());
        }
    }
}
