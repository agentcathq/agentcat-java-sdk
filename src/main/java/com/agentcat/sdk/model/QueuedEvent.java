package com.agentcat.sdk.model;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Snapshot of a tool call event ready to be processed by the event queue.
 *
 * <p>Carries the event data, resolved identity, tags/properties, server context,
 * and the hook closures configured by the user so each event is processed with
 * the options that were active at the time of the call.
 *
 * <h2>Pending futures</h2>
 * Hooks are fired fire-and-forget at call time (concurrently with the tool handler)
 * and their results land in {@link PendingHooks}. The event queue resolves them in
 * Stage 0 before the event enters the redaction/truncation pipeline.
 */
public class QueuedEvent {

    // ── Core event data ───────────────────────────────────────────────────────

    private String projectId;
    private String sessionId;
    private String toolName;
    private Map<String, Object> params;
    private Map<String, Object> response;
    private long durationMs;
    private boolean isError;
    private String errorMessage;
    /** Fully qualified class name of the thrown exception (PHP D10), or {@code null}. */
    private String errorType;
    private String userIntent;

    // ── Resolved identity (populated from pendingHooks in Stage 0) ────────────

    private UserIdentity actor;

    // ── Resolved metadata (populated from pendingHooks in Stage 0) ───────────

    private Map<String, String> tags;
    private Map<String, Object> properties;

    /**
     * SDK-internal handle tags ({@code agentcat_session_id_source}, etc.).
     * These are merged over customer tags in the pipeline and bypass the 50-entry limit.
     */
    private final Map<String, String> sdkTags;

    // ── Custom event flag ─────────────────────────────────────────────────────

    /** {@code true} when this event was submitted via {@code publishCustomEvent}. */
    private boolean customEvent;

    // ── Call timestamp ────────────────────────────────────────────────────────

    private final OffsetDateTime callTime;

    // ── Infrastructure context ────────────────────────────────────────────────

    private final ServerContext serverContext;

    // ── Async hook futures and redaction functions ────────────────────────────

    private final PendingHooks pendingHooks;

    public QueuedEvent(EventData data, ServerContext serverContext, PendingHooks pendingHooks) {
        this.projectId     = data.projectId();
        this.sessionId     = data.sessionId();
        this.toolName      = data.toolName();
        this.params        = data.params();
        this.response      = data.response();
        this.durationMs    = data.durationMs();
        this.isError       = data.isError();
        this.errorMessage  = data.errorMessage();
        this.userIntent    = data.userIntent();
        this.sdkTags       = data.sdkTags();
        this.callTime      = data.callTime();
        // Mirror TypeScript: SDK tags are set synchronously on the event immediately,
        // not deferred to the async pipeline. applyPendingTags() then merges customer
        // tags underneath (SDK tags always win as last writer).
        Map<String, String> sdkTags = data.sdkTags();
        this.tags          = sdkTags != null && !sdkTags.isEmpty()
                ? new LinkedHashMap<>(sdkTags) : null;
        this.serverContext = serverContext != null ? serverContext : ServerContext.empty();
        this.pendingHooks  = pendingHooks  != null ? pendingHooks  : PendingHooks.empty();
    }

    // ── Core getters/setters ──────────────────────────────────────────────────

    public String getProjectId()              { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getSessionId()              { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getToolName()               { return toolName; }
    public void setToolName(String toolName)   { this.toolName = toolName; }

    public Map<String, Object> getParams()    { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }

    public Map<String, Object> getResponse()  { return response; }
    public void setResponse(Map<String, Object> response) { this.response = response; }

    public long getDurationMs()               { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public boolean isError()                  { return isError; }
    public void setError(boolean isError)      { this.isError = isError; }

    public String getErrorMessage()           { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getErrorType()              { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }

    public String getUserIntent()             { return userIntent; }
    public void setUserIntent(String userIntent) { this.userIntent = userIntent; }

    // ── Identity getters/setters ──────────────────────────────────────────────

    public UserIdentity getActor()            { return actor; }
    public void setActor(UserIdentity actor)   { this.actor = actor; }

    // ── Metadata getters/setters ──────────────────────────────────────────────

    public Map<String, String> getTags()      { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    /**
     * Returns the SDK-internal handle tags (e.g. {@code agentcat_session_id_source}).
     * Unlike {@link #getTags()}, these are set synchronously at construction and always
     * override customer tags with the same key.
     */
    public Map<String, String> getSdkTags()   { return sdkTags; }

    public Map<String, Object> getProperties()            { return properties; }
    public void setProperties(Map<String, Object> properties) { this.properties = properties; }

    // ── Call timestamp getter ─────────────────────────────────────────────────

    public OffsetDateTime getCallTime()        { return callTime; }

    // ── Infrastructure context getter ─────────────────────────────────────────

    public ServerContext getServerContext()    { return serverContext; }

    // ── Pending hooks getter ──────────────────────────────────────────────────

    public PendingHooks getPendingHooks()      { return pendingHooks; }

    // ── Custom event flag getter/setter ───────────────────────────────────────

    public boolean isCustomEvent()             { return customEvent; }
    public void setCustomEvent(boolean v)      { this.customEvent = v; }
}
