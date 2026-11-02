package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.integration.TestUtils;
import com.agentcat.sdk.model.UserIdentity;
import com.agentcat.sdk.tools.GetMoreTools;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/report-missing.test.ts
 *
 * Tests for the {@code get_more_tools} feature (previously {@code report_missing}).
 */
class ReportMissingTest {

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
     * Builds a server with two tools ("add_note" and "list_notes") and applies tracking.
     */
    private McpTestContext setupServer(AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("report-missing-test", "1.0.0")
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
                .toolCall(
                        McpSchema.Tool.builder("list_notes")
                                .description("Lists all notes")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of())
                                        .build())
                                .build(),
                        (exchange, req) -> McpSchema.CallToolResult.builder()
                                .addTextContent("Notes: []")
                                .build())
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

    private PublishEventRequest findGetMoreToolsEvent() {
        return capture.getEvents().stream()
                .filter(e -> "mcp:tools/call".equals(e.getEventType()))
                .filter(e -> "get_more_tools".equals(e.getResourceName()))
                .findFirst()
                .orElse(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unit tests — pure helper logic
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getMoreToolsNameIsCorrect() {
        assertEquals("get_more_tools", GetMoreTools.GET_MORE_TOOLS_NAME);
    }

    @Test
    void contextInputSchemaRequiresContextField() {
        Map<String, Object> schema = GetMoreTools.CONTEXT_INPUT_SCHEMA;
        assertNotNull(schema);
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertNotNull(required);
        assertTrue(required.contains("context"),
                "get_more_tools schema must require 'context'");
    }

    @Test
    void extractUserIntentReturnsContextFromArgs() {
        String intent = GetMoreTools.extractUserIntent(Map.of("context", "need a csv export tool"));
        assertEquals("need a csv export tool", intent);
    }

    @Test
    void extractUserIntentReturnsNullForMissingContext() {
        assertNull(GetMoreTools.extractUserIntent(Map.of()));
    }

    @Test
    void extractUserIntentReturnsNullForNullArgs() {
        assertNull(GetMoreTools.extractUserIntent(null));
    }

    @Test
    void extractUserIntentReturnsNullForNonStringContext() {
        assertNull(GetMoreTools.extractUserIntent(Map.of("context", 123)));
    }

    @Test
    void annotationsHaveCorrectValues() {
        // ported from: "should add report_missing to tools list when enableReportMissing is true"
        // (annotation values portion)
        var ann = GetMoreTools.buildAnnotations();
        if (ann == null) {
            // Old SDK without ToolAnnotations — skip
            return;
        }
        assertEquals("Get More Tools", ann.title());
        assertTrue(Boolean.TRUE.equals(ann.readOnlyHint()), "readOnlyHint must be true");
        assertTrue(Boolean.FALSE.equals(ann.destructiveHint()), "destructiveHint must be false");
        assertTrue(Boolean.TRUE.equals(ann.idempotentHint()), "idempotentHint must be true");
        assertTrue(Boolean.FALSE.equals(ann.openWorldHint()), "openWorldHint must be false");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Tool Injection Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void addsGetMoreToolsToToolsListWhenEnableReportMissingIsTrue() throws Exception {
        // ported from: "should add report_missing to tools list when enableReportMissing is true"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(true);
        opts.setEnableTracing(true);

        try (McpTestContext ctx = setupServer(opts)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();

            McpSchema.Tool gmt = tools.stream()
                    .filter(t -> "get_more_tools".equals(t.name()))
                    .findFirst()
                    .orElse(null);

            assertNotNull(gmt, "get_more_tools must be in the tools list");
            assertEquals("get_more_tools", gmt.name());
            assertTrue(gmt.description().contains("Check for additional tools")
                            || gmt.description().contains("additional tools")
                            || gmt.description().contains("more tools"),
                    "description must mention additional/more tools, got: " + gmt.description());

            // Verify context is required
            List<String> required = (List<String>) gmt.inputSchema().get("required");
            assertNotNull(required, "required array must exist");
            assertTrue(required.contains("context"), "context must be required");

            // Verify behavior annotations
            assertNotNull(gmt.annotations(), "annotations must be present");
            assertEquals("Get More Tools", gmt.annotations().title());
            assertTrue(Boolean.TRUE.equals(gmt.annotations().readOnlyHint()));
            assertTrue(Boolean.FALSE.equals(gmt.annotations().destructiveHint()));
            assertTrue(Boolean.TRUE.equals(gmt.annotations().idempotentHint()));
            assertTrue(Boolean.FALSE.equals(gmt.annotations().openWorldHint()));
        }
    }

    @Test
    void doesNotAddGetMoreToolsWhenEnableReportMissingIsFalse() throws Exception {
        // ported from: "should NOT add get_more_tools when enableReportMissing is false"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(false);
        opts.setEnableTracing(true);

        try (McpTestContext ctx = setupServer(opts)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();

            McpSchema.Tool gmt = tools.stream()
                    .filter(t -> "get_more_tools".equals(t.name()))
                    .findFirst()
                    .orElse(null);

            assertNull(gmt, "get_more_tools must NOT be in tools list when disabled");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void addsGetMoreToolsWithoutContextInjectionEvenWhenEnableToolCallContextIsTrue() throws Exception {
        // ported from: "should add report_missing WITHOUT context injection even when enableToolCallContext is true"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(true);
        opts.setEnableToolCallContext(true);
        opts.setEnableTracing(true);

        try (McpTestContext ctx = setupServer(opts)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();

            McpSchema.Tool gmt = tools.stream()
                    .filter(t -> "get_more_tools".equals(t.name()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(gmt, "get_more_tools must be present");

            // Verify context is required on get_more_tools
            List<String> gmtRequired = (List<String>) gmt.inputSchema().get("required");
            assertNotNull(gmtRequired);
            assertTrue(gmtRequired.contains("context"), "get_more_tools must require context");

            // Check that other tools DO have injected context
            McpSchema.Tool addNote = tools.stream()
                    .filter(t -> "add_note".equals(t.name()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(addNote, "add_note must be present");
            Map<String, Object> addNoteProps = (Map<String, Object>) addNote.inputSchema().get("properties");
            assertNotNull(addNoteProps.get("context"),
                    "add_note must have injected context property");
            List<String> addNoteRequired = (List<String>) addNote.inputSchema().get("required");
            assertNotNull(addNoteRequired);
            assertTrue(addNoteRequired.contains("context"),
                    "add_note must require context when enableToolCallContext is true");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Tool Execution Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void successfullyCallsGetMoreToolsWithOnlyContext() throws Exception {
        // ported from: "should successfully call get_more_tools with only context"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(true);
        opts.setEnableTracing(true);

        try (McpTestContext ctx = setupServer(opts)) {
            String context = "Need a database query tool for SQL operations";
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("get_more_tools",
                            Map.of("context", context)));

            // Response should indicate no additional tools found
            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t ->
                            t.contains("Unfortunately") || t.contains("unfortunately") ||
                            t.contains("no additional") || t.contains("No additional")),
                    "response must indicate no more tools, got: " + texts);

            capture.flush();
            PublishEventRequest event = findGetMoreToolsEvent();
            assertNotNull(event, "get_more_tools event must be published");
        }
    }

    @Test
    void successfullyCallsGetMoreToolsWithDescriptionAndOptionalContext() throws Exception {
        // ported from: "should successfully call get_more_tools with description and optional context"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(true);
        opts.setEnableTracing(true);

        try (McpTestContext ctx = setupServer(opts)) {
            String context = "User wants to fetch data from external REST APIs";
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("get_more_tools",
                            Map.of("context", context)));

            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t ->
                            t.contains("Unfortunately") || t.contains("unfortunately") ||
                            t.contains("no additional") || t.contains("No additional")),
                    "response must acknowledge the feedback, got: " + texts);

            capture.flush();
            PublishEventRequest event = findGetMoreToolsEvent();
            assertNotNull(event, "get_more_tools event must be published");
        }
    }

    @Test
    void handlesMissingDescriptionParameterGracefully() throws Exception {
        // ported from: "should handle missing description parameter gracefully"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(true);
        opts.setEnableTracing(true);

        try (McpTestContext ctx = setupServer(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("get_more_tools",
                            Map.of("context", "Some context without description")));

            // Should handle gracefully
            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t ->
                            t.contains("Unfortunately") || t.contains("unfortunately") ||
                            t.contains("no additional") || t.contains("No additional")),
                    "response must handle gracefully, got: " + texts);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Event Tracking Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void tracksGetMoreToolsEventsWithProperStructure() throws Exception {
        // ported from: "should track report_missing events with proper structure"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(true);

        try (McpTestContext ctx = setupServer(opts)) {
            String context = "User wants to monitor file changes in real-time";
            ctx.client().callTool(new McpSchema.CallToolRequest("get_more_tools",
                    Map.of("context", context)));

            capture.flush();
            PublishEventRequest event = findGetMoreToolsEvent();
            assertNotNull(event, "get_more_tools event must be published");

            assertNotNull(event.getSessionId(), "sessionId must be present");
            assertFalse(event.getSessionId().isEmpty(), "sessionId must not be empty");
            assertEquals("get_more_tools", event.getResourceName());
            assertNotNull(event.getParameters(), "parameters must be present");

            // userIntent should be populated from the context argument
            assertNotNull(event.getUserIntent(), "userIntent must be defined");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Integration Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void maintainsTaskContinuityWhenCallingGetMoreToolsBetweenOtherTools() throws Exception {
        // ported from: "should maintain task continuity when calling report_missing between other tools"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(true);
        opts.setEnableTracing(true);

        try (McpTestContext ctx = setupServer(opts)) {
            String sessionId = TestUtils.sid("report_missing_continuity");

            // Call add_note
            ctx.client().callTool(new McpSchema.CallToolRequest("add_note",
                    Map.of("text", "First todo",
                           "context", "Adding first todo to test task continuity",
                           "session_id", sessionId)));

            // Call get_more_tools
            ctx.client().callTool(new McpSchema.CallToolRequest("get_more_tools",
                    Map.of("context", "Need a bulk todo import tool",
                           "session_id", sessionId)));

            // Call list_notes
            ctx.client().callTool(new McpSchema.CallToolRequest("list_notes",
                    Map.of("context", "Listing todos after reporting missing tool",
                           "session_id", sessionId)));

            capture.flush(3);

            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(3, events.size(), "must have 3 tool call events");

            // All should be attributed to the supplied session
            Set<String> sessionIds = new HashSet<>();
            events.forEach(e -> sessionIds.add(e.getSessionId()));
            assertEquals(Set.of(sessionId), sessionIds,
                    "all events must share the supplied session_id");

            // Verify order and tool names
            assertEquals("add_note", events.get(0).getResourceName());
            assertEquals("get_more_tools", events.get(1).getResourceName());
            assertEquals("list_notes", events.get(2).getResourceName());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void worksCorrectlyWithUserIdentification() throws Exception {
        // ported from: "should work correctly with user identification"
        String testUserId = "report-user-" + UUID.randomUUID();
        Map<String, Object> testUserData = Map.of(
                "name", "Report Test User " + UUID.randomUUID(),
                "role", "Developer"
        );

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(true);
        opts.setEnableTracing(true);
        opts.setIdentify((request, extra) ->
                CompletableFuture.completedFuture(
                        new UserIdentity(testUserId, null, testUserData)));

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("get_more_tools",
                    Map.of("context", "Need GraphQL query builder")));

            capture.flush();

            // No separate identify event
            boolean hasIdentifyEvent = capture.getEvents().stream()
                    .anyMatch(e -> "agentcat:identify".equals(e.getEventType()));
            assertFalse(hasIdentifyEvent, "no separate identify event must exist");

            PublishEventRequest event = findGetMoreToolsEvent();
            assertNotNull(event, "get_more_tools event must be published");
            assertEquals(testUserId, event.getIdentifyActorGivenId());
            assertEquals(testUserData, (Map<String, Object>) event.getIdentifyData());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Analytics Value Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void providesActionableInsightsForMissingToolReports() throws Exception {
        // ported from: "should provide actionable insights for missing tool reports"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(true);
        opts.setEnableTracing(true);

        try (McpTestContext ctx = setupServer(opts)) {
            List<String> contexts = List.of(
                    "Want to query PostgreSQL database",
                    "Need to construct complex queries",
                    "Managing schema changes"
            );

            for (String context : contexts) {
                ctx.client().callTool(new McpSchema.CallToolRequest("get_more_tools",
                        Map.of("context", context)));
            }

            capture.flush(3);

            List<PublishEventRequest> reportEvents = capture.getEvents().stream()
                    .filter(e -> "mcp:tools/call".equals(e.getEventType()))
                    .filter(e -> "get_more_tools".equals(e.getResourceName()))
                    .toList();

            assertEquals(3, reportEvents.size(), "must capture all 3 reports");

            for (PublishEventRequest event : reportEvents) {
                assertNotNull(event.getSessionId(), "sessionId must be present");
                assertNotNull(event.getTimestamp(), "timestamp must be present");
            }
        }
    }

    @Test
    void tracksGetMoreToolsAcrossDifferentSessions() throws Exception {
        // ported from: "should track get_more_tools across different sessions"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(true);
        opts.setEnableTracing(true);

        // Two calls without session_id → each gets a new minted session
        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("get_more_tools",
                    Map.of("context", "Need OAuth integration tool")));
        }

        // Second session (new server/client)
        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("get_more_tools",
                    Map.of("context", "Need OAuth2 authentication tool")));
        }

        capture.flush(2);

        List<PublishEventRequest> reportEvents = capture.getEvents().stream()
                .filter(e -> "mcp:tools/call".equals(e.getEventType()))
                .filter(e -> "get_more_tools".equals(e.getResourceName()))
                .toList();

        assertEquals(2, reportEvents.size(), "must have 2 events from different sessions");

        // Different session IDs
        Set<String> sessionIds = new HashSet<>();
        reportEvents.forEach(e -> sessionIds.add(e.getSessionId()));
        assertEquals(2, sessionIds.size(), "must have different session IDs");
    }

    @Test
    void handlesMultipleMissingToolReportsInTheSameTask() throws Exception {
        // ported from: "should handle multiple missing tool reports in the same task"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(true);
        opts.setEnableTracing(true);

        try (McpTestContext ctx = setupServer(opts)) {
            String sessionId = TestUtils.sid("report_missing_workflow");
            List<String> contexts = List.of(
                    "Importing data from spreadsheets",
                    "Need to validate imported data",
                    "Process large datasets"
            );

            for (String context : contexts) {
                ctx.client().callTool(new McpSchema.CallToolRequest("get_more_tools",
                        Map.of("context", context,
                               "session_id", sessionId)));
            }

            capture.flush(3);

            List<PublishEventRequest> reportEvents = capture.getEvents().stream()
                    .filter(e -> "mcp:tools/call".equals(e.getEventType()))
                    .filter(e -> "get_more_tools".equals(e.getResourceName()))
                    .toList();

            assertEquals(3, reportEvents.size(), "must capture all 3 reports");

            // All attributed to the same session
            Set<String> sessionIds = new HashSet<>();
            reportEvents.forEach(e -> sessionIds.add(e.getSessionId()));
            assertEquals(Set.of(sessionId), sessionIds,
                    "all events must share the supplied session_id");
        }
    }
}
