package com.agentcat.sdk.engine;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.modules.handles.SessionIdProvider;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StatefulCallHandler}.
 *
 * <p>Verifies the handler pipeline:
 * <ol>
 *   <li>Original user handler is invoked.</li>
 *   <li>Handle parameters (session_id, context) are stripped before the original handler runs.</li>
 *   <li>Non-handle parameters are forwarded unchanged.</li>
 *   <li>The result flows back to the caller.</li>
 *   <li>Errors from the original handler propagate as {@code Mono.error}.</li>
 *   <li>Mint-back is applied on MINTED sessions (text prepended) and absent on SUPPLIED sessions.</li>
 * </ol>
 */
class StatefulCallHandlerTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AgentCatOptions defaultOpts() {
        return new AgentCatOptions();
    }

    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder(name, Map.of()).description("Test " + name).build();
    }

    private static BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>>
    handlerReturning(String text) {
        return (ex, req) -> Mono.just(
                McpSchema.CallToolResult.builder().addTextContent(text).build());
    }

    /**
     * Builds a {@link StatefulCallHandler} backed by a capturable handler that stores
     * the last received request in {@code captured} and returns {@code responseText}.
     */
    private static StatefulCallHandler handlerCapturing(
            String toolName,
            AtomicReference<McpSchema.CallToolRequest> captured,
            String responseText) {
        BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> fn =
                (ex, req) -> {
                    captured.set(req);
                    return Mono.just(McpSchema.CallToolResult.builder().addTextContent(responseText).build());
                };
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool(toolName), fn);
        return new StatefulCallHandler(spec, "proj", "srv", "1.0", defaultOpts());
    }

    private static McpSchema.CallToolRequest request(String name, Object... kvPairs) {
        Map<String, Object> args = new HashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            args.put((String) kvPairs[i], kvPairs[i + 1]);
        }
        return new McpSchema.CallToolRequest(name, args);
    }

    /** A valid KSUID-style session ID the server will recognise as SUPPLIED. */
    private static String validSessionId() {
        return SessionIdProvider.newSessionId();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Original handler invoked
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void originalHandlerIsInvoked() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> fn =
                (ex, req) -> {
                    invoked.set(true);
                    return Mono.just(McpSchema.CallToolResult.builder().addTextContent("done").build());
                };
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool("test_tool"), fn);
        StatefulCallHandler handler = new StatefulCallHandler(spec, "p", "s", "v", defaultOpts());

        handler.apply(null, request("test_tool", "session_id", "start")).block();

        assertTrue(invoked.get(), "original handler must be invoked by StatefulCallHandler");
    }

    @Test
    void resultFromOriginalHandlerIsReturned() {
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool("echo"),
                handlerReturning("expected_result"));
        StatefulCallHandler handler = new StatefulCallHandler(spec, "p", "s", "v", defaultOpts());

        McpSchema.CallToolResult result =
                handler.apply(null, request("echo", "session_id", validSessionId())).block();

        assertNotNull(result);
        // On SUPPLIED sessions no mint-back text is added — the result carries only the original content
        boolean found = result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .anyMatch(t -> t.contains("expected_result"));
        assertTrue(found, "result must contain the text returned by the original handler");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Argument stripping
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void sessionIdIsStrippedBeforeReachingOriginalHandler() {
        AtomicReference<McpSchema.CallToolRequest> captured = new AtomicReference<>();
        StatefulCallHandler handler = handlerCapturing("echo", captured, "ok");

        handler.apply(null, request("echo", "session_id", "start", "payload", "hello")).block();

        assertNotNull(captured.get());
        assertFalse(captured.get().arguments().containsKey("session_id"),
                "session_id must be stripped before the original handler receives the request");
    }

    @Test
    void nonHandleParamsAreForwardedUnchanged() {
        AtomicReference<McpSchema.CallToolRequest> captured = new AtomicReference<>();
        StatefulCallHandler handler = handlerCapturing("echo", captured, "ok");

        handler.apply(null, request("echo", "session_id", "start", "input", "value_42")).block();

        assertEquals("value_42", captured.get().arguments().get("input"),
                "non-handle params must reach the original handler unchanged");
    }

    @Test
    void contextParamIsStrippedWhenToolCallContextEnabled() {
        AtomicReference<McpSchema.CallToolRequest> captured = new AtomicReference<>();
        AgentCatOptions opts = defaultOpts(); // enableToolCallContext=true by default
        BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> fn =
                (ex, req) -> {
                    captured.set(req);
                    return Mono.just(McpSchema.CallToolResult.builder().addTextContent("done").build());
                };
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool("ctx_tool"), fn);
        StatefulCallHandler handler = new StatefulCallHandler(spec, "p", "s", "v", opts);

        handler.apply(null, request("ctx_tool", "session_id", "start", "context", "doing a search")).block();

        assertFalse(captured.get().arguments().containsKey("context"),
                "context param must be stripped before reaching the original handler");
    }

    @Test
    void strippedRequestToolNameMatchesOriginal() {
        AtomicReference<McpSchema.CallToolRequest> captured = new AtomicReference<>();
        StatefulCallHandler handler = handlerCapturing("my_tool", captured, "ok");

        handler.apply(null, request("my_tool", "session_id", "start")).block();

        assertEquals("my_tool", captured.get().name(),
                "stripped request must carry the same tool name");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Mint-back
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void mintBackTextIsPrependedWhenSessionIdIsMinted() {
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool("mint_test"),
                handlerReturning("user_content"));
        StatefulCallHandler handler = new StatefulCallHandler(spec, "p", "s", "v", defaultOpts());

        // "start" sentinel → MINTED → mint-back text should be prepended
        McpSchema.CallToolResult result =
                handler.apply(null, request("mint_test", "session_id", "start")).block();

        assertNotNull(result);
        assertTrue(result.content().size() >= 2,
                "MINTED result must have mint-back text prepended before original content");
        McpSchema.TextContent first = (McpSchema.TextContent) result.content().get(0);
        assertTrue(first.text().contains("session_id issued"),
                "first content block must contain the session_id issued message");
    }

    @Test
    void mintBackIsAbsentWhenSessionIdIsSupplied() {
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool("supplied_test"),
                handlerReturning("original_text"));
        StatefulCallHandler handler = new StatefulCallHandler(spec, "p", "s", "v", defaultOpts());

        // Supplied valid KSUID → no mint-back
        McpSchema.CallToolResult result =
                handler.apply(null, request("supplied_test", "session_id", validSessionId())).block();

        assertNotNull(result);
        assertEquals(1, result.content().size(),
                "SUPPLIED session must not trigger mint-back — result has only the original content");
        assertEquals("original_text", ((McpSchema.TextContent) result.content().get(0)).text());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Error propagation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void errorFromOriginalHandlerPropagatesAsMono() {
        RuntimeException boom = new RuntimeException("handler exploded");
        BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> fn =
                (ex, req) -> Mono.error(boom);
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool("error_tool"), fn);
        StatefulCallHandler handler = new StatefulCallHandler(spec, "p", "s", "v", defaultOpts());

        Mono<McpSchema.CallToolResult> result =
                handler.apply(null, request("error_tool", "session_id", "start"));

        // User handler errors must propagate — SDK only records analytics as a side-effect.
        // Mirrors TypeScript SDK callWrap.ts: the error is re-thrown so the MCP server handles it.
        RuntimeException thrown = assertThrows(RuntimeException.class, result::block,
                "user handler error must propagate, not be swallowed");
        assertTrue(thrown.getMessage().contains("handler exploded"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // invokeOriginal() — null callHandler path
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * When {@code callHandler()} is null (0.18.x dual-slot scenario), {@code invokeOriginal()}
     * attempts a reflection-based fallback to the {@code call} slot. On SDK 1.x/2.x there is
     * no {@code call()} method → reflection throws NoSuchMethodException → caught → the method
     * returns {@code Mono.error(IllegalStateException("No handler registered for tool: ...")}.
     * The apply() try/catch must not hide this — it only wraps the very first exception.
     */
    @Test
    void invokeOriginalWithNullCallHandlerProducesMonoError() {
        McpSchema.Tool tool = McpSchema.Tool.builder("null_handler_tool", Map.of()).build();
        // Java records don't null-check constructor args — callHandler=null is legal to construct
        AsyncToolSpecification spec = new AsyncToolSpecification(tool, null);
        StatefulCallHandler handler = new StatefulCallHandler(spec, "p", "s", "v", defaultOpts());

        Mono<McpSchema.CallToolResult> result =
                handler.apply(null, request("null_handler_tool", "session_id", "start"));

        // Null callHandler → IllegalStateException propagates (user misconfiguration, not SDK error)
        assertNotNull(result, "apply() must return a Mono even with null callHandler");
        assertThrows(IllegalStateException.class, result::block,
                "null callHandler must propagate as IllegalStateException");
    }

    @Test
    void invokeOriginalNullCallHandlerErrorIsIllegalStateException() {
        McpSchema.Tool tool = McpSchema.Tool.builder("no_handler", Map.of()).build();
        AsyncToolSpecification spec = new AsyncToolSpecification(tool, null);
        StatefulCallHandler handler = new StatefulCallHandler(spec, "p", "s", "v", defaultOpts());

        Mono<McpSchema.CallToolResult> result =
                handler.apply(null, request("no_handler", "session_id", "start"));

        // Error propagates with tool name in message
        IllegalStateException thrown = assertThrows(IllegalStateException.class, result::block,
                "null callHandler must propagate as IllegalStateException");
        assertTrue(thrown.getMessage().contains("no_handler"),
                "error message must name the tool that had no handler");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // invokeOriginal() — synchronous throw path
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * When the original handler throws <em>synchronously</em> (instead of returning
     * {@code Mono.error}), {@link StatefulCallHandler#apply} must catch it and wrap it
     * in a {@code Mono.error} — the {@code try/catch} around {@code invokeOriginal()}.
     */
    @Test
    void synchronousThrowFromHandlerPropagates() {
        RuntimeException boom = new RuntimeException("handler threw synchronously");
        BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> fn =
                (ex, req) -> { throw boom; };
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool("sync_throw_tool"), fn);
        StatefulCallHandler handler = new StatefulCallHandler(spec, "p", "s", "v", defaultOpts());

        Mono<McpSchema.CallToolResult> result =
                handler.apply(null, request("sync_throw_tool", "session_id", "start"));

        // Sync throw is caught by try/catch in apply(), wrapped in Mono.error, then propagated.
        // Mirrors TypeScript SDK: user handler errors are re-thrown, not swallowed.
        RuntimeException thrown = assertThrows(RuntimeException.class, result::block,
                "synchronous throw from handler must propagate");
        assertTrue(thrown.getMessage().contains("handler threw synchronously"),
                "error message must describe the cause");
    }

    @Test
    void synchronousThrowDoesNotPreventApplyFromReturningMono() {
        BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> fn =
                (ex, req) -> { throw new IllegalStateException("sync"); };
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool("sync2"), fn);
        StatefulCallHandler handler = new StatefulCallHandler(spec, "p", "s", "v", defaultOpts());

        // apply() itself must not throw — it returns a Mono. The error surfaces when subscribed.
        Mono<McpSchema.CallToolResult> mono = assertDoesNotThrow(
                () -> handler.apply(null, request("sync2", "session_id", "start")),
                "apply() must return a Mono even when the handler throws synchronously");
        assertNotNull(mono);
        assertThrows(IllegalStateException.class, mono::block,
                "sync throw must propagate when Mono is subscribed");
    }

    @Test
    void errorResultFlagIsPreserved() {
        BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> fn =
                (ex, req) -> Mono.just(
                        McpSchema.CallToolResult.builder()
                                .addTextContent("something went wrong")
                                .isError(true)
                                .build());
        AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(tool("err_flag"), fn);
        StatefulCallHandler handler = new StatefulCallHandler(spec, "p", "s", "v", defaultOpts());

        McpSchema.CallToolResult result =
                handler.apply(null, request("err_flag", "session_id", validSessionId())).block();

        assertNotNull(result);
        assertTrue(result.isError(), "isError flag from original handler must be preserved");
    }
}
