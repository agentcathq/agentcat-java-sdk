package com.agentcat.sdk.integration;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.modules.events.EventQueue;
import com.agentcat.sdk.modules.events.EventSender;
import org.awaitility.Awaitility;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Intercepts the AgentCat event pipeline so integration tests can assert on
 * published events without making real HTTP calls.
 *
 * <p>Replaces the singleton {@link EventQueue}'s {@link EventSender} with one
 * that captures every {@link PublishEventRequest} passed to
 * {@code send()}. The original api is restored on {@link #stop()}.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * EventCapture capture = new EventCapture();
 * capture.start();
 * try {
 *     // ... call tools ...
 *     capture.flush();          // wait for pipeline to finish
 *     var events = capture.getEvents();
 *     assertThat(events).hasSize(1);
 * } finally {
 *     capture.stop();
 * }
 * }</pre>
 */
public class EventCapture {

    private static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration FLUSH_POLL    = Duration.ofMillis(20);

    private final CopyOnWriteArrayList<PublishEventRequest> captured = new CopyOnWriteArrayList<>();

    private EventSender originalApi;
    private boolean started = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Resets the singleton EventQueue (drains pending events, waits for workers,
     * restarts the dispatcher), then installs a capturing mock.
     */
    public void start() throws Exception {
        if (started) throw new IllegalStateException("EventCapture already started");

        // Full reset: clears queue, waits for in-flight workers, restarts dispatcher.
        // Eliminates all cross-test leakage from the singleton EventQueue.
        EventQueue.getInstance().resetForTesting(10_000);

        EventSender mock = captured::add;

        Field apiField = EventQueue.class.getDeclaredField("sender");
        apiField.setAccessible(true);

        EventQueue queue = EventQueue.getInstance();
        originalApi = (EventSender) apiField.get(queue);
        apiField.set(queue, mock);

        started = true;
    }

    /**
     * Restores the original {@link EventSender} in the singleton.
     * Always call this in {@code @AfterEach} / {@code finally}.
     */
    public void stop() throws Exception {
        if (!started) return;

        Field apiField = EventQueue.class.getDeclaredField("sender");
        apiField.setAccessible(true);
        apiField.set(EventQueue.getInstance(), originalApi);

        originalApi = null;
        started = false;
    }

    // ── Event access ──────────────────────────────────────────────────────────

    /** Returns a snapshot of all captured events in arrival order. */
    public List<PublishEventRequest> getEvents() {
        return Collections.unmodifiableList(new ArrayList<>(captured));
    }

    /**
     * Returns the first captured event whose {@code resourceName} matches the
     * given tool name, or {@code null} if not found.
     */
    public PublishEventRequest findByTool(String toolName) {
        return captured.stream()
                .filter(e -> toolName.equals(e.getResourceName()))
                .findFirst()
                .orElse(null);
    }

    /** Clears all captured events. */
    public void clear() {
        captured.clear();
    }

    // ── Async flush ───────────────────────────────────────────────────────────

    /**
     * Waits until at least {@code count} events have been captured, or throws
     * if the timeout elapses.
     */
    public void flush(int count) {
        Awaitility.await()
                .atMost(FLUSH_TIMEOUT)
                .pollInterval(FLUSH_POLL)
                .until(() -> captured.size() >= count);
    }

    /** Waits until at least one event has been captured. */
    public void flush() {
        flush(1);
    }
}
