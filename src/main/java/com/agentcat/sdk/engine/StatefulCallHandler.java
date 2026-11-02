package com.agentcat.sdk.engine;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.modules.handles.MintBackHandler;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.BiFunction;

import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;

/**
 * Per-tool call handler for stateful MCP servers ({@code McpSyncServer} / {@code McpAsyncServer}).
 */
class StatefulCallHandler
        implements BiFunction<McpAsyncServerExchange, CallToolRequest, Mono<CallToolResult>> {

    private final AsyncToolSpecification original;
    private final String toolName;
    private final String projectId;
    private final String serverName;
    private final String serverVersion;
    private final AgentCatOptions opts;
    private final InjectedParamsRegistry registry;
    private final DeclaredSessionParamsRegistry declaredSessionParams;
    private final OutputInjectionRegistry outputRegistry;

    /** Backward-compatible constructor — uses legacy blind-strip (no registry). */
    StatefulCallHandler(AsyncToolSpecification original,
                        String projectId, String serverName,
                        String serverVersion, AgentCatOptions opts) {
        this(original, projectId, serverName, serverVersion, opts, null, null, null);
    }

    StatefulCallHandler(AsyncToolSpecification original,
                        String projectId, String serverName,
                        String serverVersion, AgentCatOptions opts,
                        InjectedParamsRegistry registry) {
        this(original, projectId, serverName, serverVersion, opts, registry, null, null);
    }

    StatefulCallHandler(AsyncToolSpecification original,
                        String projectId, String serverName,
                        String serverVersion, AgentCatOptions opts,
                        InjectedParamsRegistry registry,
                        DeclaredSessionParamsRegistry declaredSessionParams) {
        this(original, projectId, serverName, serverVersion, opts, registry, declaredSessionParams, null);
    }

    StatefulCallHandler(AsyncToolSpecification original,
                        String projectId, String serverName,
                        String serverVersion, AgentCatOptions opts,
                        InjectedParamsRegistry registry,
                        DeclaredSessionParamsRegistry declaredSessionParams,
                        OutputInjectionRegistry outputRegistry) {
        this.original               = original;
        this.toolName               = original.tool().name();
        this.projectId              = projectId;
        this.serverName             = serverName;
        this.serverVersion          = serverVersion;
        this.opts                   = opts;
        this.registry               = registry;
        this.declaredSessionParams  = declaredSessionParams;
        this.outputRegistry         = outputRegistry;
    }

    @Override
    public Mono<CallToolResult> apply(McpAsyncServerExchange exchange, CallToolRequest request) {
        ToolCallWrapper.PreparedCall call;
        try {
            call = ToolCallWrapper.prepare(request, exchange, toolName, serverName, serverVersion, opts, registry, declaredSessionParams);
        } catch (Throwable t) {
            // Fault containment: SDK error must never break the tool call.
            // Analytics is lost for this invocation; the handler still never sees an
            // injected argument (C# Q7).
            writeToLog("[call] prepare() failed tool=" + toolName + " — falling back to untracked call, error=" + t);
            return invokeOriginal(exchange, ToolCallWrapper.handlerRequestOrOriginal(request, toolName, opts, registry));
        }

        Mono<CallToolResult> upstream;
        try {
            upstream = invokeOriginal(exchange, call.callArgs().stripped());
        } catch (Throwable t) {
            writeToLog("[call] invokeOriginal() threw synchronously tool=" + toolName + " error=" + t);
            upstream = Mono.error(t);
        }

        return upstream
                .doOnNext(result -> {
                    if (opts.isEnableTracing()) {
                        ToolCallWrapper.scheduleAnalytics(projectId, opts, call, result, null);
                    }
                })
                .doOnError(error -> {
                    // Record analytics for user handler errors, then let the error propagate.
                    // Mirrors TypeScript SDK callWrap.ts: event is published with isError=true,
                    // then the error is re-thrown so the MCP server handles it normally.
                    if (opts.isEnableTracing()) {
                        writeToLog("[call] error tool=" + toolName + " error=" + error);
                        ToolCallWrapper.scheduleAnalytics(projectId, opts, call, null, error);
                    }
                })
                .map(result -> MintBackHandler.applyMintBack(result, call.handles(), opts, toolName, outputRegistry));
    }

    /**
     * Invokes the original tool handler, handling the 0.18.x dual-slot architecture.
     */
    @SuppressWarnings("unchecked")
    private Mono<CallToolResult> invokeOriginal(McpAsyncServerExchange exchange, CallToolRequest request) {
        BiFunction<McpAsyncServerExchange, CallToolRequest, Mono<CallToolResult>> handler = original.callHandler();
        if (handler != null) {
            return handler.apply(exchange, request);
        }
        // 0.18.x fallback: handler is in the 'call' slot (Map-based BiFunction).
        try {
            Method callMethod = original.getClass().getMethod("call");
            Object rawCallFn = callMethod.invoke(original);
            BiFunction<McpAsyncServerExchange, Map<String, Object>, Mono<CallToolResult>> callFn =
                    (BiFunction<McpAsyncServerExchange, Map<String, Object>, Mono<CallToolResult>>) rawCallFn;
            if (callFn != null) {
                return callFn.apply(exchange, request.arguments());
            }
        } catch (Throwable t) {
            // fallback failed — fall through to error
        }
        return Mono.error(new IllegalStateException("No handler registered for tool: " + toolName));
    }
}
