package com.agentcat.sdk.engine;

import com.agentcat.sdk.AgentCatOptions;
import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;
import com.agentcat.sdk.model.CallArgs;
import com.agentcat.sdk.model.EventData;
import com.agentcat.sdk.model.HandleResolution;
import com.agentcat.sdk.model.PendingHooks;
import com.agentcat.sdk.model.ServerContext;
import com.agentcat.sdk.model.UserIdentity;
import com.agentcat.sdk.modules.events.EventPublisher;
import com.agentcat.sdk.modules.handles.HandleTags;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import com.agentcat.sdk.modules.handles.SessionManager;
import com.agentcat.sdk.tools.GetMoreTools;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Base class for stateful and stateless tool call wrappers.
 *
 * <p>Holds the shared context (project ID, server metadata, options) and provides
 * package-private utility methods used by both {@link StatefulCallHandler} and
 * {@link StatelessCallHandler} on every tool invocation:
 * <ul>
 *   <li>{@link #prepare} — captures args, resolves handles, fires async hooks</li>
 *   <li>{@link #stripHandleArgs} — removes injected AgentCat parameters before forwarding to user handler</li>
 *   <li>{@link #scheduleAnalytics} — fire-and-forget analytics dispatch</li>
 * </ul>
 */
public class ToolCallWrapper {

    /** AgentCat project identifier. */
    protected final String projectId;
    /** MCP server name reported in analytics events. */
    protected final String serverName;
    /** MCP server version reported in analytics events. */
    protected final String serverVersion;
    /** Runtime AgentCat SDK options (injection flags, hooks, etc.). */
    protected final AgentCatOptions opts;

    protected ToolCallWrapper(String projectId, String serverName,
                               String serverVersion, AgentCatOptions opts) {
        this.projectId     = projectId;
        this.serverName    = serverName;
        this.serverVersion = serverVersion;
        this.opts          = opts;
    }

    /** Returns the runtime options this wrapper was configured with. */
    public AgentCatOptions getOptions() { return opts; }

    /**
     * Snapshot of a prepared tool call — computed once per invocation and shared
     * between the handler chain and the analytics side effect.
     *
     * @param startMs             wall-clock time at the start of the call (for duration measurement)
     * @param handles             resolved {@code session_id} / {@code agent_id}
     * @param sdkTags             handle-derived SDK tags ({@code agentcat_session_id_source}, etc.)
     * @param callArgs            tool arguments and intent grouped into a single value object
     * @param serverContext       MCP server and connecting client identifiers
     * @param pendingHooks        async hook futures and redaction functions
     * @param exchangeOrTransport raw MCP exchange/transport, used to populate {@code extra} in event parameters
     */
    record PreparedCall(long startMs,
                        OffsetDateTime callTime,
                        String toolName,
                        HandleResolution handles,
                        Map<String, String> sdkTags,
                        CallArgs callArgs,
                        ServerContext serverContext,
                        PendingHooks pendingHooks,
                        Object exchangeOrTransport) {}

    /**
     * Captures and prepares all per-invocation data in one place so both
     * {@link StatefulCallHandler} and {@link StatelessCallHandler} share the same logic.
     *
     * <p>Async hooks ({@code resolveSessionId}, {@code identify}, {@code eventTags},
     * {@code eventProperties}) are fired immediately — concurrently with the tool handler.
     * Their futures are carried in the returned {@link PreparedCall} and resolved later
     * in the event queue's Stage 0, so they never delay the MCP response.
     *
     * @param request       the raw tool call request (includes injected handle parameters)
     * @param exchangeOrTransport         the MCP server exchange or transport context, forwarded to hooks
     * @param toolName      the name of the tool being called
     * @param serverName    MCP server name reported in analytics events
     * @param serverVersion MCP server version reported in analytics events
     * @param opts          AgentCat runtime options
     */
    /** Backward-compatible overload — uses legacy blind-strip (no registry). */
    static PreparedCall prepare(CallToolRequest request, Object exchangeOrTransport,
                                String toolName, String serverName, String serverVersion,
                                AgentCatOptions opts) {
        return prepare(request, exchangeOrTransport, toolName, serverName, serverVersion, opts, null, null);
    }

    static PreparedCall prepare(CallToolRequest request, Object exchangeOrTransport,
                                String toolName, String serverName, String serverVersion,
                                AgentCatOptions opts, InjectedParamsRegistry registry) {
        return prepare(request, exchangeOrTransport, toolName, serverName, serverVersion, opts, registry, null);
    }

    static PreparedCall prepare(CallToolRequest request, Object exchangeOrTransport,
                                String toolName, String serverName, String serverVersion,
                                AgentCatOptions opts, InjectedParamsRegistry registry,
                                DeclaredSessionParamsRegistry declaredSessionParams) {
        long startMs               = System.currentTimeMillis();
        OffsetDateTime callTime    = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> raw = captureArgs(request);

        // Fire async hooks immediately — fire-and-forget, parallel with tool handler.
        // Each future has an attached error handler so it never causes an unhandled rejection.
        CompletableFuture<String>              pendingSessionId  = null;
        CompletableFuture<UserIdentity>        pendingIdentity   = null;
        CompletableFuture<Map<String, String>> pendingTags       = null;
        CompletableFuture<Map<String, Object>> pendingProperties = null;

        if (opts.getResolveSessionId() != null) {
            pendingSessionId = invokeHook("resolveSessionId",
                    () -> opts.getResolveSessionId().resolveSessionId(request, exchangeOrTransport));
        }
        if (opts.getIdentify() != null) {
            pendingIdentity = invokeHook("identify",
                    () -> opts.getIdentify().identify(request, exchangeOrTransport));
        }
        if (opts.getEventTags() != null) {
            pendingTags = invokeHook("eventTags",
                    () -> opts.getEventTags().tags(request, exchangeOrTransport));
        }
        if (opts.getEventProperties() != null) {
            pendingProperties = invokeHook("eventProperties",
                    () -> opts.getEventProperties().properties(request, exchangeOrTransport));
        }

        // Capture userIntent from raw args BEFORE stripping — context is removed by stripHandleArgs.
        String userIntent = captureUserIntent(raw, toolName, opts);

        // Extract connecting client identity.
        // Stateful path: read from McpAsyncServerExchange (public API + reflection fallback).
        // Stateless path: read from request._meta["io.modelcontextprotocol/clientInfo"]
        //   which the MCP SDK injects into every request automatically — same approach as TypeScript SDK.
        String clientName    = null;
        String clientVersion = null;
        if (exchangeOrTransport instanceof McpAsyncServerExchange exchange) {
            McpSchema.Implementation clientInfo = exchange.getClientInfo();
            if (clientInfo == null) {
                // Fallback for stdio transport: getClientInfo() may return null if the
                // exchange was constructed before the initialize handshake completed.
                clientInfo = ReflectionUtils.extractClientInfo(exchange, toolName);
            }
            if (clientInfo != null) {
                clientName    = clientInfo.name();
                clientVersion = clientInfo.version();
            }
        } else {
            // Stateless path: clientInfo travels in _meta on every request.
            String[] fromMeta = extractClientInfoFromMeta(request, toolName);
            clientName    = fromMeta[0];
            clientVersion = fromMeta[1];
        }

        // A session_id is the customer's only when their own schema declared it —
        // recorded at the collision site during listing. Everything else is ours,
        // including tools we skipped for schema shape (oneOf/allOf/anyOf).
        boolean sessionParamIsOurs = !(toolName != null
                && declaredSessionParams != null
                && declaredSessionParams.has(toolName));
        HandleResolution handles   = SessionManager.resolveHandles(raw, toolName, opts, sessionParamIsOurs);
        Map<String, String> sdkTags = HandleTags.buildHandleTags(handles);
        return new PreparedCall(
                startMs, callTime, toolName, handles, sdkTags,
                new CallArgs(raw, handlerRequest(request, raw, toolName, opts, registry), userIntent),
                new ServerContext(serverName, serverVersion, clientName, clientVersion),
                new PendingHooks(opts.getRedactSensitiveInformation(), opts.getRedactEvent(),
                        pendingSessionId, pendingIdentity, pendingTags, pendingProperties),
                exchangeOrTransport);
    }

    /**
     * Returns an immutable snapshot of the incoming tool call arguments.
     * Returns an empty map when the request carries no arguments.
     */
    static Map<String, Object> captureArgs(CallToolRequest request) {
        if (request.arguments() == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(request.arguments()));
    }

    /**
     * The request the customer's handler receives: the client's request itself when
     * AgentCat injected nothing that the call carries, otherwise a copy with the injected
     * names removed that keeps {@code _meta}, the argument order, and a mutable map.
     */
    static CallToolRequest handlerRequest(CallToolRequest request, Map<String, Object> raw,
                                          String toolName, AgentCatOptions opts,
                                          InjectedParamsRegistry registry) {
        Map<String, Object> stripped = stripHandleArgs(raw, toolName, opts, registry);
        if (stripped == raw) {
            return request;
        }
        return new CallToolRequest(request.name(), stripped, request.meta());
    }

    /**
     * The handler request for the degraded path, when {@link #prepare} failed: the same
     * strip rule, so a tool never sees an injected argument (C# Q7). Falls back to the
     * original request if even this fails.
     */
    static CallToolRequest handlerRequestOrOriginal(CallToolRequest request, String toolName,
                                                    AgentCatOptions opts, InjectedParamsRegistry registry) {
        try {
            return handlerRequest(request, captureArgs(request), toolName, opts, registry);
        } catch (Throwable t) {
            writeToLog("[call] strip failed tool=" + toolName + " — forwarding the request unchanged, error=" + t);
            return request;
        }
    }

    /**
     * Extracts the user intent from raw tool call arguments before they are stripped.
     *
     * <ul>
     *   <li>For {@code get_more_tools}: reads the {@code context} field (owned by the tool).</li>
     *   <li>For all other tools: reads the injected {@code context} parameter when
     *       {@link AgentCatOptions#enableToolCallContext} is {@code true}.</li>
     * </ul>
     *
     * <p>Must be called on the <em>raw</em> (unstripped) args — the context parameter
     * is removed by {@link #stripHandleArgs} before analytics runs.
     */
    private static String captureUserIntent(Map<String, Object> raw,
                                             String toolName,
                                             AgentCatOptions opts) {
        if (GetMoreTools.GET_MORE_TOOLS_NAME.equals(toolName)) {
            return GetMoreTools.extractUserIntent(raw);
        }
        if (opts.isEnableToolCallContext()) {
            Object ctx = raw.get(ParameterInjector.CONTEXT_PARAM);
            if (ctx instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /** Backward-compatible overload — uses legacy blind-strip (no registry). */
    static Map<String, Object> stripHandleArgs(Map<String, Object> args,
                                                String toolName,
                                                AgentCatOptions opts) {
        return stripHandleArgs(args, toolName, opts, null);
    }

    /**
     * Returns a copy of {@code args} with AgentCat handle parameters removed so that
     * user handler code never sees them.
     *
     * <p>When a non-null {@code registry} is supplied, only parameters that AgentCat actually
     * injected into this tool's schema are stripped — customer-owned parameters with the same
     * name are left untouched. This mirrors the TypeScript SDK's {@code stripInjectedArguments}
     * which uses an {@code InjectedParamsRegistry} for the same purpose.
     *
     * <p>When {@code registry} is {@code null} or has no entry for {@code toolName} (e.g. for
     * tools registered before AgentCat was applied), the legacy blind-strip behaviour is used
     * as a fallback so the guard still applies.
     *
     * <p>Returns the original map unchanged when there is nothing to strip, avoiding allocation.
     */
    static Map<String, Object> stripHandleArgs(Map<String, Object> args,
                                                String toolName,
                                                AgentCatOptions opts,
                                                InjectedParamsRegistry registry) {
        if (args.isEmpty()) {
            return args;
        }

        boolean useRegistry = registry != null && registry.has(toolName);
        if (!useRegistry && !opts.isEnableTracing()) {
            // Legacy blind strip only applies to tools never seen by the injector.
            return args;
        }

        boolean shouldStripSession;
        boolean shouldStripAgent;
        boolean shouldStripContext;

        if (useRegistry) {
            // Precise: only strip what was recorded as injected for this tool.
            var injected = registry.get(toolName);
            shouldStripSession = injected.contains(ParameterInjector.SESSION_ID_PARAM)
                    && args.containsKey(ParameterInjector.SESSION_ID_PARAM);
            shouldStripAgent   = injected.contains(ParameterInjector.AGENT_ID_PARAM)
                    && args.containsKey(ParameterInjector.AGENT_ID_PARAM);
            shouldStripContext = injected.contains(ParameterInjector.CONTEXT_PARAM)
                    && args.containsKey(ParameterInjector.CONTEXT_PARAM);
        } else {
            // Fallback: legacy blind-strip (pre-registry behaviour).
            shouldStripSession = args.containsKey(ParameterInjector.SESSION_ID_PARAM);
            shouldStripAgent   = opts.isEnableAgentTracking()
                    && args.containsKey(ParameterInjector.AGENT_ID_PARAM);
            shouldStripContext = opts.isEnableToolCallContext()
                    && !GetMoreTools.GET_MORE_TOOLS_NAME.equals(toolName)
                    && args.containsKey(ParameterInjector.CONTEXT_PARAM);
        }

        if (!shouldStripSession && !shouldStripAgent && !shouldStripContext) {
            return args;
        }

        // Mutable and in the client's order: the handler gets what it would get without AgentCat.
        Map<String, Object> stripped = new LinkedHashMap<>(args);
        if (shouldStripSession) stripped.remove(ParameterInjector.SESSION_ID_PARAM);
        if (shouldStripAgent)   stripped.remove(ParameterInjector.AGENT_ID_PARAM);
        if (shouldStripContext) stripped.remove(ParameterInjector.CONTEXT_PARAM);
        return stripped;
    }

    /**
     * Submits an analytics event to {@link EventPublisher} on a background thread.
     * Never blocks the caller — exceptions are swallowed and logged.
     */
    static void scheduleAnalytics(String projectId, AgentCatOptions opts, PreparedCall call,
                                   CallToolResult result, Throwable error) {
        CompletableFuture
                .runAsync(() -> publishAnalytics(projectId, call, result, error))
                .exceptionally(t -> null); // Catches Errors that escape catch(Exception) in runAsync
    }

    private static void publishAnalytics(String projectId, PreparedCall call,
                                          CallToolResult result, Throwable error) {
        try {
            String toolName    = call.toolName();
            long duration      = System.currentTimeMillis() - call.startMs();
            boolean isError    = error != null
                    || Boolean.TRUE.equals(result != null ? result.isError() : null);
            String errorMessage = (error != null) ? error.getMessage() : null;

            EventData eventData = new EventData(
                    projectId, call.handles().getSessionId(), toolName,
                    buildParameters(toolName, call.callArgs().rawArgs(), call.exchangeOrTransport()),
                    EventPublisher.toMap(result),
                    duration, isError, errorMessage, call.callArgs().userIntent(), call.sdkTags(),
                    call.callTime());
            EventPublisher.publishEvent(eventData, call.serverContext(), call.pendingHooks(),
                    error != null ? error.getClass().getName() : null);
        } catch (Throwable e) {
            writeToLog("analytics error [" + call.toolName() + "]: "
                    + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    /**
     * Builds the {@code parameters} payload for the analytics event, mirroring the
     * TypeScript SDK's {@code { request, extra }} structure.
     *
     * <p>{@code request} contains the MCP method and params (tool name + arguments).
     * {@code extra} contains data extractable from the exchange; fields that are
     * unavailable in the Java MCP SDK (request id, jsonrpc version, JS functions,
     * AbortSignal) are omitted — see docs/parameters-parity.md for the gap list.
     */
    private static Map<String, Object> buildParameters(String toolName,
                                                        Map<String, Object> rawArgs,
                                                        Object exchangeOrTransport) {
        // request section
        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        params.put("arguments", rawArgs);

        Map<String, Object> request = new HashMap<>();
        request.put("method", "tools/call");
        request.put("params", params);

        Map<String, Object> result = new HashMap<>();
        result.put("request", request);

        // extra section — only real data from the exchange
        Map<String, Object> extra = buildExtra(exchangeOrTransport);
        result.put("extra", extra);

        return Collections.unmodifiableMap(result);
    }

    /**
     * Extracts serialisable fields from {@code McpAsyncServerExchange} into the
     * {@code extra} map. Never throws — a failure must not cost the event.
     */
    private static Map<String, Object> buildExtra(Object exchangeOrTransport) {
        if (!(exchangeOrTransport instanceof McpAsyncServerExchange exchange)) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> mcpReq = new HashMap<>();
            mcpReq.put("method", "tools/call");

            String sid = exchange.sessionId();
            if (sid != null) mcpReq.put("sessionId", sid);

            Map<String, Object> extra = new HashMap<>();
            extra.put("mcpReq", mcpReq);
            return extra;
        } catch (Exception e) {
            writeToLog("[EQ] buildExtra failed: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ── Hook invocation helpers ───────────────────────────────────────────────

    /**
     * Invokes a hook supplier, attaches an error handler so the returned future
     * never causes an unhandled rejection, and swallows synchronous throws.
     * Returns a future that always resolves to either a value or {@code null}.
     */
    private static <T> CompletableFuture<T> invokeHook(String hookName,
                                                         Supplier<CompletableFuture<T>> supplier) {
        try {
            CompletableFuture<T> future = supplier.get();
            if (future == null) return CompletableFuture.completedFuture(null);
            return future.exceptionally(e -> {
                writeToLog("Hook '" + hookName + "' error: "
                        + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                return null;
            });
        } catch (Exception e) {
            writeToLog("Hook '" + hookName + "' threw synchronously: " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Reads clientInfo from request._meta["io.modelcontextprotocol/clientInfo"].
     *
     * <p>The MCP SDK injects this key into every tool call request automatically,
     * mirroring the TypeScript SDK approach (session.ts getClientInfoForRequest).
     *
     * @return String[2] — [0]=clientName, [1]=clientVersion, both nullable
     */
    private static String[] extractClientInfoFromMeta(CallToolRequest request, String toolName) {
        Map<String, Object> meta = request.meta();
        if (meta == null) {
            return new String[]{null, null};
        }

        Object raw = meta.get("io.modelcontextprotocol/clientInfo");
        if (!(raw instanceof Map<?, ?> ciMap)) {
            return new String[]{null, null};
        }

        String name    = ciMap.get("name")    instanceof String s ? s : null;
        String version = ciMap.get("version") instanceof String s ? s : null;
        return new String[]{name, version};
    }
}
