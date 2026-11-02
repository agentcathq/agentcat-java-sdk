package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.CustomEventData;
import com.agentcat.sdk.integration.EventCapture;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/publishCustomEvent.test.ts
 *
 * Tests for the {@code publishCustomEvent} function that allows SDK users to
 * send custom analytics events without a tool call.
 *
 * <p>TypeScript tests are unit tests with heavy mocking (vi.mock). Java tests
 * use the {@link EventCapture} integration harness instead.
 */
class PublishCustomEventTest {

    private EventCapture capture;
    private McpSyncServer server;

    @BeforeEach
    void setUp() throws Exception {
        capture = new EventCapture();
        capture.start();

        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        server = McpServer.sync(transport)
                .serverInfo("custom-event-test-server", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("echo",
                                Map.of("type", "object",
                                       "properties", Map.of("msg", Map.of("type", "string"))))
                                .description("Echo")
                                .build(),
                        (exchange, req) -> McpSchema.CallToolResult.builder()
                                .addTextContent("echo")
                                .build())
                .build();

        AgentCat.track(server, "proj_test", new AgentCatOptions());
    }

    @AfterEach
    void tearDown() throws Exception {
        capture.stop();
        try { server.closeGracefully(); } catch (Exception ignored) {}
    }

    // ── describe: "event structure" ───────────────────────────────────────────

    @Test
    void publishesCustomEventWithCorrectEventType() throws Exception {
        // ported from: "should always use 'agentcat:custom' as event type"
        CustomEventData data = new CustomEventData();
        data.resourceName = "custom-action";

        AgentCat.publishCustomEvent(server, "proj_test", data);
        capture.flush();

        PublishEventRequest event = capture.findByTool("custom-action");
        assertNotNull(event, "custom event must be published");
        assertEquals("proj_test", event.getProjectId());
        assertEquals("agentcat:custom", event.getEventType());
    }

    @Test
    void customEventFromSessionStringUsesCustomEventType() throws Exception {
        CustomEventData data = new CustomEventData();
        data.resourceName = "string-session-action";

        AgentCat.publishCustomEvent("ses_test00000000000000000000000", "proj_test", data);
        capture.flush();

        PublishEventRequest event = capture.findByTool("string-session-action");
        assertNotNull(event, "custom event must be published");
        assertEquals("agentcat:custom", event.getEventType());
    }

    @Test
    void customEventWithoutSessionSendsNullSessionId() throws Exception {
        // TS: sessionId "" on a tracked server → wire null ("server assigns session")
        CustomEventData data = new CustomEventData();
        data.resourceName = "no-session-action";

        AgentCat.publishCustomEvent(server, "proj_test", data);
        capture.flush();

        PublishEventRequest event = capture.findByTool("no-session-action");
        assertNotNull(event, "custom event must be published");
        assertNull(event.getSessionId(), "no session must reach the wire as null, not \"\"");
        assertTrue(event.toJson().contains("\"session_id\":null"), event.toJson());
    }

    @Test
    void includesCustomEventData() throws Exception {
        // ported from: "should publish custom event with tracked server"
        CustomEventData data = new CustomEventData();
        data.sessionId    = "ses_test00000000000000000000000";
        data.resourceName = "custom-action";
        data.message      = "Testing custom event";
        data.parameters   = Map.of("action", "test");

        AgentCat.publishCustomEvent(server, "proj_test", data);
        capture.flush();

        PublishEventRequest event = capture.findByTool("custom-action");
        assertNotNull(event, "custom event must be published");
        assertEquals("ses_test00000000000000000000000", event.getSessionId());
        assertEquals("proj_test",       event.getProjectId());
        assertEquals("custom-action",   event.getResourceName());
        assertEquals("Testing custom event", event.getUserIntent()); // message → userIntent
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) event.getParameters();
        assertNotNull(params);
        assertEquals("test", params.get("action"));
    }

    @Test
    void generatesUniqueEventId() throws Exception {
        // ported from: "should generate a unique event ID"
        // TypeScript checks KSUID-based IDs; in Java EventQueue generates "evt_<uuid>".
        AgentCat.publishCustomEvent(server, "proj_test", null);
        capture.flush();

        List<PublishEventRequest> events = capture.getEvents();
        assertFalse(events.isEmpty(), "at least one event must be published");

        String id1 = events.get(0).getId();
        assertNotNull(id1, "event id must not be null");
        assertTrue(id1.startsWith("evt_"), "event id must start with 'evt_'");

        capture.clear();

        AgentCat.publishCustomEvent(server, "proj_test", null);
        capture.flush();

        String id2 = capture.getEvents().get(0).getId();
        assertNotEquals(id1, id2, "each event must get a unique id");
    }

    @Test
    void blankOrNullProjectIdIsSwallowed() {
        // Fault containment: blank/null projectId must be silently ignored, not thrown.
        // The SDK must never throw to the caller — event is dropped and logged.
        CustomEventData data = new CustomEventData();

        assertDoesNotThrow(
                () -> AgentCat.publishCustomEvent(server, "", data),
                "empty projectId must be swallowed — SDK must never throw to the caller");

        assertDoesNotThrow(
                () -> AgentCat.publishCustomEvent(server, null, data),
                "null projectId must be swallowed — SDK must never throw to the caller");
    }

    @Test
    void handlesNullOrMissingEventData() throws Exception {
        // ported from: "should handle undefined event data gracefully"
        assertDoesNotThrow(() -> AgentCat.publishCustomEvent(server, "proj_test", null));

        capture.flush();

        PublishEventRequest event = capture.getEvents().stream().findFirst().orElse(null);
        assertNotNull(event, "event must still be published when eventData is null");
        assertEquals("proj_test", event.getProjectId());
    }

    @Test
    void sessionIdStringOverloadPublishesEvent() throws Exception {
        // Mirrors TypeScript: publishCustomEvent("ses_xxx", "proj_abc", eventData)
        CustomEventData data = new CustomEventData();
        data.resourceName = "string-session-action";

        AgentCat.publishCustomEvent("ses_test00000000000000000000000", "proj_test", data);
        capture.flush();

        PublishEventRequest event = capture.findByTool("string-session-action");
        assertNotNull(event, "event must be published via session ID string overload");
        assertEquals("ses_test00000000000000000000000", event.getSessionId());
        assertEquals("proj_test", event.getProjectId());
    }

    @Test
    void sessionIdStringOverloadEventDataSessionIdTakesPriority() throws Exception {
        // Mirror TypeScript: sessionId = eventData?.sessionId || serverOrSessionId
        CustomEventData data = new CustomEventData();
        data.sessionId    = "ses_fromdata0000000000000000000";
        data.resourceName = "priority-test";

        AgentCat.publishCustomEvent("ses_fromparam000000000000000000", "proj_test", data);
        capture.flush();

        PublishEventRequest event = capture.findByTool("priority-test");
        assertNotNull(event);
        assertEquals("ses_fromdata0000000000000000000", event.getSessionId(),
                "eventData.sessionId must take priority over the string parameter");
    }
}
