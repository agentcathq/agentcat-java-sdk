package com.agentcat.sdk.engine;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.modules.handles.SessionIdProvider;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;
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
 * Unit tests for {@link StatelessCallHandler}.
 *
 * <p>Mirrors {@link StatefulCallHandlerTest} for the stateless path — uses
 * {@link McpTransportContext} (passed as {@code null} in all tests) instead of
 * {@link io.modelcontextprotocol.server.McpAsyncServerExchange}.
 */
class StatelessCallHandlerTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AgentCatOptions defaultOpts() {
        return new AgentCatOptions();
    }

    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder(name, Map.of()).description("Test " + name).build();
    }

    private static BiFunction<McpTransportContext, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>>
    handlerReturning(String text) {
        return (ctx, req) -> Mono.just(
                McpSchema.CallToolResult.builder().addTextContent(text).build());
    }

    private static AsyncToolSpecification spec(String name, String responseText) {
        return new AsyncToolSpecification(tool(name), handlerReturning(responseText));
    }

    private static StatelessCallHandler callHandler(AsyncToolSpecification spec) {
        return new StatelessCallHandler(spec, "proj", "srv", "1.0", defaultOpts());
    }

    private static McpSchema.CallToolRequest request(String name, Object... kvPairs) {
        Map<String, Object> args = new HashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            args.put((String) kvPairs[i], kvPairs[i + 1]);
        }
        return new McpSchema.CallToolRequest(name, args);
    }

    private static String validSessionId() {
        return SessionIdProvider.newSessionId();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Original handler invoked
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void originalHandlerIsInvoked() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        AsyncToolSpecification s = new AsyncToolSpecification(tool("t"),
                (ctx, req) -> {
                    invoked.set(true);
                    return Mono.just(McpSchema.CallToolResult.builder().addTextContent("x").build());
                });
        callHandler(s).apply(null, request("t", "session_id", "start")).block();
        assertTrue(invoked.get(), "original stateless handler must be invoked");
    }

    @Test
    void resultFromOriginalHandlerIsReturned() {
        McpSchema.CallToolResult result =
                callHandler(spec("echo", "hello_back"))
                        .apply(null, request("echo", "session_id", validSessionId()))
                        .block();
        assertNotNull(result);
        boolean found = result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .anyMatch(t -> t.contains("hello_back"));
        assertTrue(found, "result must contain the text from the original handler");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Argument stripping
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void sessionIdIsStrippedBeforeReachingOriginalHandler() {
        AtomicReference<McpSchema.CallToolRequest> captured = new AtomicReference<>();
        AsyncToolSpecification s = new AsyncToolSpecification(tool("echo"),
                (ctx, req) -> {
                    captured.set(req);
                    return Mono.just(McpSchema.CallToolResult.builder().addTextContent("ok").build());
                });
        callHandler(s).apply(null, request("echo", "session_id", "start", "payload", "data")).block();

        assertFalse(captured.get().arguments().containsKey("session_id"),
                "session_id must be stripped before reaching the original handler");
    }

    @Test
    void nonHandleParamsAreForwardedUnchanged() {
        AtomicReference<McpSchema.CallToolRequest> captured = new AtomicReference<>();
        AsyncToolSpecification s = new AsyncToolSpecification(tool("echo"),
                (ctx, req) -> {
                    captured.set(req);
                    return Mono.just(McpSchema.CallToolResult.builder().addTextContent("ok").build());
                });
        callHandler(s).apply(null, request("echo", "session_id", "start", "query", "test_query")).block();

        assertEquals("test_query", captured.get().arguments().get("query"),
                "non-handle params must be forwarded unchanged");
    }

    @Test
    void contextParamIsStrippedWhenEnabled() {
        AtomicReference<McpSchema.CallToolRequest> captured = new AtomicReference<>();
        AsyncToolSpecification s = new AsyncToolSpecification(tool("ctx_tool"),
                (ctx, req) -> {
                    captured.set(req);
                    return Mono.just(McpSchema.CallToolResult.builder().addTextContent("ok").build());
                });
        callHandler(s).apply(null,
                request("ctx_tool", "session_id", "start", "context", "some intent")).block();

        assertFalse(captured.get().arguments().containsKey("context"),
                "context param must be stripped before reaching the original handler");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Mint-back
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void mintBackTextIsPrependedWhenSessionIdIsMinted() {
        McpSchema.CallToolResult result =
                callHandler(spec("mint_test", "body"))
                        .apply(null, request("mint_test", "session_id", "start"))
                        .block();
        assertNotNull(result);
        assertTrue(result.content().size() >= 2,
                "MINTED result must have mint-back text prepended");
        McpSchema.TextContent first = (McpSchema.TextContent) result.content().get(0);
        assertTrue(first.text().contains("session_id issued"),
                "first content block must announce the issued session_id");
    }

    @Test
    void mintBackIsAbsentWhenSessionIdIsSupplied() {
        McpSchema.CallToolResult result =
                callHandler(spec("supplied_test", "original"))
                        .apply(null, request("supplied_test", "session_id", validSessionId()))
                        .block();
        assertNotNull(result);
        assertEquals(1, result.content().size(),
                "SUPPLIED session must not trigger mint-back");
        assertEquals("original", ((McpSchema.TextContent) result.content().get(0)).text());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Error propagation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void errorFromOriginalHandlerPropagates() {
        AsyncToolSpecification s = new AsyncToolSpecification(tool("error_tool"),
                (ctx, req) -> Mono.error(new RuntimeException("stateless handler exploded")));
        Mono<McpSchema.CallToolResult> mono =
                callHandler(s).apply(null, request("error_tool", "session_id", "start"));
        // User handler errors must propagate — SDK only records analytics as a side-effect.
        // Mirrors TypeScript SDK callWrap.ts: the error is re-thrown so the MCP server handles it.
        RuntimeException thrown = assertThrows(RuntimeException.class, mono::block,
                "user handler error must propagate, not be swallowed");
        assertTrue(thrown.getMessage().contains("stateless handler exploded"));
    }

    @Test
    void errorResultFlagIsPreserved() {
        AsyncToolSpecification s = new AsyncToolSpecification(tool("err_flag"),
                (ctx, req) -> Mono.just(
                        McpSchema.CallToolResult.builder()
                                .addTextContent("fail")
                                .isError(true)
                                .build()));
        McpSchema.CallToolResult result =
                callHandler(s).apply(null, request("err_flag", "session_id", validSessionId())).block();
        assertNotNull(result);
        assertTrue(result.isError(), "isError flag must be preserved through the call handler");
    }
}
