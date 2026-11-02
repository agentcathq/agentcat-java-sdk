package com.agentcat.sdk.e2e;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.integration.EventCapture;
import io.modelcontextprotocol.client.McpSyncClient;

import java.util.List;
import java.util.Map;

/**
 * Everything a scenario script needs to drive and assert on a single test run.
 *
 * <p>Java analogue of the TypeScript {@code ScenarioContext} interface.
 *
 * <h3>Helpers</h3>
 * <ul>
 *   <li>{@link #waitForEvents(int)} — waits until at least N events are captured,
 *       then returns the full event list.</li>
 *   <li>{@link #settle()} — 100 ms pause for pipeline-mutation assertions
 *       (redaction, properties) that run after the event is added to the queue.</li>
 *   <li>{@link #rawArgsOf(PublishEventRequest)} — returns the raw wire arguments
 *       from a captured event (pre-strip, post-redaction).</li>
 * </ul>
 */
public record ScenarioContext(
        McpSyncClient client,
        EventCapture capture,
        ToolSink sink,
        E2ELane lane
) {

    /**
     * Waits until at least {@code count} events have been captured, then returns
     * all captured events. Uses {@link EventCapture#flush(int)} internally.
     *
     * <p>Always prefer this over reading {@code capture.getEvents()} directly —
     * events cross a real HTTP boundary and arrive asynchronously.
     */
    public List<PublishEventRequest> waitForEvents(int count) {
        capture.flush(count);
        return capture.getEvents();
    }

    /**
     * 100 ms pause for pipeline-mutation assertions (redaction, custom properties)
     * that run asynchronously after the event enters the queue.
     *
     * <p>Use after {@link #waitForEvents(int)} when the assertion concerns a field
     * that the redaction/properties hook mutates — those mutations happen concurrently
     * with the event being counted as "captured".
     */
    public static void settle() throws InterruptedException {
        Thread.sleep(100);
    }

    /**
     * Returns the raw wire arguments from a captured event.
     *
     * <p>These are the pre-strip arguments as received over the wire: they include
     * injected handles ({@code session_id}, {@code context}, {@code agent_id}) that
     * the customer handler never saw. Compare with {@link ToolSink} entries to verify
     * stripping.
     *
     * @param event a captured {@link PublishEventRequest}
     * @return the raw arguments map, cast from {@code event.getParameters()}
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> rawArgsOf(PublishEventRequest event) {
        // parameters mirrors TS SDK: { request: { method, params: { name, arguments: rawArgs } }, extra }
        try {
            Map<String, Object> parameters = (Map<String, Object>) event.getParameters();
            Map<String, Object> request    = (Map<String, Object>) parameters.get("request");
            Map<String, Object> params     = (Map<String, Object>) request.get("params");
            return (Map<String, Object>) params.get("arguments");
        } catch (Exception e) {
            return Map.of();
        }
    }
}
