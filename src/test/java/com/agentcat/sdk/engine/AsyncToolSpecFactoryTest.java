package com.agentcat.sdk.engine;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AsyncToolSpecFactory#buildSpec}.
 *
 * <h3>What we verify</h3>
 * <ol>
 *   <li>Handler lands in the {@code callHandler} slot, not the {@code call} slot.</li>
 *   <li>The tool descriptor is preserved exactly as passed.</li>
 *   <li>The handler is invocable via spec and returns the correct result.</li>
 *   <li>Two buildSpec calls produce distinct objects.</li>
 *   <li>Handlers from different specs never cross-contaminate.</li>
 * </ol>
 */
class AsyncToolSpecFactoryTest {

    // ── Assertion helpers ─────────────────────────────────────────────────────

    /**
     * Asserts the handler is in the callHandler slot.
     * Critical: StatefulCallHandler reads only callHandler(), never call().
     */
    private static void assertHandlerInCallHandlerSlot(
            AsyncToolSpecification spec,
            BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> handler) {
        assertNotNull(spec, "spec must not be null");
        assertSame(handler, spec.callHandler(),
                "handler must be in the callHandler slot, not the call slot");
    }

    /**
     * Asserts the tool descriptor is the exact same object that was passed in.
     */
    private static void assertToolPreserved(AsyncToolSpecification spec, McpSchema.Tool tool) {
        assertSame(tool, spec.tool(), "spec.tool() must return the same object that was passed");
        assertEquals(tool.name(), spec.tool().name(), "tool name must match");
        assertEquals(tool.description(), spec.tool().description(), "tool description must match");
    }

