package com.agentcat.sdk.engine;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.lang.reflect.Constructor;
import java.util.function.BiFunction;


/**
 * Creates {@link AsyncToolSpecification} instances with the handler correctly placed
 * in the {@code callHandler} slot across all MCP SDK versions (0.18.x, 1.x, 2.x).
 *
 * <h3>0.18.x dual-slot architecture</h3>
 * SDK 0.18.x introduced two parallel slots on {@code AsyncToolSpecification}:
 * <ul>
 *   <li>{@code call} — {@code BiFunction<exchange, Map<String,Object>, Mono>} (Map-based)</li>
 *   <li>{@code callHandler} — {@code BiFunction<exchange, CallToolRequest, Mono>} (typed)</li>
 * </ul>
 * The 2-arg constructor {@code (Tool, BiFunction)} fills the {@code call} slot, leaving
 * {@code callHandler} null. The 3-arg constructor {@code (Tool, call, callHandler)} allows
 * explicit placement. This factory always targets {@code callHandler} so that
 * {@link StatefulCallHandler} can retrieve it reliably via {@code spec.callHandler()}.
 *
 * <p>SDK 1.x and 2.x have only {@code callHandler}; the 2-arg constructor is correct there.
 */
public final class AsyncToolSpecFactory {

    private AsyncToolSpecFactory() {}

    /**
     * Builds an {@link AsyncToolSpecification} placing {@code handler} in the
     * {@code callHandler} slot, regardless of which SDK version is on the classpath.
     *
     * @param tool    the tool descriptor
     * @param handler the call handler ({@code BiFunction<exchange, CallToolRequest, Mono>})
     * @return a correctly wired specification ready for wrapping or direct use
     */
    @SuppressWarnings("unchecked")
    public static AsyncToolSpecification buildSpec(
            McpSchema.Tool tool,
            BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> handler) {
        try {
            // In SDK 0.18.x the 2-arg constructor silently puts the handler in the `call`
            // slot (Map-based), leaving `callHandler` null — so spec.callHandler() returns
            // null and our handler is never invoked. The 3-arg constructor lets us pass
            // null for `call` and put the handler directly into `callHandler`.
            Constructor<?> ctor3 = AsyncToolSpecification.class
                    .getDeclaredConstructor(McpSchema.Tool.class, BiFunction.class, BiFunction.class);
            return (AsyncToolSpecification) ctor3.newInstance(tool, null, handler);
        } catch (NoSuchMethodException e) {
            // SDK 1.x/2.x: only one slot, 2-arg constructor is correct.
            return new AsyncToolSpecification(tool, handler);
        } catch (ReflectiveOperationException e) {
            return new AsyncToolSpecification(tool, handler);
        }
    }
}
