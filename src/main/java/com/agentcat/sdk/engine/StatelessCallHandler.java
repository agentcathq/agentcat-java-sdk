package com.agentcat.sdk.engine;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.modules.handles.MintBackHandler;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import reactor.core.publisher.Mono;

import java.util.function.BiFunction;

import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;

/**
 * Per-tool call handler for stateless MCP servers
 * ({@code McpStatelessSyncServer} / {@code McpStatelessAsyncServer}).
 *
 * <p>On every invocation:
 * <ol>
 *   <li>Fires async hooks ({@code identify}, {@code resolveSessionId}, {@code eventTags},
 *       {@code eventProperties}) concurrently — never delays the response.</li>
 *   <li>Resolves {@code session_id} and {@code agent_id} from incoming arguments.</li>
 *   <li>Strips injected handle parameters before forwarding to the original user handler.</li>
 *   <li>Schedules an analytics event as a fire-and-forget side effect.</li>
 *   <li>Applies mint-back so the agent receives its resolved {@code session_id} in the response.</li>
 * </ol>
 *
 * <p>The original handler is always called regardless of any analytics error.
 * Analytics runs on a background thread and never delays the tool response.
 */
class StatelessCallHandler
        implements BiFunction<McpTransportContext, CallToolRequest, Mono<CallToolResult>> {

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
    StatelessCallHandler(AsyncToolSpecification original,
                         String projectId, String serverName,
                         String serverVersion, AgentCatOptions opts) {
        this(original, projectId, serverName, serverVersion, opts, null, null, null);
    }

    StatelessCallHandler(AsyncToolSpecification original,
                         String projectId, String serverName,
                         String serverVersion, AgentCatOptions opts,
                         InjectedParamsRegistry registry) {
        this(original, projectId, serverName, serverVersion, opts, registry, null, null);
    }

    StatelessCallHandler(AsyncToolSpecification original,
                         String projectId, String serverName,
                         String serverVersion, AgentCatOptions opts,
                         InjectedParamsRegistry registry,
                         DeclaredSessionParamsRegistry declaredSessionParams) {
        this(original, projectId, serverName, serverVersion, opts, registry, declaredSessionParams, null);
    }

    StatelessCallHandler(AsyncToolSpecification original,
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
    public Mono<CallToolResult> apply(McpTransportContext ctx, CallToolRequest request) {
        ToolCallWrapper.PreparedCall call;
        try {
            call = ToolCallWrapper.prepare(request, ctx, toolName, serverName, serverVersion, opts, registry, declaredSessionParams);
        } catch (Throwable t) {
            // Fault containment: SDK error must never break the tool call.
            // Analytics is lost for this invocation; the handler still never sees an
            // injected argument (C# Q7).
            writeToLog("[call] prepare() failed tool=" + toolName + " — falling back to untracked call, error=" + t);
            return original.callHandler().apply(ctx, ToolCallWrapper.handlerRequestOrOriginal(request, toolName, opts, registry));
        }

        Mono<CallToolResult> upstream;
        try {
            upstream = original.callHandler().apply(ctx, call.callArgs().stripped());
        } catch (Throwable t) {
            writeToLog("[call] handler threw synchronously tool=" + toolName + " error=" + t);
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
}
