package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.CustomEventData;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.integration.TestUtils;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/event-tags-properties.test.ts
 *
 * Tests for event tag/property validation and SDK handle tag injection.
 */
class EventTagsPropertiesTest {

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

    /**
     * Splits captured tags into customer-provided tags (excludes SDK handle tags).
     */
    private static Map<String, String> customerTags(Map<String, String> tags) {
        if (tags == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>(tags);
        SDK_TAG_KEYS.forEach(out::remove);
        return out;
    }

    /**
     * Extracts only SDK handle tags.
     */
    private static Map<String, String> sdkTags(Map<String, String> tags) {
        if (tags == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : SDK_TAG_KEYS) {
            if (tags.containsKey(key)) out.put(key, tags.get(key));
        }
        return out;
    }

    /**
     * Creates a simple echo server with AgentCat.track() applied.
     * Matches the TypeScript setupTestServerAndClient() pattern.
     */
    private McpTestContext setupServer(AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("event-tags-properties-test", "1.0.0")
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
        ctx.client().listTools(); // prime schema registry
        return ctx;
    }

    private PublishEventRequest findToolCallEvent() {
        return capture.getEvents().stream()
                .filter(e -> "mcp:tools/call".equals(e.getEventType()))
                .findFirst()
                .orElse(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SDK tag key constants — unit test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void sdkTagKeysHaveCorrectWireValues() {
        assertEquals("agentcat_session_id_source", AGENTCAT_TAG_SESSION_SOURCE);
        assertEquals("agentcat_agent_id", AGENTCAT_TAG_AGENT_ID);
        assertEquals("agentcat_agent_id_source", AGENTCAT_TAG_AGENT_SOURCE);
        assertEquals("agentcat_protocol_version", AGENTCAT_TAG_PROTOCOL_VERSION);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "eventTags callback"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void attachesTagsToToolCallEvents() throws Exception {
        // ported from: "should attach tags to tool call events"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        opts.setEventTags((request, extra) ->
                CompletableFuture.completedFuture(Map.of(
                        "env", "test",
                        "trace_id", "abc-123"
                )));

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Test todo",
                           "agent_id", "opus-4.80-1m|claude-code|k3n9x")));

            capture.flush();
            PublishEventRequest event = findToolCallEvent();
            assertNotNull(event, "tool call event must exist");

            assertEquals(Map.of("env", "test", "trace_id", "abc-123"),
                    customerTags(event.getTags()));

            // SDK handle tags ride alongside the customer's, never replacing them.
            assertEquals("minted", event.getTags().get(AGENTCAT_TAG_SESSION_SOURCE));
            assertEquals("supplied", event.getTags().get(AGENTCAT_TAG_AGENT_SOURCE));
            assertEquals("opus-4.80-1m|claude-code|k3n9x", event.getTags().get(AGENTCAT_TAG_AGENT_ID));
        }
    }

    @Test
    void overwritesCustomerTagThatCollidesWithSdkTagName() throws Exception {
        // ported from: "should overwrite a customer tag that collides with an SDK tag name"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventTags((request, extra) ->
                CompletableFuture.completedFuture(Map.of(
                        AGENTCAT_TAG_SESSION_SOURCE, "customer-value",
                        "env", "test"
                )));

        try (McpTestContext ctx = setupServer(opts)) {
            String sessionId = TestUtils.sid("supplied");

            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Test todo",
                           "session_id", sessionId)));

            capture.flush();
            PublishEventRequest event = findToolCallEvent();
            assertNotNull(event, "tool call event must exist");

            // SDK tags are merged last, so ours wins the collision.
            assertEquals("supplied", event.getTags().get(AGENTCAT_TAG_SESSION_SOURCE));

            // Non-colliding customer tags survive untouched.
            assertEquals(Map.of("env", "test"), customerTags(event.getTags()));
        }
    }

    @Test
    void doesNotBlockToolCallsWhenTagsCallbackThrows() throws Exception {
        // ported from: "should not block tool calls when callback throws"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventTags((request, extra) -> {
            throw new RuntimeException("Tags callback failed");
        });

        try (McpTestContext ctx = setupServer(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("text", "Test todo")));

            // Tool call must succeed despite callback failure.
            List<String> texts = TestUtils.textContents(result);
            assertFalse(texts.isEmpty(), "tool must produce output");
            assertTrue(texts.stream().anyMatch(t -> t.contains("echo: Test todo")),
                    "tool response must contain echoed text");

            capture.flush();
            PublishEventRequest event = findToolCallEvent();
            assertNotNull(event, "event must still be published");

            // When the callback throws, no customer tags land — only SDK handle tags.
            assertEquals(Map.of(), customerTags(event.getTags()));
            assertTrue(sdkTags(event.getTags()).containsKey(AGENTCAT_TAG_SESSION_SOURCE),
                    "SDK handle tags must still be present");
        }
    }

    @Test
    void handlesNullReturnFromTagsCallback() throws Exception {
        // ported from: "should handle null return from callback"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventTags((request, extra) ->
                CompletableFuture.completedFuture(null));

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Test todo")));

            capture.flush();
            PublishEventRequest event = findToolCallEvent();
            assertNotNull(event, "tool call event must exist");

            assertEquals(Map.of(), customerTags(event.getTags()));
            assertTrue(sdkTags(event.getTags()).containsKey(AGENTCAT_TAG_SESSION_SOURCE),
                    "SDK handle tags must still be present");
        }
    }

    @Test
    void validatesTagsFromCallback() throws Exception {
        // ported from: "should validate tags from callback"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventTags((request, extra) ->
                CompletableFuture.completedFuture(Map.of(
                        "valid", "value",
                        "invalid!", "value"   // '!' is not an allowed key character
                )));

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Test todo")));

            capture.flush();
            PublishEventRequest event = findToolCallEvent();
            assertNotNull(event, "tool call event must exist");

            // Only valid tags survive validation.
            assertEquals(Map.of("valid", "value"), customerTags(event.getTags()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "eventProperties callback"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void attachesPropertiesToToolCallEvents() throws Exception {
        // ported from: "should attach properties to tool call events"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventProperties((request, extra) ->
                CompletableFuture.completedFuture(Map.of(
                        "device", "desktop",
                        "feature_flags", List.of("dark_mode"),
                        "nested", Map.of("key", "value")
                )));

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Test todo")));

            capture.flush();
            PublishEventRequest event = findToolCallEvent();
            assertNotNull(event, "tool call event must exist");

            Map<String, Object> props = (Map<String, Object>) event.getProperties();
            assertNotNull(props, "properties must be present");
            assertEquals("desktop", props.get("device"));
            assertEquals(List.of("dark_mode"), props.get("feature_flags"));
            assertEquals(Map.of("key", "value"), props.get("nested"));
        }
    }

    @Test
    void doesNotBlockToolCallsWhenPropertiesCallbackThrows() throws Exception {
        // ported from: "should not block tool calls when callback throws"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventProperties((request, extra) -> {
            throw new RuntimeException("Properties callback failed");
        });

        try (McpTestContext ctx = setupServer(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("text", "Test todo")));

            // Tool call must succeed despite callback failure.
            List<String> texts = TestUtils.textContents(result);
            assertFalse(texts.isEmpty(), "tool must produce output");
            assertTrue(texts.stream().anyMatch(t -> t.contains("echo: Test todo")),
                    "tool response must contain echoed text");
        }
    }

    @Test
    void handlesNullReturnFromPropertiesCallback() throws Exception {
        // ported from: "should handle null return from callback"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventProperties((request, extra) ->
                CompletableFuture.completedFuture(null));

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Test todo")));

            capture.flush();
            PublishEventRequest event = findToolCallEvent();
            assertNotNull(event, "tool call event must exist");

            assertNull(event.getProperties(),
                    "properties must be null/undefined when callback returns null");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "both callbacks configured"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void attachesBothTagsAndPropertiesToSameEvent() throws Exception {
        // ported from: "should attach both tags and properties to the same event"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventTags((request, extra) ->
                CompletableFuture.completedFuture(Map.of("env", "test")));
        opts.setEventProperties((request, extra) ->
                CompletableFuture.completedFuture(Map.of("device", "mobile")));

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Test todo")));

            capture.flush();
            PublishEventRequest event = findToolCallEvent();
            assertNotNull(event, "tool call event must exist");

            assertEquals(Map.of("env", "test"), customerTags(event.getTags()));

            Map<String, Object> props = (Map<String, Object>) event.getProperties();
            assertNotNull(props, "properties must be present");
            assertEquals(Map.of("device", "mobile"), props);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "no callbacks configured"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void doesNotAddTagsOrPropertiesWhenNotConfigured() throws Exception {
        // ported from: "should not add tags or properties when not configured"
        AgentCatOptions opts = new AgentCatOptions();

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Test todo")));

            capture.flush();
            PublishEventRequest event = findToolCallEvent();
            assertNotNull(event, "tool call event must exist");

            // No customer callbacks means no customer tags.
            assertEquals(Map.of(), customerTags(event.getTags()));
            // SDK handle tags are unconditional and always present.
            assertTrue(sdkTags(event.getTags()).containsKey(AGENTCAT_TAG_SESSION_SOURCE),
                    "SDK handle tags must always be present");
            // No properties when no callback.
            assertNull(event.getProperties(),
                    "properties must be null/undefined when no callback configured");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "initialize is no longer an event"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void publishesNoInitializeEventEvenWhenTagPropertyCallbacksSet() throws Exception {
        // ported from: "publishes no initialize event even when tag/property callbacks are set"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventTags((request, extra) ->
                CompletableFuture.completedFuture(Map.of("env", "test", "source", "init")));
        opts.setEventProperties((request, extra) ->
                CompletableFuture.completedFuture(Map.of("device", "desktop")));

        // Track BEFORE connecting (strongest scenario for init event to appear).
        try (McpTestContext ctx = setupServer(opts)) {
            // The McpTestContext connect + initialize already happened in start().
            // Make a tool call to ensure the pipeline is active.
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "probe")));
            capture.flush();

            // No initialize event must exist.
            PublishEventRequest initEvent = capture.getEvents().stream()
                    .filter(e -> "mcp:initialize".equals(e.getEventType()))
                    .findFirst()
                    .orElse(null);
            assertNull(initEvent, "no initialize event must be published");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "tools/list is no longer an event"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void publishesNoToolsListEventEvenWhenTagPropertyCallbacksSet() throws Exception {
        // ported from: "publishes no tools/list event even when tag/property callbacks are set"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventTags((request, extra) ->
                CompletableFuture.completedFuture(Map.of("env", "test", "action", "list")));
        opts.setEventProperties((request, extra) ->
                CompletableFuture.completedFuture(Map.of("source", "list-test")));

        try (McpTestContext ctx = setupServer(opts)) {
            // Trigger tools/list (already called once in setupServer; call again explicitly)
            ctx.client().listTools();

            Thread.sleep(100); // wait for any potential event
            PublishEventRequest listEvent = capture.getEvents().stream()
                    .filter(e -> "mcp:tools/list".equals(e.getEventType()))
                    .findFirst()
                    .orElse(null);
            assertNull(listEvent, "no tools/list event must be published");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "redaction bypass"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void doesNotRedactTagsOrPropertiesWhenRedactSensitiveInformationConfigured() throws Exception {
        // ported from: "should not redact tags or properties when redactSensitiveInformation is configured"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setRedactSensitiveInformation(s ->
                CompletableFuture.completedFuture("[REDACTED]"));
        opts.setEventTags((request, extra) ->
                CompletableFuture.completedFuture(Map.of(
                        "env", "production",
                        "trace_id", "abc-123"
                )));
        opts.setEventProperties((request, extra) ->
                CompletableFuture.completedFuture(Map.of(
                        "device", "desktop",
                        "app_version", "2.1.0",
                        "feature_flags", List.of("dark_mode", "beta_ui")
                )));

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Test todo")));

            capture.flush();
            PublishEventRequest event = findToolCallEvent();
            assertNotNull(event, "tool call event must exist");

            // Tags should NOT be redacted — customer explicitly provides this data.
            assertEquals(Map.of("env", "production", "trace_id", "abc-123"),
                    customerTags(event.getTags()));

            // Properties should NOT be redacted.
            Map<String, Object> props = (Map<String, Object>) event.getProperties();
            assertNotNull(props, "properties must be present");
            assertEquals("desktop", props.get("device"));
            assertEquals("2.1.0", props.get("app_version"));
            assertEquals(List.of("dark_mode", "beta_ui"), props.get("feature_flags"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "callback receives correct arguments"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void passesRequestAndExtraToEventTagsCallback() throws Exception {
        // ported from: "should pass request and extra to eventTags callback"
        AtomicReference<McpSchema.CallToolRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<Object> capturedExtra = new AtomicReference<>();

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventTags((request, extra) -> {
            capturedRequest.set(request);
            capturedExtra.set(extra);
            return CompletableFuture.completedFuture(Map.of("env", "test"));
        });

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Callback args test")));

            capture.flush();

            McpSchema.CallToolRequest req = capturedRequest.get();
            assertNotNull(req, "callback must receive the request");
            assertEquals("echo", req.name());
            assertEquals("Callback args test", req.arguments().get("text"));
            assertNotNull(capturedExtra.get(), "callback must receive extra");
        }
    }

    @Test
    void passesRequestAndExtraToEventPropertiesCallback() throws Exception {
        // ported from: "should pass request and extra to eventProperties callback"
        AtomicReference<McpSchema.CallToolRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<Object> capturedExtra = new AtomicReference<>();

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEventProperties((request, extra) -> {
            capturedRequest.set(request);
            capturedExtra.set(extra);
            return CompletableFuture.completedFuture(Map.of("device", "test"));
        });

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("text", "Props callback args test")));

            capture.flush();

            McpSchema.CallToolRequest req = capturedRequest.get();
            assertNotNull(req, "callback must receive the request");
            assertEquals("echo", req.name());
            assertNotNull(capturedExtra.get(), "callback must receive extra");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // describe: "publishCustomEvent"
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void includesTagsAndPropertiesPassedDirectly() throws Exception {
        // ported from: "should include tags and properties passed directly"
        try (McpTestContext ctx = setupServer(new AgentCatOptions())) {
            CustomEventData data = new CustomEventData();
            data.resourceName = "custom-action";
            data.tags = Map.of("env", "production", "trace_id", "xyz");
            data.properties = new LinkedHashMap<>(Map.of(
                    "device", "mobile",
                    "feature_flags", List.of("beta")
            ));

            AgentCat.publishCustomEvent(ctx.server(), "test-project", data);
            capture.flush();

            PublishEventRequest event = capture.getEvents().stream()
                    .filter(e -> "custom-action".equals(e.getResourceName()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(event, "custom event must be published");
            assertEquals(Map.of("env", "production", "trace_id", "xyz"), event.getTags());
            Map<String, Object> props = (Map<String, Object>) event.getProperties();
            assertNotNull(props, "properties must be present");
            assertEquals("mobile", props.get("device"));
            assertEquals(List.of("beta"), props.get("feature_flags"));
        }
    }

    @Test
    void validatesTagsPassedDirectly() throws Exception {
        // ported from: "should validate tags passed directly"
        try (McpTestContext ctx = setupServer(new AgentCatOptions())) {
            CustomEventData data = new CustomEventData();
            data.resourceName = "custom-action";
            data.tags = new LinkedHashMap<>();
            data.tags.put("valid", "value");
            data.tags.put("bad!key", "value");  // '!' is not an allowed key character

            AgentCat.publishCustomEvent(ctx.server(), "test-project", data);
            capture.flush();

            PublishEventRequest event = capture.getEvents().stream()
                    .filter(e -> "custom-action".equals(e.getResourceName()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(event, "custom event must be published");
            assertEquals(Map.of("valid", "value"), event.getTags());
        }
    }

    @Test
    void normalizesEmptyPropertiesToOmission() throws Exception {
        // ported from: "should normalize empty properties to omission"
        try (McpTestContext ctx = setupServer(new AgentCatOptions())) {
            CustomEventData data = new CustomEventData();
            data.resourceName = "custom-action";
            data.properties = new LinkedHashMap<>();  // empty map

            AgentCat.publishCustomEvent(ctx.server(), "test-project", data);
            capture.flush();

            PublishEventRequest event = capture.getEvents().stream()
                    .filter(e -> "custom-action".equals(e.getResourceName()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(event, "custom event must be published");
            assertNull(event.getProperties(), "empty properties must be omitted");
        }
    }
}
