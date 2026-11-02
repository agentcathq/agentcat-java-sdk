package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.integration.TestUtils;
import com.agentcat.sdk.model.UserIdentity;
import com.agentcat.sdk.modules.handles.HandleTags;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/v2/hooks.test.ts
 *
 * Tests for the hook surface ({@code identify}, {@code resolveSessionId},
 * {@code eventTags}, {@code eventProperties}, {@code redactSensitiveInformation},
 * {@code redactEvent}) on a tracked v2 McpServer.
 *
 * <p>This is the v2 twin of {@link EventTagsPropertiesTest} and {@link IdentifyTest},
 * exercising the same hook behaviors through the v2 (McpServer.sync) path.
 */
class HooksTest {

    // SDK tag key constants (from HandleTags)
    private static final String AGENTCAT_TAG_SESSION_SOURCE = HandleTags.SESSION_ID_SOURCE_TAG;
    private static final String AGENTCAT_TAG_AGENT_ID = HandleTags.AGENT_ID_TAG;
    private static final String AGENTCAT_TAG_AGENT_SOURCE = HandleTags.AGENT_ID_SOURCE_TAG;
    private static final String AGENTCAT_TAG_PROTOCOL_VERSION = HandleTags.PROTOCOL_VERSION_TAG;

    private static final Set<String> SDK_TAG_KEYS = Set.of(
            AGENTCAT_TAG_SESSION_SOURCE,
            AGENTCAT_TAG_AGENT_ID,
            AGENTCAT_TAG_AGENT_SOURCE,
            AGENTCAT_TAG_PROTOCOL_VERSION
    );

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

