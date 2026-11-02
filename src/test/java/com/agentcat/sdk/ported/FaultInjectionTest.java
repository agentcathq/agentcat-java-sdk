package com.agentcat.sdk.ported;

import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.model.EventData;
import com.agentcat.sdk.model.PendingHooks;
import com.agentcat.sdk.model.QueuedEvent;
import com.agentcat.sdk.model.ServerContext;
import com.agentcat.sdk.modules.events.EventQueue;
import com.agentcat.sdk.modules.events.EventSendException;
import com.agentcat.sdk.modules.events.EventSender;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fault injection tests — verifies the SDK never breaks or delays a tool call
 * even when analytics infrastructure fails.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>API unreachable: {@code EventSender.send()} throws on every attempt
 *       — all retries are exhausted in the background; tool call already returned.</li>
 *   <li>Queue full: event queue is at {@code MAX_QUEUE_SIZE} when the tool call fires
 *       — {@code submit()} drops the oldest entry and still accepts the new event;
 *       tool call is unaffected.</li>
 * </ul>
 */
class FaultInjectionTest {

    @BeforeEach
    void resetQueue() throws InterruptedException {
        EventQueue.getInstance().resetForTesting(10_000);
    }

    /** Saved before each test that replaces the API; restored in @AfterEach. */
    private EventSender savedApi;

    @AfterEach
    void restoreApi() throws Exception {
        if (savedApi != null) {
            setQueueApi(savedApi);
            savedApi = null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static McpTestContext setupEchoServer() throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("fault-test", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Returns the input text unchanged")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("text", Map.of("type", "string")))
                                        .required(List.of("text"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            String text = String.valueOf(req.arguments().get("text"));
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent(text)
                                    .build();
                        })
                .build();
        AgentCat.track(server, "test-project", new AgentCatOptions());
        McpTestContext ctx = McpTestContext.start(transport, server);
        ctx.client().listTools();
        return ctx;
    }

    private static EventSender getQueueApi() throws Exception {
        Field f = EventQueue.class.getDeclaredField("sender");
        f.setAccessible(true);
        return (EventSender) f.get(EventQueue.getInstance());
    }

    private static void setQueueApi(EventSender api) throws Exception {
        Field f = EventQueue.class.getDeclaredField("sender");
        f.setAccessible(true);
        f.set(EventQueue.getInstance(), api);
    }

    @SuppressWarnings("unchecked")
    private static LinkedBlockingQueue<QueuedEvent> getQueue() throws Exception {
        Field f = EventQueue.class.getDeclaredField("queue");
        f.setAccessible(true);
        return (LinkedBlockingQueue<QueuedEvent>) f.get(EventQueue.getInstance());
    }

    private static int getMaxQueueSize() throws Exception {
        Field f = EventQueue.class.getDeclaredField("MAX_QUEUE_SIZE");
        f.setAccessible(true);
        return (int) f.get(null);
    }

    private static String extractText(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null) return null;
        // MintBackHandler prepends a session_id announcement as the FIRST content element.
        // The actual tool response is appended after it, so we take the LAST TextContent.
        return result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .reduce((first, last) -> last)
                .orElse(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API unreachable
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void toolCallSucceedsWhenApiReturns503() throws Exception {
        // Arrange: replace the sender with one that always fails with HTTP 503.
        // Simulates a network outage or unreachable AgentCat ingest endpoint.
        savedApi = getQueueApi();
        EventSender throwingApi = req -> {
            throw new EventSendException(503, "Service Unavailable", null);
        };
        setQueueApi(throwingApi);

        try (McpTestContext ctx = setupEchoServer()) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo", Map.of("text", "hello")));

            // Tool response must be correct and completely unaffected by the API failure.
            assertNotNull(result, "result must not be null");
            assertFalse(Boolean.TRUE.equals(result.isError()), "result must not be an error");
            assertEquals("hello", extractText(result), "echo text must be returned unchanged");
        }
    }

    @Test
    void toolCallSucceedsWhenApiThrowsOnEveryRetry() throws Exception {
        // All MAX_RETRIES attempts fail with 500 — retry backoff fires in the background.
        // The tool call must have completed well before that background work finishes.
        savedApi = getQueueApi();
        EventSender throwingApi = req -> {
            throw new EventSendException(500, "Internal Server Error", null);
        };
        setQueueApi(throwingApi);

        try (McpTestContext ctx = setupEchoServer()) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo", Map.of("text", "retry-test")));

            assertNotNull(result);
            assertFalse(Boolean.TRUE.equals(result.isError()));
            assertEquals("retry-test", extractText(result));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Queue full
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void toolCallSucceedsWhenEventQueueIsFull() throws Exception {
        // Arrange: fill the event queue to MAX_QUEUE_SIZE with minimal dummy events.
        // Simulates a saturated analytics backend where the dispatcher cannot drain fast enough.
        LinkedBlockingQueue<QueuedEvent> queue = getQueue();
        int maxSize = getMaxQueueSize();

        // Clear any residue from prior tests, then fill to capacity.
        queue.clear();
        QueuedEvent dummy = new QueuedEvent(
                new EventData("dummy-proj", null, "dummy_tool", null, null, 0, false, null, null, null, null),
                ServerContext.empty(),
                PendingHooks.empty());
        for (int i = 0; i < maxSize; i++) {
            queue.offer(dummy);
        }
        // Workers from previous tests may still hold concurrency slots while sleeping in retry
        // backoff (up to 7 s total). The dispatcher can dequeue a few items while they wait
        // for a free slot. Allow up to MAX_CONCURRENCY items of tolerance.
        assertTrue(queue.size() >= maxSize - 10,
                "queue must be near capacity before the tool call; actual: " + queue.size());

        try (McpTestContext ctx = setupEchoServer()) {
            // Act: submit() will drop the oldest entry and accept the new event —
            // the tool call must be unaffected.
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo", Map.of("text", "queue-full")));

            // Assert: correct result regardless of queue overflow.
            assertNotNull(result, "result must not be null");
            assertFalse(Boolean.TRUE.equals(result.isError()), "result must not be an error");
            assertEquals("queue-full", extractText(result), "echo text must be returned unchanged");
        } finally {
            // Drain dummy events so subsequent tests start with an empty queue.
            queue.clear();
        }
    }
}