    /**
     * Asserts the handler is invocable via spec and its first TextContent matches expectedText.
     */
    private static void assertHandlerInvocable(
            AsyncToolSpecification spec,
            McpSchema.CallToolRequest request,
            String expectedText) {
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, request)
                .block();
        assertNotNull(result, "result must not be null");
        assertFalse(result.content().isEmpty(), "content must not be empty");
        McpSchema.TextContent first = (McpSchema.TextContent) result.content().get(0);
        assertEquals(expectedText, first.text(), "response text must match");
    }

    /**
     * Asserts that two specs are distinct object instances.
     */
    private static void assertDistinctSpecs(AsyncToolSpecification spec1, AsyncToolSpecification spec2) {
        assertNotSame(spec1, spec2, "each buildSpec call must produce a new object");
    }

    // ── Factory helpers ───────────────────────────────────────────────────────

    /** Creates a Tool using the SDK 2.x builder API with an empty schema. */
    private static McpSchema.Tool toolWith(String name, String description) {
        return McpSchema.Tool.builder(name, Map.of())
                .description(description)
                .build();
    }

    private static McpSchema.Tool toolWithSchema(String name, String description,
                                                  Map<String, Object> schema) {
        return McpSchema.Tool.builder(name, schema)
                .description(description)
                .build();
    }

    private static McpSchema.CallToolRequest requestFor(String toolName) {
        return new McpSchema.CallToolRequest(toolName, Map.of());
    }

    /** Returns a handler that always produces a single TextContent with the given text. */
    private static BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>>
    handlerReturning(String text) {
        return (exchange, req) -> Mono.just(
                McpSchema.CallToolResult.builder()
                        .addTextContent(text)
                        .build());
    }

    // ── Parameter sources ─────────────────────────────────────────────────────

    /**
     * Various tool definitions: different names, descriptions, and schemas.
     */
    static Stream<McpSchema.Tool> toolVariations() {
        return Stream.of(
                toolWith("get_weather", "Returns current weather"),
                toolWith("search", "Search the web"),
                toolWith("calculate", null),
                toolWith("a", ""),
                toolWith("tool_with_underscores_and_numbers_123", "Long name tool"),
                toolWith("TOOL_UPPERCASE", "Uppercase name"),
                toolWithSchema("typed_tool", "Has schema",
                        Map.of("type", "object",
                               "properties", Map.of("query", Map.of("type", "string")))),
                toolWithSchema("multi_param_tool", "Multiple params",
                        Map.of("type", "object",
                               "properties", Map.of(
                                   "q",      Map.of("type", "string"),
                                   "limit",  Map.of("type", "integer"),
                                   "offset", Map.of("type", "integer"))))
        );
    }

    /**
     * Various handler response texts to exercise the invocation path.
     */
    static Stream<String> handlerResponseVariations() {
        return Stream.of(
                "ok",
                "Hello, World!",
                "Result with newline content",
                "Special chars: <>&\"'",
                "a".repeat(1000),
                "Unicode: \u041F\u0440\u0438\u0432\u0435\u0442",
                "42",
                "null",
                "true",
                "{ \"json\": true }"
        );
    }

    // ── Tests: handler in callHandler slot ───────────────────────────────────

    @Test
    void handlerIsInCallHandlerSlotForBasicTool() {
        McpSchema.Tool tool = toolWith("my_tool", "desc");
        var handler = handlerReturning("result");
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool, handler);
        assertHandlerInCallHandlerSlot(spec, handler);
    }

    @ParameterizedTest
    @MethodSource("toolVariations")
    void handlerIsInCallHandlerSlotForAllToolVariations(McpSchema.Tool tool) {
        var handler = handlerReturning("ok");
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool, handler);
        assertHandlerInCallHandlerSlot(spec, handler);
    }

    // ── Tests: tool descriptor preserved ─────────────────────────────────────

    @ParameterizedTest
    @MethodSource("toolVariations")
    void toolDescriptorIsPreservedExactly(McpSchema.Tool tool) {
        var handler = handlerReturning("ok");
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool, handler);
        assertToolPreserved(spec, tool);
    }

    @Test
    void toolNameIsPreservedVerbatim() {
        McpSchema.Tool tool = toolWith("exact_tool_name_XYZ", "desc");
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool, handlerReturning("x"));
        assertEquals("exact_tool_name_XYZ", spec.tool().name());
    }

    @Test
    void toolDescriptionIsPreservedWhenNull() {
        McpSchema.Tool tool = toolWith("no_desc_tool", null);
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool, handlerReturning("x"));
        assertNull(spec.tool().description());
    }

    // ── Tests: handler is invocable ───────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("handlerResponseVariations")
    void handlerIsInvocableAndReturnsExpectedText(String responseText) {
        McpSchema.Tool tool = toolWith("echo_tool", "echoes back");
        var handler = handlerReturning(responseText);
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool, handler);
        assertHandlerInvocable(spec, requestFor("echo_tool"), responseText);
    }

    @Test
    void handlerReceivesCorrectRequest() {
        McpSchema.Tool tool = toolWith("check_request", "desc");
        McpSchema.CallToolRequest[] captured = new McpSchema.CallToolRequest[1];
        BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> handler =
                (exchange, req) -> {
                    captured[0] = req;
                    return Mono.just(McpSchema.CallToolResult.builder()
                            .addTextContent("done")
                            .build());
                };

        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool, handler);
        McpSchema.CallToolRequest request = requestFor("check_request");
        spec.callHandler().apply(null, request).block();

        assertSame(request, captured[0], "handler must receive the exact request object");
    }

    @Test
    void handlerCanReturnErrorResult() {
        McpSchema.Tool tool = toolWith("failing_tool", "may fail");
        BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> handler =
                (exchange, req) -> Mono.just(
                        McpSchema.CallToolResult.builder()
                                .addTextContent("error occurred")
                                .isError(true)
                                .build());

        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool, handler);
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, requestFor("failing_tool"))
                .block();

        assertNotNull(result);
        assertTrue(result.isError(), "isError must be true");
        McpSchema.TextContent first = (McpSchema.TextContent) result.content().get(0);
        assertEquals("error occurred", first.text());
    }

    @Test
    void handlerCanReturnMultipleContentItems() {
        McpSchema.Tool tool = toolWith("multi_content_tool", "returns multiple items");
        BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> handler =
                (exchange, req) -> Mono.just(
                        McpSchema.CallToolResult.builder()
                                .addTextContent("first")
                                .addTextContent("second")
                                .addTextContent("third")
                                .build());

        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool, handler);
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, requestFor("multi_content_tool"))
                .block();

        assertNotNull(result);
        assertEquals(3, result.content().size());
    }

    // ── Tests: spec independence ──────────────────────────────────────────────

    @Test
    void twoCallsProduceDistinctSpecObjects() {
        McpSchema.Tool tool = toolWith("tool", "desc");
        var handler = handlerReturning("ok");
        AsyncToolSpecification spec1 = AsyncToolSpecFactory.buildSpec(tool, handler);
        AsyncToolSpecification spec2 = AsyncToolSpecFactory.buildSpec(tool, handler);
        assertDistinctSpecs(spec1, spec2);
    }

    @Test
    void differentHandlersDoNotCrossContaminate() {
        McpSchema.Tool tool1 = toolWith("tool_a", "A");
        McpSchema.Tool tool2 = toolWith("tool_b", "B");
        var handler1 = handlerReturning("response_A");
        var handler2 = handlerReturning("response_B");

        AsyncToolSpecification spec1 = AsyncToolSpecFactory.buildSpec(tool1, handler1);
        AsyncToolSpecification spec2 = AsyncToolSpecFactory.buildSpec(tool2, handler2);

        // Each spec invokes its own handler
        assertHandlerInvocable(spec1, requestFor("tool_a"), "response_A");
        assertHandlerInvocable(spec2, requestFor("tool_b"), "response_B");

        // Handlers are not swapped
        assertSame(handler1, spec1.callHandler());
        assertSame(handler2, spec2.callHandler());
    }

    @Test
    void manySpecsAllWiredCorrectly() {
        // 20 distinct tools, each with its own handler
        int count = 20;
        McpSchema.Tool[] tools = new McpSchema.Tool[count];
        @SuppressWarnings("unchecked")
        BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>>[] handlers =
                new BiFunction[count];
        AsyncToolSpecification[] specs = new AsyncToolSpecification[count];

        for (int i = 0; i < count; i++) {
            tools[i]    = toolWith("tool_" + i, "Tool number " + i);
            handlers[i] = handlerReturning("result_" + i);
            specs[i]    = AsyncToolSpecFactory.buildSpec(tools[i], handlers[i]);
        }

        for (int i = 0; i < count; i++) {
            assertHandlerInCallHandlerSlot(specs[i], handlers[i]);
            assertToolPreserved(specs[i], tools[i]);
            assertHandlerInvocable(specs[i], requestFor("tool_" + i), "result_" + i);
        }
    }

    // ── Tests: spec is never null ─────────────────────────────────────────────

    @Test
    void buildSpecNeverReturnsNull() {
        McpSchema.Tool tool = toolWith("t", "d");
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool, handlerReturning("x"));
        assertNotNull(spec);
    }

    @ParameterizedTest
    @MethodSource("toolVariations")
    void buildSpecIsNonNullForAllToolVariations(McpSchema.Tool tool) {
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool, handlerReturning("x"));
        assertNotNull(spec);
    }

    // ── Tests: fault-containment — buildSpec never throws ────────────────────

    /**
     * Internally, {@link AsyncToolSpecFactory#buildSpec} tries a 3-arg constructor
     * (for SDK 0.18.x) and falls back to the standard 2-arg constructor on
     * {@link NoSuchMethodException} or {@link ReflectiveOperationException}.
     * The result must always be a valid spec regardless of which path executes.
     */
    @Test
    void buildSpecNeverThrowsForAnyTool() {
        // Cycle through all tool variations — the factory must never propagate an exception
        toolVariations().forEach(tool ->
                assertDoesNotThrow(() -> AsyncToolSpecFactory.buildSpec(tool, handlerReturning("ok")),
                        "buildSpec must not throw for tool: " + tool.name()));
    }

    @Test
    void buildSpecFaultContainedResultAlwaysHasCorrectHandler() {
        // Whether the 3-arg or 2-arg ctor path was taken, the handler must land in
        // the callHandler slot so StatefulCallHandler.invokeOriginal() finds it.
        var handler = handlerReturning("fault_result");
        McpSchema.Tool tool = toolWith("fault_tool", "d");
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool, handler);
        assertSame(handler, spec.callHandler(),
                "fallback path must still place the handler in the callHandler slot");
    }

    @Test
    void buildSpecFaultContainedResultIsInvocable() {
        // After any fallback the spec must be invocable end-to-end
        McpSchema.Tool tool = toolWith("invocable_after_fallback", "d");
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool, handlerReturning("alive"));
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, requestFor("invocable_after_fallback"))
                .block();
        assertNotNull(result);
        assertEquals("alive", ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void buildSpecAlwaysProducesInvocableSpecOnMultipleSuccessiveCalls() {
        // Ensure the fallback caching (or lack thereof) does not degrade on repeat calls
        McpSchema.Tool tool = toolWith("repeat_tool", "d");
        for (int i = 0; i < 5; i++) {
            AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool, handlerReturning("r" + i));
            assertNotNull(spec, "spec must not be null on call #" + i);
            assertNotNull(spec.callHandler(), "callHandler must not be null on call #" + i);
        }
    }
}