    private static Map<String, String> customerTags(Map<String, String> tags) {
        if (tags == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>(tags);
        SDK_TAG_KEYS.forEach(out::remove);
        return out;
    }

    private static Map<String, String> sdkTags(Map<String, String> tags) {
        if (tags == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : SDK_TAG_KEYS) {
            if (tags.containsKey(key)) out.put(key, tags.get(key));
        }
        return out;
    }

    /**
     * Builds a tracked v2 McpServer with one tool ("add_note") and connects
     * a real client. listTools() populates the injection registries.
     * Matches the TypeScript setupHighLevel().
     */
    private McpTestContext setupHighLevel(AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("v2-hooks", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("add_note")
                                .description("Adds a note")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("text", Map.of("type", "string")))
                                        .required(List.of("text"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            String text = String.valueOf(req.arguments().get("text"));
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("Added note: " + text)
                                    .build();
                        })
                .build();

        AgentCat.track(server, "test-project", opts);
        McpTestContext ctx = McpTestContext.start(transport, server);
        ctx.client().listTools(); // prime schema registry
        return ctx;
    }

    private List<PublishEventRequest> toolCallEvents() {
        return capture.getEvents().stream()
                .filter(e -> "mcp:tools/call".equals(e.getEventType()))
                .toList();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unit tests — SDK tag constants
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void sdkTagConstantsHaveCorrectWireValues() {
        assertEquals("agentcat_session_id_source", HandleTags.SESSION_ID_SOURCE_TAG);
        assertEquals("agentcat_agent_id", HandleTags.AGENT_ID_TAG);
        assertEquals("agentcat_agent_id_source", HandleTags.AGENT_ID_SOURCE_TAG);
        assertEquals("agentcat_protocol_version", HandleTags.PROTOCOL_VERSION_TAG);
    }

    @Test
    void identifyHookIsNullByDefault() {
        AgentCatOptions opts = new AgentCatOptions();
        assertNull(opts.getIdentify());
    }

    @Test
    void resolveSessionIdHookIsNullByDefault() {
        AgentCatOptions opts = new AgentCatOptions();
        assertNull(opts.getResolveSessionId());
    }

    @Test
    void eventTagsHookIsNullByDefault() {
        AgentCatOptions opts = new AgentCatOptions();
        assertNull(opts.getEventTags());
    }

    @Test
    void eventPropertiesHookIsNullByDefault() {
        AgentCatOptions opts = new AgentCatOptions();
        assertNull(opts.getEventProperties());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "v2 hooks: identify"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void identifyStampsActorFieldsOntoToolCallEvent() throws Exception {
        // ported from: "runs identify on the tool call and stamps the identity onto that event"
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

        try (McpTestContext ctx = setupHighLevel(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("add_note",
                    Map.of("text", "hello", "context", "identification test")));

            assertTrue(identifyCalled.get(), "identify must have been called");

            // No separate identify event exists — the resolved identity is stamped
            // directly onto the tool call event it belongs to.
            capture.flush();
            boolean hasIdentifyEvent = capture.getEvents().stream()
                    .anyMatch(e -> "agentcat:identify".equals(e.getEventType()));
            assertFalse(hasIdentifyEvent, "no separate identify event must be published");

            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            PublishEventRequest event = events.get(0);
            assertEquals("add_note", event.getResourceName());
            assertEquals(testUserId, event.getIdentifyActorGivenId());
            assertEquals(testUserData, (Map<String, Object>) event.getIdentifyData());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void identifyRunsOnEveryCallWithNoDedup() throws Exception {
        // ported from: "runs identify on every tool call and stamps every event, with no dedup"
        AtomicInteger identifyCallCount = new AtomicInteger(0);
        String userId = "user-" + UUID.randomUUID();

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) -> {
            identifyCallCount.incrementAndGet();
            return CompletableFuture.completedFuture(
                    new UserIdentity(userId, null, Map.of("name", "Another User")));
        });

        try (McpTestContext ctx = setupHighLevel(opts)) {
            for (String text : List.of("first", "second", "third")) {
                ctx.client().callTool(new McpSchema.CallToolRequest("add_note",
                        Map.of("text", text, "context", "no-dedup test")));
            }
            assertEquals(3, identifyCallCount.get());

            capture.flush(3);
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(3, events.size());
            for (PublishEventRequest event : events) {
                assertEquals(userId, event.getIdentifyActorGivenId());
                assertEquals(Map.of("name", "Another User"),
                        (Map<String, Object>) event.getIdentifyData());
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void reResolvesIdentityPerRequest() throws Exception {
        // ported from: "re-resolves identity per request so a changed identity lands on the next event"
        UserIdentity[] identities = {
                new UserIdentity("event-user-a", "First", Map.of("seat", "a")),
                new UserIdentity("event-user-b", "Second", Map.of("seat", "b"))
        };
        AtomicInteger call = new AtomicInteger(0);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.completedFuture(identities[call.getAndIncrement()]));

        try (McpTestContext ctx = setupHighLevel(opts)) {
            for (String text : List.of("First call", "Second call")) {
                ctx.client().callTool(new McpSchema.CallToolRequest("add_note",
                        Map.of("text", text, "context", "identity churn test")));
            }

            capture.flush(2);
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(2, events.size());
            assertEquals("event-user-a", events.get(0).getIdentifyActorGivenId());
            assertEquals("First", events.get(0).getIdentifyActorName());
            assertEquals(Map.of("seat", "a"), (Map<String, Object>) events.get(0).getIdentifyData());
            assertEquals("event-user-b", events.get(1).getIdentifyActorGivenId());
            assertEquals("Second", events.get(1).getIdentifyActorName());
            assertEquals(Map.of("seat", "b"), (Map<String, Object>) events.get(1).getIdentifyData());
        }
    }

    @Test
    void publishesAnonymouslyWhenIdentifyReturnsNull() throws Exception {
        // ported from: "publishes anonymously when identify returns null"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.completedFuture(null));

        try (McpTestContext ctx = setupHighLevel(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("add_note",
                            Map.of("text", "anon", "context", "null identity test")));

            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t -> t.contains("Added note")),
                    "tool must produce output");

            capture.flush();
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            PublishEventRequest event = events.get(0);
            assertNull(event.getIdentifyActorGivenId());
            assertNull(event.getIdentifyActorName());
        }
    }

    @Test
    void identifyDegradesToAnonymousWhenThrows() throws Exception {
        // ported from: "degrades to anonymous when identify throws; the tool call still succeeds"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) -> {
            throw new RuntimeException("Failed to identify user");
        });

        try (McpTestContext ctx = setupHighLevel(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("add_note",
                            Map.of("text", "err", "context", "identify error test")));

            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t -> t.contains("Added note")),
                    "tool must produce output");

            capture.flush();
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            PublishEventRequest event = events.get(0);
            assertNull(event.getIdentifyActorGivenId());
            assertNull(event.getIdentifyActorName());
        }
    }

    @Test
    void handlesIdentifyReturningInvalidDataByLeavingActorFieldsEmpty() throws Exception {
        // ported from: "handles identify returning invalid data by leaving actor fields empty"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.completedFuture(
                        new UserIdentity(null, null, null)));

        try (McpTestContext ctx = setupHighLevel(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("add_note",
                            Map.of("text", "invalid", "context", "invalid identity test")));

            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t -> t.contains("Added note")),
                    "tool must produce output");

            capture.flush();
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            assertNull(events.get(0).getIdentifyActorGivenId());
            assertNull(events.get(0).getIdentifyActorName());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncIdentifyNeverDelaysToolCallReturn() throws Exception {
        // ported from: "returns the call while async identify is still in flight"
        AtomicBoolean asyncCompleted = new AtomicBoolean(false);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.supplyAsync(() -> {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    asyncCompleted.set(true);
                    return new UserIdentity("async-user", null, Map.of("source", "async-lookup"));
                }));

        try (McpTestContext ctx = setupHighLevel(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("add_note",
                            Map.of("text", "async", "context", "async identify test")));

            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t -> t.contains("Added note")),
                    "tool must produce output");

            capture.flush();
            assertTrue(asyncCompleted.get(), "async identify must have completed");
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            assertEquals("async-user", events.get(0).getIdentifyActorGivenId());
            assertEquals(Map.of("source", "async-lookup"),
                    (Map<String, Object>) events.get(0).getIdentifyData());
        }
    }

    @Test
    void doesNotRunIdentifyOrPublishEventForToolsList() throws Exception {
        // ported from: "does not run identify or publish an event for tools/list"
        AtomicBoolean identifyCalled = new AtomicBoolean(false);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) -> {
            identifyCalled.set(true);
            return CompletableFuture.completedFuture(
                    new UserIdentity("list-user", null, null));
        });

        try (McpTestContext ctx = setupHighLevel(opts)) {
            // Reset — setupHighLevel already called listTools() once for schema priming
            identifyCalled.set(false);
            capture.clear();

            ctx.client().listTools();
            Thread.sleep(100);

            assertFalse(identifyCalled.get(), "identify must not be called for tools/list");
            assertTrue(capture.getEvents().isEmpty(), "no events for tools/list");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "v2 hooks: eventTags"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void attachesTagsToToolCallEvents() throws Exception {
        // ported from: "attaches customer tags; SDK handle tags ride alongside"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        opts.setEventTags((request, extra) ->
                CompletableFuture.completedFuture(Map.of("env", "test", "trace_id", "abc-123")));

        try (McpTestContext ctx = setupHighLevel(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("add_note",
                    Map.of("text", "tags",
                           "context", "tag test",
                           "agent_id", "opus-4.80-1m|claude-code|k3n9x")));

            capture.flush();
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            PublishEventRequest event = events.get(0);

            assertEquals(Map.of("env", "test", "trace_id", "abc-123"),
                    customerTags(event.getTags()));
            assertEquals("minted", event.getTags().get(AGENTCAT_TAG_SESSION_SOURCE));
            assertEquals("supplied", event.getTags().get(AGENTCAT_TAG_AGENT_SOURCE));
            assertEquals("opus-4.80-1m|claude-code|k3n9x",
                    event.getTags().get(AGENTCAT_TAG_AGENT_ID));
        }
    }

    @Test
    void overwritesCustomerTagThatCollidesWithSdkTagName() throws Exception {
        // ported from: "SDK tag wins when a customer tag collides with an SDK tag name"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventTags((request, extra) ->
                CompletableFuture.completedFuture(Map.of(
                        AGENTCAT_TAG_SESSION_SOURCE, "customer-value",
                        "env", "test"
                )));

        try (McpTestContext ctx = setupHighLevel(opts)) {
            String sessionId = TestUtils.sid("supplied");
            ctx.client().callTool(new McpSchema.CallToolRequest("add_note",
                    Map.of("text", "collision",
                           "context", "tag test",
                           "session_id", sessionId)));

            capture.flush();
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            // SDK tags are merged last, so ours wins the collision.
            assertEquals("supplied", events.get(0).getTags().get(AGENTCAT_TAG_SESSION_SOURCE));
            assertEquals(Map.of("env", "test"), customerTags(events.get(0).getTags()));
        }
    }

    @Test
    void doesNotBlockToolCallsWhenTagsCallbackThrows() throws Exception {
        // ported from: "does not block tool calls when the callback throws; only SDK tags remain"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventTags((request, extra) -> {
            throw new RuntimeException("Tags callback failed");
        });

        try (McpTestContext ctx = setupHighLevel(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("add_note",
                            Map.of("text", "throw", "context", "tag test")));

            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t -> t.contains("Added note")),
                    "tool must produce output");

            capture.flush();
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            assertEquals(Map.of(), customerTags(events.get(0).getTags()));
            assertTrue(sdkTags(events.get(0).getTags()).containsKey(AGENTCAT_TAG_SESSION_SOURCE),
                    "SDK handle tags must still be present");
        }
    }

    @Test
    void handlesNullReturnFromTagsCallback() throws Exception {
        // ported from: "handles null return from the callback"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventTags((request, extra) ->
                CompletableFuture.completedFuture(null));

        try (McpTestContext ctx = setupHighLevel(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("add_note",
                    Map.of("text", "null", "context", "tag test")));

            capture.flush();
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            assertEquals(Map.of(), customerTags(events.get(0).getTags()));
            assertTrue(sdkTags(events.get(0).getTags()).containsKey(AGENTCAT_TAG_SESSION_SOURCE));
        }
    }

    @Test
    void validatesTagsFromCallbackDroppingInvalidKeys() throws Exception {
        // ported from: "validates tags from the callback, dropping invalid keys"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventTags((request, extra) ->
                CompletableFuture.completedFuture(Map.of(
                        "valid", "value",
                        "invalid!", "value"
                )));

        try (McpTestContext ctx = setupHighLevel(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("add_note",
                    Map.of("text", "validate", "context", "tag test")));

            capture.flush();
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            assertEquals(Map.of("valid", "value"), customerTags(events.get(0).getTags()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "v2 hooks: eventProperties"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void attachesPropertiesToToolCallEvents() throws Exception {
        // ported from: "attaches properties to tool call events"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventProperties((request, extra) ->
                CompletableFuture.completedFuture(Map.of(
                        "device", "desktop",
                        "feature_flags", List.of("dark_mode"),
                        "nested", Map.of("key", "value")
                )));

        try (McpTestContext ctx = setupHighLevel(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("add_note",
                    Map.of("text", "props", "context", "props test")));

            capture.flush();
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());

            Map<String, Object> props = (Map<String, Object>) events.get(0).getProperties();
            assertNotNull(props, "properties must be present");
            assertEquals("desktop", props.get("device"));
            assertEquals(List.of("dark_mode"), props.get("feature_flags"));
            assertEquals(Map.of("key", "value"), props.get("nested"));
        }
    }

    @Test
    void doesNotBlockToolCallsWhenPropertiesCallbackThrows() throws Exception {
        // ported from: "does not block tool calls when the callback throws"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventProperties((request, extra) -> {
            throw new RuntimeException("Properties callback failed");
        });

        try (McpTestContext ctx = setupHighLevel(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("add_note",
                            Map.of("text", "throw", "context", "props test")));

            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t -> t.contains("Added note")),
                    "tool must produce output");
        }
    }

    @Test
    void omitsPropertiesWhenCallbackReturnsNull() throws Exception {
        // ported from: "omits properties when the callback returns null"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventProperties((request, extra) ->
                CompletableFuture.completedFuture(null));

        try (McpTestContext ctx = setupHighLevel(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("add_note",
                    Map.of("text", "null", "context", "props test")));

            capture.flush();
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            assertNull(events.get(0).getProperties(),
                    "properties must be null when callback returns null");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void attachesBothTagsAndPropertiesToSameEvent() throws Exception {
        // ported from: "attaches both tags and properties to the same event"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventTags((request, extra) ->
                CompletableFuture.completedFuture(Map.of("env", "test")));
        opts.setEventProperties((request, extra) ->
                CompletableFuture.completedFuture(Map.of("device", "mobile")));

        try (McpTestContext ctx = setupHighLevel(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("add_note",
                    Map.of("text", "both", "context", "combined test")));

            capture.flush();
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            assertEquals(Map.of("env", "test"), customerTags(events.get(0).getTags()));

            Map<String, Object> props = (Map<String, Object>) events.get(0).getProperties();
            assertNotNull(props);
            assertEquals(Map.of("device", "mobile"), props);
        }
    }

    @Test
    void addsNeitherTagsNorPropertiesWhenNoCallbacksConfigured() throws Exception {
        // ported from: "adds neither tags nor properties when no callbacks are configured"
        try (McpTestContext ctx = setupHighLevel(new AgentCatOptions())) {
            ctx.client().callTool(new McpSchema.CallToolRequest("add_note",
                    Map.of("text", "bare", "context", "no callback test")));

            capture.flush();
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            assertEquals(Map.of(), customerTags(events.get(0).getTags()));
            assertTrue(sdkTags(events.get(0).getTags()).containsKey(AGENTCAT_TAG_SESSION_SOURCE),
                    "SDK handle tags must always be present");
            assertNull(events.get(0).getProperties(),
                    "properties must be null when no callback configured");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "v2 hooks: redaction"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void redactSensitiveInformationAppliedToEventBeforeSending() throws Exception {
        // ported from: "redactSensitiveInformation applied to v2 event"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setRedactSensitiveInformation(text ->
                CompletableFuture.completedFuture(text.replace("secret-sauce", "[REDACTED]")));

        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("v2-hooks-redact", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("leaky")
                                .description("Leaks")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("query", Map.of("type", "string")))
                                        .required(List.of("query"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            String query = String.valueOf(req.arguments().get("query"));
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("result for " + query + ": secret-sauce")
                                    .build();
                        })
                .build();
        AgentCat.track(server, "test-project", opts);

        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            ctx.client().listTools();
            ctx.client().callTool(new McpSchema.CallToolRequest("leaky",
                    Map.of("query", "find the secret-sauce",
                           "context", "redaction test")));

            capture.flush();
            PublishEventRequest event = capture.findByTool("leaky");
            assertNotNull(event, "event must be published");

            // Response text must be redacted
            Map<String, Object> response = (Map<String, Object>) event.getResponse();
            assertNotNull(response);
            List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
            assertNotNull(content);
            String responseText = content.stream()
                    .filter(c -> "text".equals(c.get("type")))
                    .map(c -> (String) c.get("text"))
                    .filter(t -> t != null && !t.startsWith("[session_id"))
                    .findFirst()
                    .orElse(null);
            assertNotNull(responseText, "response text must be present");
            assertTrue(responseText.contains("[REDACTED]"),
                    "response must contain [REDACTED], got: " + responseText);
            assertFalse(responseText.contains("secret-sauce"),
                    "response must not contain secret-sauce after redaction");

            // Parameters pass through the same hook
            Map<String, Object> params = (Map<String, Object>) event.getParameters();
            assertNotNull(params);
            String queryValue = findStringInMap(params, "query");
            assertNotNull(queryValue, "query param must be present in event");
            assertEquals("find the [REDACTED]", queryValue, "query param must be redacted");
        }
    }

    @Test
    void redactEventCanDropAnEvent() throws Exception {
        // ported from: "redactEvent returning null drops the event"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setRedactEvent(event -> CompletableFuture.completedFuture(null));

        try (McpTestContext ctx = setupHighLevel(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("add_note",
                            Map.of("text", "should be dropped", "context", "drop test")));

            assertNotNull(result, "tool call must succeed");

            Thread.sleep(200);
            assertTrue(capture.getEvents().isEmpty(),
                    "no events must be published when redactEvent returns null");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "v2 hooks: concurrency"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void hooksRunConcurrentlyWithToolHandlerAndNeverDelayResponse() throws Exception {
        // ported from: "hooks run concurrently — never delay MCP response"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.supplyAsync(() -> {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    return new UserIdentity("slow-user", null, null);
                }));
        opts.setEventTags((request, extra) ->
                CompletableFuture.supplyAsync(() -> {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    return Map.of("env", "slow");
                }));
        opts.setEventProperties((request, extra) ->
                CompletableFuture.supplyAsync(() -> {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    return Map.of("slow", (Object) true);
                }));

        try (McpTestContext ctx = setupHighLevel(opts)) {
            long start = System.currentTimeMillis();
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("add_note",
                            Map.of("text", "fast", "context", "concurrency test")));
            long elapsed = System.currentTimeMillis() - start;

            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t -> t.contains("Added note")),
                    "tool must produce output");

            // The tool call must return quickly — hooks must not delay it.
            // Each hook sleeps 200ms but they run concurrently with the handler.
            // Allow generous margin for HTTP overhead, but the 200ms hook sleep
            // must not stack onto the response time.
            assertTrue(elapsed < 500,
                    "response must return before hooks complete (elapsed=" + elapsed + "ms)");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "v2 real-wire client identity"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void stampsConnectingClientIdentityOntoEvents() throws Exception {
        // ported from: "stamps the connecting client's own identity onto captured events"
        try (McpTestContext ctx = setupHighLevel(new AgentCatOptions())) {
            ctx.client().callTool(new McpSchema.CallToolRequest("add_note",
                    Map.of("text", "who am I", "context", "real-wire identity test")));

            capture.flush();
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            // The MCP client library sends its name/version during initialize.
            // We just verify the fields are populated (actual values depend on
            // the Java MCP client implementation).
            assertNotNull(events.get(0).getClientName(),
                    "clientName must be populated from real-wire handshake");
            assertNotNull(events.get(0).getClientVersion(),
                    "clientVersion must be populated from real-wire handshake");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "(request, extra) arguments carry the v2 ServerContext"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void passesRawRequestAndExtraToEveryHook() throws Exception {
        // ported from: "passes the raw request and the v2 ServerContext to every hook"
        Map<String, Object[]> seen = new LinkedHashMap<>();

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) -> {
            seen.put("identify", new Object[]{request, extra});
            return CompletableFuture.completedFuture(
                    new UserIdentity("ctx-user", null, null));
        });
        opts.setEventTags((request, extra) -> {
            seen.put("eventTags", new Object[]{request, extra});
            return CompletableFuture.completedFuture(Map.of("env", "test"));
        });
        opts.setEventProperties((request, extra) -> {
            seen.put("eventProperties", new Object[]{request, extra});
            return CompletableFuture.completedFuture(Map.of("device", "desktop"));
        });

        try (McpTestContext ctx = setupHighLevel(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("add_note",
                    Map.of("text", "ctx", "context", "extra shape test")));

            capture.flush();

            for (String hookName : List.of("identify", "eventTags", "eventProperties")) {
                Object[] args = seen.get(hookName);
                assertNotNull(args, hookName + " must have been called");
                McpSchema.CallToolRequest request = (McpSchema.CallToolRequest) args[0];
                assertNotNull(request, hookName + " must receive the request");
                assertEquals("add_note", request.name(),
                        hookName + " must see the tool name");
                assertEquals("ctx", request.arguments().get("text"),
                        hookName + " must see the raw arguments (injected params included)");
                assertEquals("extra shape test", request.arguments().get("context"),
                        hookName + " must see the context param");
                assertNotNull(args[1], hookName + " must receive extra");
            }
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static String findStringInMap(Map<String, Object> map, String key) {
        if (map.containsKey(key)) {
            Object v = map.get(key);
            return v instanceof String s ? s : null;
        }
        for (Object value : map.values()) {
            if (value instanceof Map<?, ?> nested) {
                String found = findStringInMap((Map<String, Object>) nested, key);
                if (found != null) return found;
            }
        }
        return null;
    }
}
