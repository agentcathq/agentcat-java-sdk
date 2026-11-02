package com.agentcat.sdk.modules.events;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.config.AgentCatConstants;
import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;
import com.agentcat.sdk.model.PendingHooks;
import com.agentcat.sdk.model.QueuedEvent;
import com.agentcat.sdk.model.ServerContext;
import com.agentcat.sdk.model.UserIdentity;
import com.agentcat.sdk.modules.handles.HandleTags;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import com.agentcat.sdk.modules.handles.SessionIdProvider;
import com.agentcat.sdk.modules.pipeline.PayloadRedactor;
import com.agentcat.sdk.modules.pipeline.Sanitizer;
import com.agentcat.sdk.modules.pipeline.Truncator;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounded, asynchronous event queue for AgentCat analytics events.
 *
 * <h3>Pipeline (per event)</h3>
 * <ol>
 *   <li>Stage 0: Resolve pending async hook results (identity, session, tags, properties)
 *       — with a 5 s timeout; timed-out hooks are skipped.</li>
 *   <li>redactEvent — event-level hook; returning {@code null}, throwing, or timing out
 *       drops the event.</li>
 *   <li>redactSensitiveInformation — string-level redaction via customer hook
 *       (runs on original data, before sanitizer replaces binary content).</li>
 *   <li>Sanitize — remove binary content, large base64 blobs.</li>
 *   <li>Truncate — cap strings at 32 KB, depth at 10, breadth at 100.</li>
 *   <li>Send — HTTP POST to AgentCat API, up to 3 retries with exponential
 *       backoff (1 s / 2 s / 4 s).</li>
 * </ol>
 *
 * <h3>Concurrency</h3>
 * Up to {@value #MAX_CONCURRENCY} events are in-flight at the same time.
 * A single background dispatcher thread feeds a shared {@link Semaphore} so
 * worker threads are never starved and the semaphore is always released.
 *
 * <h3>Capacity</h3>
 * The queue holds at most {@value #MAX_QUEUE_SIZE} events. When full, the
 * oldest entry is dropped and a warning is written to the log.
 *
 * <h3>Shutdown</h3>
 * A JVM shutdown hook awaitPendingEventsOnShutdowns the queue for up to {@value #DRAIN_TIMEOUT_MS} ms.
 * After that, remaining events are discarded and the count is logged.
 */
public final class EventQueue {

    // ── Constants ────────────────────────────────────────────────────────────

    private static final int  MAX_QUEUE_SIZE      = 10_000;
    private static final int  MAX_CONCURRENCY     = 5;
    private static final int  MAX_RETRIES         = 3;
    private static final long RETRY_BASE_DELAY_MS = 1_000;
    private static final long DRAIN_TIMEOUT_MS       = 5_000;
    private static final long DRAIN_POLL_INTERVAL_MS = 50;
    /** Maximum ms to wait for any async hook to settle (Stage 0 + redaction stages). */
    private static final long HOOK_TIMEOUT_MS     = 5_000;
    /** Wire event type of a tool call. */
    static final String TOOLS_CALL_EVENT_TYPE = "mcp:tools/call";
    /** Wire event type of {@code publishCustomEvent} (TS {@code AGENTCAT_CUSTOM_EVENT_TYPE}). */
    static final String CUSTOM_EVENT_TYPE     = "agentcat:custom";

    // ── Singleton ────────────────────────────────────────────────────────────

    private static final EventQueue INSTANCE = new EventQueue();

    /**
     * Returns the singleton queue instance. The dispatcher thread and shutdown hook
     * are started on first class load — not on the first call to this method.
     */
    public static EventQueue getInstance() {
        return INSTANCE;
    }

    /**
     * Reconfigures the API base URL used for event delivery.
     * Intended for testing and custom deployments — call before the first tool invocation.
     *
     * @param apiBaseUrl base URL of the AgentCat ingest API (e.g. {@code "http://localhost:8080"})
     */
    public void configure(String apiBaseUrl) {
        this.sender = new HttpEventSender(apiBaseUrl);
    }

    // ── State ────────────────────────────────────────────────────────────────

    private final LinkedBlockingQueue<QueuedEvent> queue =
            new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);

    private final Semaphore concurrency = new Semaphore(MAX_CONCURRENCY);

    private volatile EventSender sender;


    private volatile boolean shutdown = false;
    private volatile Thread dispatcherThread;
    /** Active worker threads — tracked so {@link #resetForTesting} can interrupt them. */
    private final List<Thread> activeWorkers = new ArrayList<>();

    // ── Constructor ──────────────────────────────────────────────────────────

    private EventQueue() {
        this.sender = new HttpEventSender(AgentCatConstants.API_BASE_URL);

        dispatcherThread = new Thread(this::dispatch, "agentcat-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();

        Runtime.getRuntime().addShutdownHook(
                new Thread(this::awaitPendingEventsOnShutdown, "agentcat-awaitPendingEventsOnShutdown"));
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Submits an event to the queue. Returns immediately; processing is
     * asynchronous. Drops the oldest event if the queue is full.
     */
    public void submit(QueuedEvent event) {
        if (shutdown) {
            writeToLog("EventQueue shutting down — event dropped");
            return;
        }
        if (!queue.offer(event)) {
            queue.poll(); // drop oldest
            queue.offer(event);
            writeToLog("EventQueue full (max " + MAX_QUEUE_SIZE + ") — dropped oldest event");
        }
    }

    // ── Test support ──────────────────────────────────────────────────────────

    /**
     * Resets the singleton to a clean state for testing.
     *
     * <ol>
     *   <li>Stops the current dispatcher (sets {@code shutdown=true}, interrupts its thread).</li>
     *   <li>Drains the queue — discards all pending events.</li>
     *   <li>Waits for in-flight workers to release all concurrency permits.</li>
     *   <li>Restarts a fresh dispatcher thread.</li>
     * </ol>
     *
     * <p>Package-private — only test infrastructure in {@code com.agentcat.sdk.modules.events}
     * or friends that can reach this package should call it.
     *
     * @param timeoutMs maximum time to wait for workers to finish
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws IllegalStateException if workers do not finish within {@code timeoutMs}
     */
    public void resetForTesting(long timeoutMs) throws InterruptedException {
        // 1. Signal the dispatcher to exit and interrupt it so poll() wakes up immediately.
        shutdown = true;
        if (dispatcherThread != null) {
            dispatcherThread.interrupt();
            dispatcherThread.join(1_000);
        }

        // 2. Discard all queued events.
        queue.clear();

        // 3. Interrupt all active workers so they stop retrying / sleeping.
        synchronized (activeWorkers) {
            for (Thread w : activeWorkers) {
                w.interrupt();
            }
        }

        // 4. Wait for in-flight workers to release permits.
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (concurrency.availablePermits() < MAX_CONCURRENCY) {
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException(
                        "EventQueue.resetForTesting: workers did not finish within " + timeoutMs
                        + " ms — permits=" + concurrency.availablePermits() + "/" + MAX_CONCURRENCY);
            }
            Thread.sleep(10);
        }

        // 5. Restart a fresh dispatcher.
        shutdown = false;
        synchronized (activeWorkers) {
            activeWorkers.clear();
        }
        dispatcherThread = new Thread(this::dispatch, "agentcat-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();
    }

    // ── Dispatcher ───────────────────────────────────────────────────────────

    private void dispatch() {
        while (!shutdown || !queue.isEmpty()) {
            try {
                QueuedEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
                if (event == null) continue;

                concurrency.acquire();
                Thread worker = new Thread(() -> {
                    try {
                        processEvent(event);
                    } finally {
                        concurrency.release();
                        synchronized (activeWorkers) {
                            activeWorkers.remove(Thread.currentThread());
                        }
                    }
                }, "agentcat-worker");
                worker.setDaemon(true);
                synchronized (activeWorkers) {
                    activeWorkers.add(worker);
                }
                worker.start();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ── Event processing ─────────────────────────────────────────────────────

    /** Typed pair replacing raw {@code Map[]} arrays in the processing pipeline. */
    private record PayloadPair(Map<String, Object> params, Map<String, Object> response) {
        static PayloadPair copyOf(QueuedEvent ev) {
            return new PayloadPair(
                ev.getParams()   != null ? new HashMap<>(ev.getParams())   : new HashMap<>(),
                ev.getResponse() != null ? new HashMap<>(ev.getResponse()) : new HashMap<>()
            );
        }
    }

    private void processEvent(QueuedEvent ev) {
        // TS sends only when a project ID is set (eventQueue.ts sendEvent).
        if (ev.getProjectId() == null || ev.getProjectId().isBlank()) {
            return;
        }
        try {
            resolvePendingHooks(ev);

            PayloadPair pair = PayloadPair.copyOf(ev);

            pair = applyRedactEvent(ev, pair);
            if (pair == null) return; // dropped by hook

            pair = applyStringRedaction(ev, pair);
            if (pair == null) return; // dropped: redactSensitiveInformation hook threw

            pair = sanitize(pair);
            pair = truncate(pair);

            sendWithRetry(buildRequest(ev, pair.params(), pair.response()), ev.getToolName());

        } catch (Exception e) {
            writeToLog("[EQ] processEvent ERROR tool=" + ev.getToolName()
                    + " exception=" + e.getClass().getName()
                    + " message=" + e.getMessage());
        }
    }

    private static PayloadPair sanitize(PayloadPair pair) {
        return new PayloadPair(
            Sanitizer.sanitizeParams(pair.params()),
            Sanitizer.sanitizeResponse(pair.response())
        );
    }

    /**
     * Returns {@code null} if the redaction hook threw or timed out — the event must be dropped.
     * Mirrors TypeScript eventQueue.ts (hook throw → drop). TS has no timeout and waits;
     * Java bounds the wait and fails closed, so raw data never goes out.
     */
    private PayloadPair applyStringRedaction(QueuedEvent ev, PayloadPair pair) {
        if (ev.getPendingHooks().redactSensitiveInformation() == null) return pair;

        try {
            if (ev.getUserIntent() != null) {
                ev.setUserIntent(PayloadRedactor.applyToString(
                        ev.getUserIntent(),
                        ev.getPendingHooks().redactSensitiveInformation(),
                        HOOK_TIMEOUT_MS));
            }

            // errorMessage: may contain exception text with sensitive data
            if (ev.getErrorMessage() != null) {
                ev.setErrorMessage(PayloadRedactor.applyToString(
                        ev.getErrorMessage(),
                        ev.getPendingHooks().redactSensitiveInformation(),
                        HOOK_TIMEOUT_MS));
            }

            // userData from identify hook: may contain PII
            if (ev.getActor() != null && ev.getActor().userData != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> redactedUserData = PayloadRedactor.apply(
                        ev.getActor().userData,
                        ev.getPendingHooks().redactSensitiveInformation(),
                        HOOK_TIMEOUT_MS);
                ev.setActor(new UserIdentity(
                        ev.getActor().userId, ev.getActor().userName, redactedUserData));
            }

            return new PayloadPair(
                PayloadRedactor.apply(pair.params(),   ev.getPendingHooks().redactSensitiveInformation(), HOOK_TIMEOUT_MS),
                PayloadRedactor.apply(pair.response(), ev.getPendingHooks().redactSensitiveInformation(), HOOK_TIMEOUT_MS)
            );
        } catch (Exception e) {
            writeToLog("redactSensitiveInformation hook failed or timed out — dropping event tool=" + ev.getToolName()
                    + " error=" + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static PayloadPair truncate(PayloadPair pair) {
        Map<String, Object> params   = Truncator.normalizeMap(pair.params());
        Map<String, Object> response = Truncator.normalizeMap(pair.response());
        Map<String, Object>[] shrunk = Truncator.enforceEventSize(params, response);
        return new PayloadPair(shrunk[0], shrunk[1]);
    }

    /**
     * Runs the redactEvent hook on the full event snapshot and applies ALL mutable fields
     * from the result back onto the event — mirroring TypeScript {@code applyEventRedaction}.
     *
     * <p>The hook receives the full event (see {@link #buildEventMapForHook}).
     * On return, every mutable field is written back to {@code ev}, not just
     * {@code params} and {@code response}.
     * Protected fields ({@code sessionId}, {@code projectId}) are not applied back,
     * matching TypeScript {@code RESTORED_FIELDS}.
     *
     * <p>Returns {@code null} if the event was dropped (hook returned null, threw, or timed out).
     * Returns the original pair unchanged if the hook is not configured.
     */
    private PayloadPair applyRedactEvent(QueuedEvent ev, PayloadPair pair) {
        if (ev.getPendingHooks().redactEvent() == null) return pair;

        Map<String, Object> eventMap = buildEventMapForHook(ev, pair.params(), pair.response());
        try {
            CompletableFuture<Map<String, Object>> f = ev.getPendingHooks().redactEvent().apply(eventMap);
            eventMap = (f != null) ? f.get(HOOK_TIMEOUT_MS, TimeUnit.MILLISECONDS) : null;
        } catch (TimeoutException e) {
            // Fail closed: an unredacted event must never reach the API.
            writeToLog("redactEvent hook timed out [" + ev.getToolName() + "] — dropping event");
            return null;
        } catch (Exception e) {
            writeToLog("redactEvent hook error [" + ev.getToolName() + "]: " + e.getMessage());
            eventMap = null; // treat error same as explicit null drop
        }
        if (eventMap == null) {
            writeToLog("event dropped by redactEvent hook [" + ev.getToolName() + "]");
            return null;
        }

        // Apply all mutable fields back — mirrors TypeScript full Object.assign merge.
        // Protected fields (sessionId, projectId) are not applied back.
        applyMutableFields(ev, eventMap);

        return new PayloadPair(extractMap(eventMap, "params"), extractMap(eventMap, "response"));
    }

    /**
     * Writes mutable fields from the hook-returned {@code eventMap} back onto {@code ev}.
     *
     * <p>Protected fields ({@code sessionId}, {@code projectId}) are intentionally skipped —
     * mirrors TypeScript {@code RESTORED_FIELDS} ({@code id}, {@code sessionId},
     * {@code projectId}, {@code eventType}, {@code timestamp}).
     *
     * <p>{@code serverName}/{@code serverVersion}/{@code clientName}/{@code clientVersion}
     * are passed to the hook as read-only context but not written back —
     * {@link com.agentcat.sdk.model.ServerContext} is immutable.
     */
    @SuppressWarnings("unchecked")
    private static void applyMutableFields(QueuedEvent ev, Map<String, Object> eventMap) {
        if (eventMap.containsKey("toolName") && eventMap.get("toolName") instanceof String s) {
            ev.setToolName(s);
        }
        if (eventMap.containsKey("userIntent")) {
            Object v = eventMap.get("userIntent");
            ev.setUserIntent(v instanceof String s ? s : null);
        }
        if (eventMap.containsKey("isError") && eventMap.get("isError") instanceof Boolean b) {
            ev.setError(b);
        }
        if (eventMap.containsKey("durationMs") && eventMap.get("durationMs") instanceof Number n) {
            ev.setDurationMs(n.longValue());
        }
        if (eventMap.containsKey("tags")) {
            Object v = eventMap.get("tags");
            ev.setTags(v instanceof Map ? (Map<String, String>) v : null);
        }
        if (eventMap.containsKey("properties")) {
            Object v = eventMap.get("properties");
            ev.setProperties(v instanceof Map ? (Map<String, Object>) v : null);
        }
        if (eventMap.containsKey("actorId") && eventMap.get("actorId") instanceof String actorId) {
            UserIdentity existing = ev.getActor();
            if (existing != null) {
                ev.setActor(new UserIdentity(actorId, existing.userName, existing.userData));
            } else {
                ev.setActor(new UserIdentity(actorId));
            }
        }
        // "error" mirrors TypeScript Event.error: { message, type }. The hook owns both:
        // a returned map replaces them, and null removes the error entirely.
        if (eventMap.containsKey("error")) {
            Object v = eventMap.get("error");
            if (v instanceof Map<?, ?> errMap) {
                ev.setErrorMessage(errMap.get("message") instanceof String msg ? msg : null);
                ev.setErrorType(errMap.get("type") instanceof String type ? type : null);
            } else if (v == null) {
                ev.setErrorMessage(null);
                ev.setErrorType(null);
            }
        }
    }

    // ── Stage 0: Pending hook resolution ─────────────────────────────────────

    /**
     * Resolves all pending async hook futures concurrently (with a per-hook timeout)
     * and applies the results to the event in-place. Any hook that times out or throws
     * is silently skipped — the event publishes without that hook's data.
     */
    private void resolvePendingHooks(QueuedEvent ev) {
        PendingHooks hooks = ev.getPendingHooks();
        // Collect non-null futures into a combined wait
        List<CompletableFuture<?>> all = new ArrayList<>();
        if (hooks.pendingSessionId()  != null) all.add(hooks.pendingSessionId());
        if (hooks.pendingIdentity()   != null) all.add(hooks.pendingIdentity());
        if (hooks.pendingTags()       != null) all.add(hooks.pendingTags());
        if (hooks.pendingProperties() != null) all.add(hooks.pendingProperties());

        if (all.isEmpty()) return;

        try {
            // Wait for all hooks concurrently (worst case: one timeout budget, not four)
            CompletableFuture.allOf(all.toArray(new CompletableFuture[0]))
                    .get(HOOK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            writeToLog("Pending hook resolution timed out or failed — publishing event with partial data: "
                    + e.getMessage());
        }

        // Apply each result, guarding against individual futures that may have failed
        applyPendingSessionId(ev);
        applyPendingIdentity(ev);
        applyPendingTags(ev);
        applyPendingProperties(ev);
    }

    private void applyPendingSessionId(QueuedEvent ev) {
        CompletableFuture<String> f = ev.getPendingHooks().pendingSessionId();
        if (f == null) return;
        if (!f.isDone()) {
            // Hook hasn't resolved by event-processing time — mint a new ID so the event
            // is never published with a blank sessionId. Mirrors TypeScript settleWithTimeout:
            // timeout resolves to null, sessionFromHookValue then mints.
            writeToLog("resolveSessionId hook not done in time — minting session");
            ev.setSessionId(SessionIdProvider.newSessionId());
            updateSessionSourceTag(ev, "minted");
            return;
        }
        try {
            String hookValue = f.getNow(null);
            if (hookValue != null) {
                ev.setSessionId(SessionIdProvider.deriveSessionId(hookValue, ev.getProjectId()));
            } else {
                // null → silent mint (warn: agent can never learn a silently minted ID)
                ev.setSessionId(SessionIdProvider.newSessionId());
                updateSessionSourceTag(ev, "minted");
                writeToLog("resolveSessionId hook returned null — minting session silently");
            }
        } catch (Exception e) {
            writeToLog("resolveSessionId hook result failed: " + e.getMessage());
            ev.setSessionId(SessionIdProvider.newSessionId());
            updateSessionSourceTag(ev, "minted");
        }
    }

    /**
     * Updates the session source tag on both sdkTags and tags so that
     * {@code applyPendingTags} sees the correct value when it merges sdkTags.
     */
    private void updateSessionSourceTag(QueuedEvent ev, String source) {
        if (ev.getSdkTags() != null) {
            ev.getSdkTags().put(HandleTags.SESSION_ID_SOURCE_TAG, source);
            // Rebuild tags from the updated sdkTags so the corrected source is
            // visible even if no customer eventTags hook runs afterward.
            ev.setTags(new LinkedHashMap<>(ev.getSdkTags()));
        }
    }

    private void applyPendingIdentity(QueuedEvent ev) {
        CompletableFuture<UserIdentity> f = ev.getPendingHooks().pendingIdentity();
        if (f == null || !f.isDone()) return;
        try {
            UserIdentity identity = f.getNow(null);
            if (identity != null) {
                ev.setActor(identity);
            }
        } catch (Exception e) {
            writeToLog("identify hook result failed: " + e.getMessage());
        }
    }

    private void applyPendingTags(QueuedEvent ev) {
        // SDK tags are already on ev.tags (set synchronously at enqueue time).
        // Here we only need to merge customer eventTags UNDER them (SDK wins).
        CompletableFuture<Map<String, String>> f = ev.getPendingHooks().pendingTags();
        if (f == null || !f.isDone()) return;

        try {
            Map<String, String> customerRaw = f.getNow(null);
            if (customerRaw == null) return;

            Map<String, String> valid = TagValidator.validate(customerRaw);
            if (valid == null) return;

            // Customer tags go first, then SDK tags override — same as TypeScript.
            Map<String, String> merged = new LinkedHashMap<>(valid);
            if (ev.getSdkTags() != null) {
                merged.putAll(ev.getSdkTags());
            }
            ev.setTags(merged);
        } catch (Exception e) {
            writeToLog("eventTags hook result failed: " + e.getMessage());
        }
    }

    private void applyPendingProperties(QueuedEvent ev) {
        CompletableFuture<Map<String, Object>> f = ev.getPendingHooks().pendingProperties();
        if (f == null || !f.isDone()) return;
        try {
            Map<String, Object> props = f.getNow(null);
            if (props != null) {
                ev.setProperties(props);
            }
        } catch (Exception e) {
            writeToLog("eventProperties hook result failed: " + e.getMessage());
        }
    }

    /**
     * Strips AgentCat-injected handle parameters from a params map so the
     * {@code redactEvent} hook never sees {@code session_id}, {@code agent_id},
     * or {@code context} — mirroring TypeScript SDK behavior.
     */
    private static Map<String, Object> stripHandleParams(Map<String, Object> params) {
        if (params == null) return null;
        Map<String, Object> stripped = new HashMap<>(params);
        stripped.remove(ParameterInjector.SESSION_ID_PARAM);
        stripped.remove(ParameterInjector.AGENT_ID_PARAM);
        stripped.remove(ParameterInjector.CONTEXT_PARAM);
        return stripped;
    }

    /**
     * Builds the event snapshot passed to the {@code redactEvent} hook.
     *
     * <p>Mirrors the TypeScript hook input: the full event minus internal function fields.
     * Protected fields ({@code sessionId}, {@code projectId}) are included so the hook
     * can read them for context, but {@link #applyMutableFields} never writes them back.
     * Server/client context fields are included read-only — {@link com.agentcat.sdk.model.ServerContext}
     * is immutable so hook changes to them are not applied back.
     */
    private Map<String, Object> buildEventMapForHook(QueuedEvent ev,
                                                      Map<String, Object> params,
                                                      Map<String, Object> response) {
        Map<String, Object> map = new LinkedHashMap<>();
        // Protected (read-only for hook)
        map.put("projectId",  ev.getProjectId());
        map.put("sessionId",  ev.getSessionId());
        // Mutable fields
        map.put("toolName",   ev.getToolName());
        map.put("params",     stripHandleParams(params));
        map.put("response",   response);
        map.put("durationMs", ev.getDurationMs());
        map.put("isError",    ev.isError());
        if (ev.getUserIntent() != null)  map.put("userIntent",  ev.getUserIntent());
        if (ev.getErrorMessage() != null || ev.getErrorType() != null) {
            Map<String, Object> error = new LinkedHashMap<>();
            if (ev.getErrorMessage() != null) error.put("message", ev.getErrorMessage());
            if (ev.getErrorType() != null)    error.put("type", ev.getErrorType());
            map.put("error", error);
        }
        if (ev.getActor() != null && ev.getActor().userId != null) map.put("actorId", ev.getActor().userId);
        if (ev.getTags() != null)        map.put("tags",        ev.getTags());
        if (ev.getProperties() != null)  map.put("properties",  ev.getProperties());
        // Server/client context — read-only (ServerContext is immutable)
        ServerContext ctx = ev.getServerContext();
        if (ctx.serverName()    != null) map.put("serverName",    ctx.serverName());
        if (ctx.serverVersion() != null) map.put("serverVersion", ctx.serverVersion());
        if (ctx.clientName()    != null) map.put("clientName",    ctx.clientName());
        if (ctx.clientVersion() != null) map.put("clientVersion", ctx.clientVersion());
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMap(Map<String, Object> eventMap, String key) {
        Object v = eventMap.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    // ── Request builder ───────────────────────────────────────────────────────

    private PublishEventRequest buildRequest(QueuedEvent ev,
                                              Map<String, Object> params,
                                              Map<String, Object> response) {
        String evtId = SessionIdProvider.newEventId();

        PublishEventRequest req = new PublishEventRequest()
                .id(evtId)
                .projectId(ev.getProjectId())
                // TS: sessionId || null — a blank session reaches the wire as null,
                // so the server assigns one (stateless mode).
                .sessionId(ev.getSessionId() == null || ev.getSessionId().isEmpty() ? null : ev.getSessionId())
                .eventType(ev.isCustomEvent() ? CUSTOM_EVENT_TYPE : TOOLS_CALL_EVENT_TYPE)
                .resourceName(ev.getToolName())
                .timestamp(ev.getCallTime() != null ? ev.getCallTime() : OffsetDateTime.now(ZoneOffset.UTC))
                .duration((int) ev.getDurationMs())
                .isError(ev.isError())
                .parameters(params)
                .sdkLanguage(AgentCatConstants.SDK_LANGUAGE)
                .agentcatVersion(AgentCatConstants.AGENTCAT_SDK_VERSION);

        if (response != null)                req.response(response);
        if (ev.getUserIntent() != null)     req.userIntent(Truncator.truncateField(ev.getUserIntent(), Truncator.MAX_USER_INTENT_LENGTH));
        if (ev.getErrorMessage() != null || ev.getErrorType() != null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("message", ev.getErrorMessage() != null
                    ? Truncator.truncateField(ev.getErrorMessage(), Truncator.MAX_USER_INTENT_LENGTH) : "");
            if (ev.getErrorType() != null) error.put("type", ev.getErrorType());
            req.error(error);
        }

        ServerContext ctx = ev.getServerContext();
        if (ctx.serverName()    != null)   req.serverName(ctx.serverName());
        if (ctx.serverVersion() != null)   req.serverVersion(ctx.serverVersion());
        if (ctx.clientName()    != null)   req.clientName(ctx.clientName());
        if (ctx.clientVersion() != null)   req.clientVersion(ctx.clientVersion());

        // Identity from identify hook
        UserIdentity actor = ev.getActor();
        if (actor != null) {
            if (actor.userId   != null) req.identifyActorGivenId(actor.userId);
            if (actor.userName != null) req.identifyActorName(actor.userName);
            if (actor.userData != null) req.identifyData(actor.userData);
        }

        // Customer-defined metadata
        if (ev.getTags()       != null)             req.tags(ev.getTags());
        if (ev.getProperties() != null)             req.properties(ev.getProperties());

        return req;
    }

    // ── Send with retry ───────────────────────────────────────────────────────

    private void sendWithRetry(PublishEventRequest req, String toolName) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                sender.send(req);
                writeToLog("Successfully sent event " + req.getId()
                        + " | " + req.getEventType() + " | session " + req.getSessionId()
                        + " | " + req.getProjectId()
                        + " | " + req.getDuration() + " ms"
                        + " | " + (req.getIdentifyActorGivenId() != null
                                ? req.getIdentifyActorGivenId() : "anonymous"));
                return;
            } catch (EventSendException e) {
                if (e.isClientError()) {
                    writeToLog("Failed to deliver AgentCat event (not retrying, client error "
                            + e.getStatusCode() + "): " + messageOf(e));
                    return;
                }
                if (attempt < MAX_RETRIES) {
                    writeToLog("Failed to send event " + req.getId() + ", retrying... [Error: "
                            + messageOf(e) + "]");
                    long delayMs = RETRY_BASE_DELAY_MS * (1L << attempt); // 1s, 2s, 4s
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    writeToLog("Failed to deliver AgentCat event after retries: " + messageOf(e));
                }
            } catch (Throwable t) {
                // Catches Error subclasses (NoSuchMethodError, ExceptionInInitializerError, etc.)
                // that escape catch(EventSendException) — must not kill the worker thread.
                writeToLog("Failed to deliver AgentCat event after retries: " + messageOf(t));
                return;
            }
        }
    }

    private static String messageOf(Throwable t) {
        if (t.getMessage() != null && !t.getMessage().isBlank()) return t.getMessage();
        return t.getClass().getSimpleName();
    }

    // ── Graceful shutdown ─────────────────────────────────────────────────────

    private void awaitPendingEventsOnShutdown() {
        shutdown = true;
        long deadlineMs = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;

        while (hasPendingWork() && System.currentTimeMillis() < deadlineMs) {
            try {
                Thread.sleep(DRAIN_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        int remaining = queue.size();
        if (remaining > 0) {
            writeToLog("Shutting down with " + remaining + " events still in queue");
        }
    }

    /** Returns true while there are queued events or in-flight workers still running. */
    private boolean hasPendingWork() {
        return !queue.isEmpty() || concurrency.availablePermits() < MAX_CONCURRENCY;
    }
}
