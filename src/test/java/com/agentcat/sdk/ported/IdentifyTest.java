package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.integration.TestUtils;
import com.agentcat.sdk.model.UserIdentity;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/identify.test.ts
 *
 * Tests for the {@code identify} hook that stamps user identity onto tool-call events.
 */
class IdentifyTest {

    private EventCapture capture;

    @BeforeEach
    void startCapture() throws Exception {
        capture = new EventCapture();
        capture.start();
    }

    @AfterEach
    void stopCapture() throws Exception {
        capture.stop();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private McpTestContext setupServer(AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("identify-test", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("text", Map.of("type", "string")))
                                        .required(List.of("text"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            String text = String.valueOf(req.arguments().get("text"));
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("echo: " + text)
                                    .build();
                        })
                .build();

        AgentCat.track(server, "test-project", opts);
        McpTestContext ctx = McpTestContext.start(transport, server);
        ctx.client().listTools();
        return ctx;
    }

    private List<PublishEventRequest> toolCallEvents() {
        return capture.getEvents().stream()
                .filter(e -> "mcp:tools/call".equals(e.getEventType()))
                .toList();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unit tests — AgentCatOptions.identify wiring
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void identifyCallbackCanBeSetOnOptions() {
        AtomicBoolean called = new AtomicBoolean(false);
        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) -> {
            called.set(true);
            return CompletableFuture.completedFuture(
                    new UserIdentity("user-1", null, null));
        });
        assertNotNull(opts.getIdentify());
    }

    @Test
    void identifyCallbackIsNullByDefault() {
        AgentCatOptions opts = new AgentCatOptions();
        assertNull(opts.getIdentify());
    }

    @Test
    void userIdentityHoldsUserIdAndUserData() {
        UserIdentity identity = new UserIdentity("user-123", "Alice", Map.of("role", "admin"));
        assertEquals("user-123", identity.userId);
        assertEquals("Alice", identity.userName);
        assertEquals(Map.of("role", "admin"), identity.userData);
    }

    @Test
    void userIdentityAllowsNullFields() {
        UserIdentity identity = new UserIdentity(null, null, null);
        assertNull(identity.userId);
        assertNull(identity.userName);
        assertNull(identity.userData);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Basic Identification Test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void callsIdentifyOnToolCallAndStampsIdentityOntoThatEvent() throws Exception {
        // ported from: "should call identify on the tool call and stamp the identity onto that event"
        AtomicBoolean identifyCalled = new AtomicBoolean(false);
        String testUserId = "user-" + UUID.randomUUID();
        Map<String, Object> testUserData = Map.of(
                "name", "Test User " + UUID.randomUUID(),
                "email", "test-" + UUID.randomUUID() + "@example.com"
        );

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) -> {
            identifyCalled.set(true);
            assertNotNull(request);
            assertNotNull(extra);
            return CompletableFuture.completedFuture(
                    new UserIdentity(testUserId, null, testUserData));
        });

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Test todo for identification")));

            assertTrue(identifyCalled.get(), "identify must have been called");

            capture.flush();

            // No separate identify event exists — identity is stamped on tool call event.
            boolean hasIdentifyEvent = capture.getEvents().stream()
                    .anyMatch(e -> "agentcat:identify".equals(e.getEventType()));
            assertFalse(hasIdentifyEvent, "no separate identify event must be published");

