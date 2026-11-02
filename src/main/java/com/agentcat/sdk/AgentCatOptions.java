package com.agentcat.sdk;

import com.agentcat.sdk.model.UserIdentity;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Optional configuration for {@link AgentCat#track}.
 *
 * <p>All fields have safe defaults: tracing on, agent-id and context injection off,
 * no hooks configured. Pass an instance to the three-argument {@code track()} overload
 * only when deviating from the defaults.
 *
 * <h2>Hook contract</h2>
 * All hooks ({@link #getIdentify()}, {@link #getResolveSessionId()}, {@link #getEventTags()},
 * {@link #getEventProperties()}) are <em>fire-and-forget</em>: they are started
 * concurrently with the tool handler and never delay the MCP response. A hook
 * that throws, times out, or returns {@code null} is silently ignored — the event
 * is published without that hook's data. Exceptions are logged to
 * {@code ~/agentcat.log}.
 */
public final class AgentCatOptions {

    // ── Feature flags ────────────────────────────────────────────────────────

    /**
     * Set to {@code false} to disable all AgentCat tracing. Tool handlers are called
     * directly with no wrapping and no parameters are injected into schemas.
     */
    private boolean enableTracing = true;

    /**
     * When {@code true}, an {@code agent_id} parameter is injected into every tool's
     * input schema so agents can supply a stable self-identifier for correlation.
     */
    private boolean enableAgentTracking = false;

    /**
     * When {@code true}, a {@code context} parameter is injected into every tool's input
     * schema (except {@code get_more_tools}) asking the agent to describe in 15–25 words
     * why it is making the call.
     *
     * <p>Defaults to {@code true} — matches the TypeScript SDK default.
     */
    private boolean enableToolCallContext = true;

    /**
     * When {@code true}, suppresses the anonymized diagnostic signal sent to AgentCat on
     * setup or runtime errors. Can also be set via the {@code DISABLE_DIAGNOSTICS}
     * environment variable. Your local {@code ~/agentcat.log} is unaffected.
     */
    private boolean disableDiagnostics = false;

    /**
     * When {@code true} (default), the {@code get_more_tools} tool is registered automatically.
     * Set to {@code false} to suppress it — useful when the host application already surfaces
     * a feedback mechanism or when the tool list is curated and complete.
     */
    private boolean enableReportMissing = true;

    /**
     * Custom description for the injected {@code context} parameter.
     * When {@code null} (default), the built-in 15–25-word prompt from the TypeScript SDK
     * is used verbatim. Override only when the default wording does not suit the domain
     * (e.g. a different word count or phrasing is needed for a specific agent).
     */
    private String customContextDescription = null;

    // ── Identity hook ─────────────────────────────────────────────────────────

    /**
     * Called on every tool invocation to identify the actor behind the call.
     * The returned {@link UserIdentity} is stamped onto the analytics event as
     * {@code identifyActorGivenId}, {@code identifyActorName}, and {@code identifyData}.
     *
     * <p>Runs concurrently with the tool handler — never delays the MCP response.
     * Return {@code null} (or a future that resolves to {@code null}) to skip identity
     * for this call. Exceptions are logged and treated as {@code null}.
     *
     * <p>Example:
     * <pre>{@code
     * opts.setIdentify((request, extra) ->
     *     CompletableFuture.supplyAsync(() -> {
     *         String token = (String) request.arguments().get("auth_token");
     *         User user = userService.fromToken(token);
     *         return new UserIdentity(user.id(), user.name(), Map.of("plan", user.plan()));
     *     }));
     * }</pre>
     */
    private IdentifyFunction identify = null;

    // ── Session hook ─────────────────────────────────────────────────────────

    /**
     * Hook mode: the customer supplies a session identifier from their own context
     * (e.g. an auth token, workflow ID, or request header) and AgentCat steps back.
     *
     * <p>When configured:
     * <ul>
     *   <li>The {@code session_id} parameter is <em>not</em> injected into tool schemas.</li>
     *   <li>No mint-back text is added to tool responses.</li>
     *   <li>The returned value is combined with the project ID via SHA-256 into a
     *       deterministic KSUID — the same input always maps to the same session.</li>
     *   <li>Returning {@code null} silently mints a new random session (avoid: the
     *       agent can never learn a silently minted ID).</li>
     * </ul>
     *
     * <p>Runs concurrently with the tool handler — never delays the MCP response.
     *
     * <p>Example:
     * <pre>{@code
     * opts.setResolveSessionId((request, extra) ->
     *     CompletableFuture.supplyAsync(() -> authContext.currentUserId()));
     * }</pre>
     */
    private ResolveSessionIdFunction resolveSessionId = null;

    // ── Redaction hooks ───────────────────────────────────────────────────────

    /**
     * Applied to every string value inside {@code params} and {@code response} before
     * the event is sent. Return a future resolving to the redacted string, or the original
     * to leave it unchanged.
     *
     * <p>Runs concurrently with the tool handler on the event queue worker thread —
     * never delays the MCP response. A hook that throws, times out, or returns
     * {@code null} leaves the original string unchanged.
     *
     * <p>Example — mask credit card numbers:
     * <pre>{@code
     * opts.setRedactSensitiveInformation(s ->
     *     CompletableFuture.completedFuture(s.replaceAll("\\b\\d{16}\\b", "****")));
     * }</pre>
     */
    private Function<String, CompletableFuture<String>> redactSensitiveInformation = null;

    /**
     * Applied to the full event map just before it is sent to the AgentCat API.
     * Return a future resolving to the (possibly modified) map to allow the event,
     * or {@code null} to drop it.
     *
     * <p>The map contains: {@code projectId}, {@code sessionId}, {@code toolName},
     * {@code params}, {@code response}, {@code durationMs}, {@code isError},
     * and optionally {@code userIntent}.
     *
     * <p>Runs concurrently with the tool handler on the event queue worker thread —
     * never delays the MCP response.
     *
     * <p>Example — drop events for a specific tool:
     * <pre>{@code
     * opts.setRedactEvent(event -> CompletableFuture.completedFuture(
     *     "internal_tool".equals(event.get("toolName")) ? null : event));
     * }</pre>
     */
    private Function<Map<String, Object>, CompletableFuture<Map<String, Object>>> redactEvent = null;

    // ── Metadata hooks ────────────────────────────────────────────────────────

    /**
     * Called on every tool invocation to attach string key-value tags to the event.
     * Tags are indexed in the AgentCat dashboard and are ideal for structured metadata
     * you want to filter or group by (environment, region, trace IDs, etc.).
     *
     * <p>Validation rules (invalid entries are silently dropped and logged):
     * <ul>
     *   <li>Key: ≤ 32 chars, matching {@code [a-zA-Z0-9$_.:\- ]+}</li>
     *   <li>Value: ≤ 200 chars, no newline characters</li>
     *   <li>Maximum 50 entries per event</li>
     * </ul>
     *
     * <p>Runs concurrently with the tool handler — never delays the MCP response.
     * Return {@code null} (or a future resolving to {@code null}) to skip tags.
     *
     * <p>Example:
     * <pre>{@code
     * opts.setEventTags((request, extra) ->
     *     CompletableFuture.completedFuture(Map.of(
     *         "env", "production",
     *         "region", "eu-west-1"
     *     )));
     * }</pre>
     */
    private EventTagsFunction eventTags = null;

    /**
     * Called on every tool invocation to attach arbitrary JSON metadata to the event.
     * Unlike tags, properties have no format constraints — any JSON-serialisable value is accepted.
     *
     * <p>Runs concurrently with the tool handler — never delays the MCP response.
     * Return {@code null} (or a future resolving to {@code null}) to skip properties.
     *
     * <p>Example:
     * <pre>{@code
     * opts.setEventProperties((request, extra) ->
     *     CompletableFuture.completedFuture(Map.of(
     *         "device", "desktop",
     *         "featureFlags", List.of("dark_mode", "beta_ui")
     *     )));
     * }</pre>
     */
    private EventPropertiesFunction eventProperties = null;

    public AgentCatOptions() {}

    // ── Getters ───────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when diagnostics are disabled — either because
     * {@link #setDisableDiagnostics(boolean)} was called with {@code true}, or because
     * the {@code DISABLE_DIAGNOSTICS} environment variable is set to a truthy value
     * ({@code 1}, {@code true}, {@code yes}, {@code on}).
     */
    public boolean isDisableDiagnostics() {
        if (disableDiagnostics) return true;
        String env = System.getenv("DISABLE_DIAGNOSTICS");
        if (env == null) return false;
        String v = env.trim().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    /** @see #enableTracing */
    public boolean isEnableTracing() { return enableTracing; }

    /** @see #enableAgentTracking */
    public boolean isEnableAgentTracking() { return enableAgentTracking; }

    /** @see #enableToolCallContext */
    public boolean isEnableToolCallContext() { return enableToolCallContext; }

    /** @see #enableReportMissing */
    public boolean isEnableReportMissing() { return enableReportMissing; }

    /** @see #customContextDescription */
    public String getCustomContextDescription() { return customContextDescription; }

    /** @see #identify */
    public IdentifyFunction getIdentify() { return identify; }

    /** @see #resolveSessionId */
    public ResolveSessionIdFunction getResolveSessionId() { return resolveSessionId; }

    /** @see #redactSensitiveInformation */
    public Function<String, CompletableFuture<String>> getRedactSensitiveInformation() {
        return redactSensitiveInformation;
    }

    /** @see #redactEvent */
    public Function<Map<String, Object>, CompletableFuture<Map<String, Object>>> getRedactEvent() {
        return redactEvent;
    }

    /** @see #eventTags */
    public EventTagsFunction getEventTags() { return eventTags; }

    /** @see #eventProperties */
    public EventPropertiesFunction getEventProperties() { return eventProperties; }

    // ── Setters ───────────────────────────────────────────────────────────────

    /** @see #disableDiagnostics */
    public AgentCatOptions setDisableDiagnostics(boolean disableDiagnostics) { this.disableDiagnostics = disableDiagnostics; return this; }

    /** @see #enableTracing */
    public AgentCatOptions setEnableTracing(boolean enableTracing) { this.enableTracing = enableTracing; return this; }

    /** @see #enableAgentTracking */
    public AgentCatOptions setEnableAgentTracking(boolean enableAgentTracking) { this.enableAgentTracking = enableAgentTracking; return this; }

    /** @see #enableToolCallContext */
    public AgentCatOptions setEnableToolCallContext(boolean enableToolCallContext) { this.enableToolCallContext = enableToolCallContext; return this; }

    /** @see #enableReportMissing */
    public AgentCatOptions setEnableReportMissing(boolean enableReportMissing) { this.enableReportMissing = enableReportMissing; return this; }

    /** @see #customContextDescription */
    public AgentCatOptions setCustomContextDescription(String customContextDescription) { this.customContextDescription = customContextDescription; return this; }

    /** @see #identify */
    public AgentCatOptions setIdentify(IdentifyFunction identify) { this.identify = identify; return this; }

    /** @see #resolveSessionId */
    public AgentCatOptions setResolveSessionId(ResolveSessionIdFunction resolveSessionId) { this.resolveSessionId = resolveSessionId; return this; }

    /** @see #redactSensitiveInformation */
    public AgentCatOptions setRedactSensitiveInformation(Function<String, CompletableFuture<String>> redactSensitiveInformation) {
        this.redactSensitiveInformation = redactSensitiveInformation; return this;
    }

    /** @see #redactEvent */
    public AgentCatOptions setRedactEvent(Function<Map<String, Object>, CompletableFuture<Map<String, Object>>> redactEvent) {
        this.redactEvent = redactEvent; return this;
    }

    /** @see #eventTags */
    public AgentCatOptions setEventTags(EventTagsFunction eventTags) { this.eventTags = eventTags; return this; }

    /** @see #eventProperties */
    public AgentCatOptions setEventProperties(EventPropertiesFunction eventProperties) { this.eventProperties = eventProperties; return this; }

    // ── Hook functional interfaces ────────────────────────────────────────────

    /**
     * Hook that identifies the actor behind each tool call.
     *
     * @see #getIdentify()
     */
    @FunctionalInterface
    public interface IdentifyFunction {
        /**
         * @param request the raw {@link CallToolRequest} (including injected handle parameters)
         * @param extra   the MCP server exchange/context — cast to
         *                {@code McpAsyncServerExchange} (stateful) or
         *                {@code McpTransportContext} (stateless) as needed
         * @return a future resolving to the actor's identity, or {@code null} to skip
         */
        CompletableFuture<UserIdentity> identify(CallToolRequest request, Object extra);
    }

    /**
     * Hook that supplies a custom session identifier per request.
     *
     * @see #getResolveSessionId()
     */
    @FunctionalInterface
    public interface ResolveSessionIdFunction {
        /**
         * @param request the raw {@link CallToolRequest}
         * @param extra   the MCP server exchange/context
         * @return a future resolving to a string (hashed into a deterministic KSUID),
         *         or {@code null} to mint silently
         */
        CompletableFuture<String> resolveSessionId(CallToolRequest request, Object extra);
    }

    /**
     * Hook that attaches validated string tags to the event.
     *
     * @see #getEventTags()
     */
    @FunctionalInterface
    public interface EventTagsFunction {
        /**
         * @param request the raw {@link CallToolRequest}
         * @param extra   the MCP server exchange/context
         * @return a future resolving to a tag map, or {@code null} to skip
         */
        CompletableFuture<Map<String, String>> tags(CallToolRequest request, Object extra);
    }

    /**
     * Hook that attaches arbitrary JSON metadata to the event.
     *
     * @see #getEventProperties()
     */
    @FunctionalInterface
    public interface EventPropertiesFunction {
        /**
         * @param request the raw {@link CallToolRequest}
         * @param extra   the MCP server exchange/context
         * @return a future resolving to a properties map, or {@code null} to skip
         */
        CompletableFuture<Map<String, Object>> properties(CallToolRequest request, Object extra);
    }
}
