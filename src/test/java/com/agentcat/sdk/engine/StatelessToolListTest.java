package com.agentcat.sdk.engine;

import com.agentcat.sdk.AgentCatOptions;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StatelessToolList}.
 *
 * <p>Mirrors {@link StatefulToolListTest} for the stateless path.
 */
class StatelessToolListTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AsyncToolSpecification unwrapped(String name) {
        McpSchema.Tool tool = McpSchema.Tool.builder(name, Map.of()).build();
        return new AsyncToolSpecification(tool,
                (McpTransportContext ctx, McpSchema.CallToolRequest req) ->
                        Mono.just(McpSchema.CallToolResult.builder().addTextContent("ok").build()));
    }

    private static StatelessToolCallWrapper wrapper() {
        return new StatelessToolCallWrapper("proj", "srv", "1.0", new AgentCatOptions());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Constructor — initial tools preserved
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void emptyInitialListProducesEmptyList() {
        assertEquals(0, wrapper().newToolList(List.of()).size());
    }

    @Test
    void initialToolsArePreserved() {
        StatelessToolCallWrapper w = wrapper();
        AsyncToolSpecification s1 = w.wrap(unwrapped("pre_a"));
        AsyncToolSpecification s2 = w.wrap(unwrapped("pre_b"));
        StatelessToolList list = w.newToolList(List.of(s1, s2));
        assertEquals(2, list.size());
        assertSame(s1, list.get(0));
        assertSame(s2, list.get(1));
    }

    @Test
    void initialToolOrderIsPreserved() {
        StatelessToolCallWrapper w = wrapper();
        List<AsyncToolSpecification> specs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            specs.add(w.wrap(unwrapped("tool_" + i)));
        }
        StatelessToolList list = w.newToolList(specs);
        for (int i = 0; i < 5; i++) {
            assertEquals("tool_" + i, list.get(i).tool().name());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // add() — auto-wrapping
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void addWrapsUnwrappedSpec() {
        StatelessToolCallWrapper w = wrapper();
        StatelessToolList list = w.newToolList(List.of());
        list.add(unwrapped("late_tool"));
        assertEquals(1, list.size());
        assertTrue(StatelessToolCallWrapper.isWrapped(list.get(0)),
                "spec added via add() must be automatically wrapped");
    }

    @Test
    void addPreservesToolName() {
        StatelessToolCallWrapper w = wrapper();
        StatelessToolList list = w.newToolList(List.of());
        list.add(unwrapped("named_tool"));
        assertEquals("named_tool", list.get(0).tool().name());
    }

    @Test
    void addReturnsTrueOnSuccess() {
        assertTrue(wrapper().newToolList(List.of()).add(unwrapped("any")));
    }

    @Test
    void multipleAddsAreAllWrapped() {
        StatelessToolCallWrapper w = wrapper();
        StatelessToolList list = w.newToolList(List.of());
        list.add(unwrapped("alpha"));
        list.add(unwrapped("beta"));
        list.add(unwrapped("gamma"));
        assertEquals(3, list.size());
        for (AsyncToolSpecification spec : list) {
            assertTrue(StatelessToolCallWrapper.isWrapped(spec),
                    "every spec added must be wrapped: " + spec.tool().name());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // add() — no double-wrapping
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void addDoesNotDoubleWrapAlreadyWrappedSpec() {
        StatelessToolCallWrapper w = wrapper();
        AsyncToolSpecification alreadyWrapped = w.wrap(unwrapped("already_wrapped"));
        StatelessToolList list = w.newToolList(List.of());
        list.add(alreadyWrapped);
        assertSame(alreadyWrapped, list.get(0),
                "an already-wrapped spec must be added as-is, not re-wrapped");
    }

    @Test
    void handlerRemainsStatelessCallHandlerAfterAddingWrapped() {
        StatelessToolCallWrapper w = wrapper();
        AsyncToolSpecification wrapped = w.wrap(unwrapped("check_handler"));
        StatelessToolList list = w.newToolList(List.of());
        list.add(wrapped);
        assertSame(wrapped.callHandler(), list.get(0).callHandler(),
                "adding an already-wrapped spec must not replace its handler");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Mixed initial + late-added tools
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void mixedInitialAndLateToolsAreAllWrapped() {
        StatelessToolCallWrapper w = wrapper();
        AsyncToolSpecification pre1 = w.wrap(unwrapped("pre_1"));
        AsyncToolSpecification pre2 = w.wrap(unwrapped("pre_2"));
        StatelessToolList list = w.newToolList(List.of(pre1, pre2));
        list.add(unwrapped("late_1"));
        list.add(unwrapped("late_2"));
        assertEquals(4, list.size());
        for (AsyncToolSpecification spec : list) {
            assertTrue(StatelessToolCallWrapper.isWrapped(spec),
                    "all tools must be wrapped: " + spec.tool().name());
        }
    }
}
