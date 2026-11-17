package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.api.model.PublishServerInfoRequest;
import com.agentcat.sdk.modules.events.EventQueue;
import com.agentcat.sdk.modules.events.EventSendException;
import com.agentcat.sdk.tools.GetMoreTools;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static com.agentcat.sdk.logging.AgentCatLogger.writeToLogFileOnly;

/**
 * The server info heartbeat: about once an hour for each process, the SDK reads the server's
 * name, version, title, instructions and lists, and sends them to AgentCat in one POST.
 *
 * <p>A tool call only checks the guard in memory. The lists, the redaction and the POST run
 * on a daemon thread of the SDK, which catches every {@link Throwable}, so no failure and no
 * wait of the heartbeat ever reaches the customer's server.
 */
public final class ServerInfoHeartbeat {

    static final long BUILD_LIMIT_NANOS = Duration.ofSeconds(30).toNanos();
    static final String THREAD_NAME = "agentcat-server-info";
    private static final int HTTP_TOO_LARGE = 413;

    private static final LongSupplier SYSTEM_CLOCK_MS = () -> System.nanoTime() / 1_000_000L;

    private static volatile ServerInfoGuard guard = new ServerInfoGuard(SYSTEM_CLOCK_MS);
    private static final AtomicInteger RUNNING = new AtomicInteger();

    private ServerInfoHeartbeat() {}

    /**
     * Checks on a tool call whether a heartbeat is due, and starts it in the background.
     * Never throws and never waits.
     */
    public static void onToolCall(ServerInfoTarget target, String toolName) {
        try {
            if (target == null || target.projectId == null || target.projectId.isBlank()) return;
            if (target.opts == null || !target.opts.isEnableTracing()) return;
            boolean feedbackNote = GetMoreTools.GET_MORE_TOOLS_NAME.equals(toolName);
            if (!guard.tryStart(target.projectId, target.serverName, feedbackNote)) return;
            start(target);
        } catch (Throwable ignored) {
            // The request path does no I/O, so a failure here is not logged.
        }
    }

    private static void start(ServerInfoTarget target) {
        RUNNING.incrementAndGet();
        try {
            Thread thread = new Thread(() -> run(target), THREAD_NAME);
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            RUNNING.decrementAndGet();
            throw t;
        }
    }

    private static void run(ServerInfoTarget target) {
        try {
            attempt(target);
        } catch (Throwable t) {
            log("server info: attempt failed (" + t.getClass().getName() + ")");
        } finally {
            RUNNING.decrementAndGet();
        }
    }

    private static void attempt(ServerInfoTarget target) throws Exception {
        long deadline = System.nanoTime() + BUILD_LIMIT_NANOS;
        ServerFeatures server = target.source.read();
        if (server == null) {
            log("server info: skipped, the server no longer exists");
            return;
        }

        Map<String, Object> features;
        try {
            features = ServerInfoBody.features(server, deadline);
        } catch (ListReader.ListFailedException e) {
            log("server info: skipped, the tools list failed: " + e.getMessage());
            return;
        }
        String title = server.title();
        String instructions = server.instructions();

        Function<String, CompletableFuture<String>> redact = target.opts.getRedactSensitiveInformation();
        if (redact != null) {
            ServerInfoRedaction.Redacted redacted;
            try {
                redacted = ServerInfoRedaction.apply(title, instructions, features, redact, deadline);
            } catch (Throwable t) {
                log("server info: skipped, the redaction function failed or passed its time limit ("
                        + t.getClass().getName() + ")");
                return;
            }
            title = redacted.title();
            instructions = redacted.instructions();
            features = redacted.features();
        }

        if (System.nanoTime() - deadline > 0) {
            log("server info: skipped, the build took more than 30 s");
            return;
        }
        send(target, title, instructions, features);
    }

    private static void send(ServerInfoTarget target, String title, String instructions,
                             Map<String, Object> features) {
        ServerInfoSender sender = EventQueue.getInstance().serverInfoSender();
        PublishServerInfoRequest request = ServerInfoBody.request(target.projectId, target.serverName,
                target.serverVersion, title, instructions, features);
        Integer status = post(sender, request);
        if (status == null || done(status, request, features)) return;
        if (status != HTTP_TOO_LARGE) {
            log("server info: the API answered HTTP " + status + ", attempt ended");
            return;
        }

        Map<String, Object> slim = ServerInfoBody.withoutSchemas(features);
        request = ServerInfoBody.request(target.projectId, target.serverName,
                target.serverVersion, title, instructions, slim);
        status = post(sender, request);
        if (status == null || done(status, request, slim)) return;
        log("server info: the API answered HTTP " + status + " to the body without schemas, attempt ended");
    }

    /** Sends once. Returns the status, or {@code null} when no answer arrived. */
    private static Integer post(ServerInfoSender sender, PublishServerInfoRequest request) {
        try {
            return sender.sendServerInfo(request);
        } catch (EventSendException e) {
            log("server info: no answer from the API, attempt ended: " + e.getMessage());
            return null;
        }
    }

    private static boolean done(int status, PublishServerInfoRequest request, Map<String, Object> features) {
        if (status < 200 || status > 299) return false;
        logSent(request, features);
        return true;
    }

    private static void logSent(PublishServerInfoRequest request, Map<String, Object> features) {
        int tools = features.get(ServerInfoBody.TOOLS) instanceof List<?> list ? list.size() : 0;
        int bytes = request.toJson().getBytes(StandardCharsets.UTF_8).length;
        log("server info: sent, " + tools + " tools, " + bytes + " bytes");
    }

    private static void log(String message) {
        try {
            writeToLogFileOnly(message);
        } catch (Throwable ignored) {
            // A failed log line must not end the attempt's thread with an uncaught error.
        }
    }

    // ── Test support ──────────────────────────────────────────────────────────

    /**
     * Empties the memory of the heartbeat, as a new process has it, and reads time from
     * {@code clockMs} (milliseconds). The memory starts at the first check.
     */
    public static void resetForTesting(LongSupplier clockMs) {
        guard = new ServerInfoGuard(clockMs);
    }

    /** Like {@link #resetForTesting(LongSupplier)}, with a memory that is {@code memoryAgeMs} old. */
    public static void resetForTesting(LongSupplier clockMs, long memoryAgeMs) {
        ServerInfoGuard fresh = new ServerInfoGuard(clockMs);
        fresh.startMemoryAt(clockMs.getAsLong() - memoryAgeMs);
        guard = fresh;
    }

    /** Waits until no heartbeat runs. Returns {@code false} when one still runs after {@code timeout}. */
    public static boolean awaitIdleForTesting(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (RUNNING.get() > 0) {
            if (System.nanoTime() - deadline > 0) return false;
            Thread.sleep(5);
        }
        return true;
    }
}