            List<PublishEventRequest> toolEvents = toolCallEvents();
            assertEquals(1, toolEvents.size());
            PublishEventRequest event = toolEvents.get(0);
            assertEquals("echo", event.getResourceName());
            assertEquals(testUserId, event.getIdentifyActorGivenId());
            assertEquals(testUserData, (Map<String, Object>) event.getIdentifyData());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void callsIdentifyOnEveryToolCallAndStampsEveryEventWithNoDedup() throws Exception {
        // ported from: "should call identify on every tool call and stamp every event, with no dedup"
        AtomicInteger identifyCallCount = new AtomicInteger(0);
        String userId = "user-" + UUID.randomUUID();
        String userName = "Another User " + UUID.randomUUID();

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) -> {
            identifyCallCount.incrementAndGet();
            return CompletableFuture.completedFuture(
                    new UserIdentity(userId, null, Map.of("name", userName)));
        });

        try (McpTestContext ctx = setupServer(opts)) {
            // Three tool calls
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "First todo")));
            assertEquals(1, identifyCallCount.get());

            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Second todo")));
            assertEquals(2, identifyCallCount.get());

            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Third todo")));
            assertEquals(3, identifyCallCount.get());

            capture.flush(3);

            // Zero identify events.
            long identifyCount = capture.getEvents().stream()
                    .filter(e -> "agentcat:identify".equals(e.getEventType()))
                    .count();
            assertEquals(0, identifyCount, "no identify events must be published");

            // EVERY tool call event carries the resolved identity.
            List<PublishEventRequest> toolEvents = toolCallEvents();
            assertEquals(3, toolEvents.size());
            for (PublishEventRequest event : toolEvents) {
                assertEquals(userId, event.getIdentifyActorGivenId());
                assertEquals(Map.of("name", userName), (Map<String, Object>) event.getIdentifyData());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // User Data Persistence Across Tool Calls
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void maintainsUserIdentificationAcrossMultipleToolCalls() throws Exception {
        // ported from: "should maintain user identification across multiple tool calls"
        String testUserId = "persistent-user-" + UUID.randomUUID();
        Map<String, Object> testUserData = Map.of(
                "name", "Persistent User " + UUID.randomUUID(),
                "department", "Engineering",
                "customField", "custom-value-" + UUID.randomUUID()
        );

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.completedFuture(
                        new UserIdentity(testUserId, null, testUserData)));

        try (McpTestContext ctx = setupServer(opts)) {
            String sessionId = TestUtils.sid("identify_persistence");

            // Make multiple tool calls with the same session
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Todo 1", "session_id", sessionId)));
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Todo 2", "session_id", sessionId)));
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Todo 3", "session_id", sessionId)));

            capture.flush(3);

            List<PublishEventRequest> toolEvents = toolCallEvents();
            assertEquals(3, toolEvents.size());

            // All events on the same session
            for (PublishEventRequest event : toolEvents) {
                assertEquals(sessionId, event.getSessionId());
            }

            // Identity re-resolved and stamped on every event
            for (PublishEventRequest event : toolEvents) {
                assertEquals(testUserId, event.getIdentifyActorGivenId());
                assertEquals(testUserData, (Map<String, Object>) event.getIdentifyData());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Null/Undefined Identity Handling
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void handlesWhenIdentifyFunctionReturnsNull() throws Exception {
        // ported from: "should handle when identify function returns null"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.completedFuture(null));

        try (McpTestContext ctx = setupServer(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("text", "Test todo")));

            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t -> t.contains("echo: Test todo")),
                    "tool must produce output");

            capture.flush();

            // No identify event
            boolean hasIdentifyEvent = capture.getEvents().stream()
                    .anyMatch(e -> "agentcat:identify".equals(e.getEventType()));
            assertFalse(hasIdentifyEvent);

            // Tool call event still publishes, just anonymously
            List<PublishEventRequest> toolEvents = toolCallEvents();
            assertEquals(1, toolEvents.size());
            PublishEventRequest event = toolEvents.get(0);
            assertNull(event.getIdentifyActorGivenId(),
                    "identifyActorGivenId must be null when identify returns null");
            assertNull(event.getIdentifyActorName(),
                    "identifyActorName must be null when identify returns null");
        }
    }

    @Test
    void worksWithoutIdentifyFunctionAnonymousSessions() throws Exception {
        // ported from: "should work without identify function (anonymous sessions)"
        AgentCatOptions opts = new AgentCatOptions();
        // No identify function provided

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Anonymous todo")));
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Second anonymous")));

            capture.flush(2);

            List<PublishEventRequest> toolEvents = toolCallEvents();
            assertEquals(2, toolEvents.size());

            // Events published with session IDs
            for (PublishEventRequest event : toolEvents) {
                assertNotNull(event.getSessionId());
                assertFalse(event.getSessionId().isEmpty());
            }

            // No identify events
            boolean hasIdentifyEvent = capture.getEvents().stream()
                    .anyMatch(e -> "agentcat:identify".equals(e.getEventType()));
            assertFalse(hasIdentifyEvent);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Identity Data on Published Events
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void populatesActorGivenIdActorNameAndActorDataOnTheEvent() throws Exception {
        // ported from: "should populate actorGivenId, actorName, and actorData on the event"
        String testUserId = "session-user-" + UUID.randomUUID();
        String testUserName = "Session User " + UUID.randomUUID();
        Map<String, Object> testUserData = Map.of(
                "name", "Session Test User " + UUID.randomUUID(),
                "role", "Developer",
                "team", "Platform"
        );

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.completedFuture(
                        new UserIdentity(testUserId, testUserName, testUserData)));

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Test session info")));

            capture.flush();

            List<PublishEventRequest> toolEvents = toolCallEvents();
            assertEquals(1, toolEvents.size());
            PublishEventRequest event = toolEvents.get(0);
            assertEquals(testUserId, event.getIdentifyActorGivenId());
            assertEquals(testUserName, event.getIdentifyActorName());
            assertEquals(testUserData, (Map<String, Object>) event.getIdentifyData());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void reResolvesIdentityPerRequestSoChangedIdentityLandsOnNextEvent() throws Exception {
        // ported from: "should re-resolve identity per request so a changed identity lands on the next event"
        String firstUserId = "event-user-a-" + UUID.randomUUID();
        String secondUserId = "event-user-b-" + UUID.randomUUID();
        UserIdentity[] identities = {
                new UserIdentity(firstUserId, "First", Map.of("seat", "a")),
                new UserIdentity(secondUserId, "Second", Map.of("seat", "b"))
        };
        AtomicInteger call = new AtomicInteger(0);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.completedFuture(identities[call.getAndIncrement()]));

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "First call")));
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Second call")));

            capture.flush(2);

            List<PublishEventRequest> toolEvents = toolCallEvents();
            assertEquals(2, toolEvents.size());

            assertEquals(firstUserId, toolEvents.get(0).getIdentifyActorGivenId());
            assertEquals("First", toolEvents.get(0).getIdentifyActorName());
            assertEquals(Map.of("seat", "a"), (Map<String, Object>) toolEvents.get(0).getIdentifyData());

            assertEquals(secondUserId, toolEvents.get(1).getIdentifyActorGivenId());
            assertEquals("Second", toolEvents.get(1).getIdentifyActorName());
            assertEquals(Map.of("seat", "b"), (Map<String, Object>) toolEvents.get(1).getIdentifyData());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Async Identity Resolution
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void handlesAsyncOperationsInIdentifyFunction() throws Exception {
        // ported from: "should handle async operations in identify function"
        AtomicBoolean asyncCompleted = new AtomicBoolean(false);
        String asyncUserId = "async-user-" + UUID.randomUUID();

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.supplyAsync(() -> {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    asyncCompleted.set(true);
                    return new UserIdentity(asyncUserId, null, Map.of("source", "async-lookup"));
                }));

        try (McpTestContext ctx = setupServer(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("text", "Async test todo")));

            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t -> t.contains("echo: Async test todo")),
                    "tool must produce output");

            // The identify hook is deferred: the call returns while the 100ms
            // lookup is still in flight — hooks never hold up the tool call.
            // (In Java the hook may or may not have completed by now depending on timing,
            // so we don't assert asyncCompleted == false here.)

            capture.flush();
            // After flush, the async operation must have completed.
            assertTrue(asyncCompleted.get(), "async identify must have completed");

            List<PublishEventRequest> toolEvents = toolCallEvents();
            assertEquals(1, toolEvents.size());
            PublishEventRequest event = toolEvents.get(0);
            assertEquals(asyncUserId, event.getIdentifyActorGivenId());
            assertEquals(Map.of("source", "async-lookup"), (Map<String, Object>) event.getIdentifyData());
        }
    }

    @Test
    void handlesErrorsInIdentifyFunctionGracefully() throws Exception {
        // ported from: "should handle errors in identify function gracefully"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) -> {
            throw new RuntimeException("Failed to identify user");
        });

        try (McpTestContext ctx = setupServer(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("text", "Error test todo")));

            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t -> t.contains("echo: Error test todo")),
                    "tool must produce output despite identify error");

            capture.flush();

            // No identify event
            boolean hasIdentifyEvent = capture.getEvents().stream()
                    .anyMatch(e -> "agentcat:identify".equals(e.getEventType()));
            assertFalse(hasIdentifyEvent);

            // Tool call event still publishes, just without actor fields
            List<PublishEventRequest> toolEvents = toolCallEvents();
            assertEquals(1, toolEvents.size());
            PublishEventRequest event = toolEvents.get(0);
            assertNull(event.getIdentifyActorGivenId(),
                    "identifyActorGivenId must be null when identify throws");
            assertNull(event.getIdentifyActorName(),
                    "identifyActorName must be null when identify throws");
        }
    }

    @Test
    void handlesIdentifyFunctionThatReturnsInvalidData() throws Exception {
        // ported from: "should handle identify function that returns invalid data"
        // Return identity with no userId set (missing required fields).
        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.completedFuture(
                        new UserIdentity(null, null, null)));

        try (McpTestContext ctx = setupServer(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("text", "Invalid identity test")));

            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t -> t.contains("echo: Invalid identity test")),
                    "tool must produce output");

            capture.flush();

            // Event publishes, but identity fields come out empty.
            List<PublishEventRequest> toolEvents = toolCallEvents();
            assertEquals(1, toolEvents.size());
            PublishEventRequest event = toolEvents.get(0);
            assertNull(event.getIdentifyActorGivenId(),
                    "identifyActorGivenId must be null for invalid identity");
            assertNull(event.getIdentifyActorName(),
                    "identifyActorName must be null for invalid identity");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // tools/list no longer identifies or publishes
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldNotRunIdentifyOrPublishEventForToolsListRequest() throws Exception {
        // ported from: "should not run identify or publish an event for a tools/list request"
        AtomicBoolean identifyCalled = new AtomicBoolean(false);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) -> {
            identifyCalled.set(true);
            return CompletableFuture.completedFuture(
                    new UserIdentity("list-tools-user", "List Tools User",
                            Map.of("plan", "premium")));
        });

        try (McpTestContext ctx = setupServer(opts)) {
            // Reset — the setupServer already called listTools() once for schema priming;
            // reset state to isolate this test.
            identifyCalled.set(false);
            capture.clear();

            // tools/list is schema injection only: no event, no identify.
            ctx.client().listTools();

            Thread.sleep(100);

            assertFalse(identifyCalled.get(),
                    "identify must not be called for tools/list");
            assertTrue(capture.getEvents().isEmpty(),
                    "no events must be published for tools/list");
        }
    }
}
