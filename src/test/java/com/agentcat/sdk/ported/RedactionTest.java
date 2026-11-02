package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.integration.TestUtils;
import com.agentcat.sdk.model.UserIdentity;
import com.agentcat.sdk.modules.pipeline.PayloadRedactor;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/redaction.test.ts
 *
 * Tests for string-level and event-level redaction.
 *
 * <p>TypeScript {@code redactEvent(event, redactFn)} walks the whole {@code UnredactedEvent}
 * object skipping protected top-level fields. In Java, {@code PayloadRedactor.apply}
 * walks only the {@code params} and {@code response} maps — protected fields (sessionId,
 * projectId, resourceName, eventType, identifyActor*) live in separate {@link com.agentcat.sdk.model.QueuedEvent}
 * fields and are never fed to the redact function.
 */
class RedactionTest {

    private static Map<String, Object> applyStringRedaction(
            Map<String, Object> map,
            Function<String, CompletableFuture<String>> redactFn
    ) throws Exception {
        return PayloadRedactor.apply(map, redactFn);
    }

    private static final Function<String, CompletableFuture<String>> MOCK_REDACT =
            text -> CompletableFuture.completedFuture("[REDACTED-" + text.length() + "]");

    // ── Integration helpers ───────────────────────────────────────────────────

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

    /** Builds an add_todo + list_todos server with the given opts and starts McpTestContext. */
    private McpTestContext setupServer(AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        List<String> todos = new CopyOnWriteArrayList<>();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("test server", "1.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("add_todo")
                                .description("adds a todo item")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("text", Map.of("type", "string")))
                                        .required(List.of("text"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            String text = String.valueOf(req.arguments().get("text"));
                            todos.add(text);
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("Added todo: " + text)
                                    .build();
                        })
                .toolCall(
                        McpSchema.Tool.builder("list_todos")
                                .description("lists todos")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .build())
                                .build(),
                        (exchange, req) -> McpSchema.CallToolResult.builder()
                                .addTextContent(todos.toString())
                                .build())
                .build();

        AgentCat.track(server, "test-project", opts);
        return McpTestContext.start(transport, server);
    }

    private void callAddTodo(McpTestContext ctx, String text) throws Exception {
        ctx.client().callTool(new McpSchema.CallToolRequest("add_todo",
                Map.of("text", text, "context", "Adding a todo item")));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unit tests — PayloadRedactor.apply
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void redactsBasicStringFields() throws Exception {
        // ported from: "should redact basic string fields"
        // TypeScript walks the whole UnredactedEvent; Java applyStringRedaction walks a map.
        // We mirror the TypeScript test by passing the same data as a map.
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userIntent", "sensitive user intent");   // 21 chars → [REDACTED-21]

        Map<String, Object> result = applyStringRedaction(map, MOCK_REDACT);

        assertEquals("[REDACTED-21]", result.get("userIntent"),
                "string must be passed through the redact function");
    }

    @Test
    void preservesNonStringValues() throws Exception {
        // ported from: "should preserve null and undefined values" and number fields
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("number", 5000);
        map.put("boolVal", true);
        map.put("nullVal", null);

        Map<String, Object> result = applyStringRedaction(map, MOCK_REDACT);

        assertEquals(5000,  result.get("number"),  "numbers must not be redacted");
        assertEquals(true,  result.get("boolVal"), "booleans must not be redacted");
        assertNull(result.get("nullVal"),            "null values must remain null");
    }

    @Test
    void doesNotRedactProtectedFields() throws Exception {
        // ported from: "should not redact protected fields"
        //
        // In Java, protected fields (sessionId, projectId, resourceName, eventType,
        // identifyActorGivenId, identifyActorName) live as dedicated QueuedEvent fields
        // and are NEVER passed to applyStringRedaction. This integration test verifies
        // that even with an aggressive redact-everything function the published event's
        // protected fields remain unchanged.
        AgentCatOptions opts = new AgentCatOptions();
        // Aggressive: would redact anything if it ever saw it
        opts.setRedactSensitiveInformation(s -> CompletableFuture.completedFuture("[REDACTED-ALL]"));
        opts.setIdentify((req, extra) -> CompletableFuture.completedFuture(
                new UserIdentity("actor-123", "John Doe", null)));

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().listTools();
            callAddTodo(ctx, "test todo text");

            capture.flush();
            PublishEventRequest event = capture.findByTool("add_todo");
            assertNotNull(event, "event must be captured");

            // Protected fields — must NOT be redacted
            assertTrue(event.getSessionId().startsWith("ses_"),
                    "sessionId must be preserved — was: " + event.getSessionId());
            assertEquals("test-project", event.getProjectId(),
                    "projectId must be preserved");
            assertEquals("add_todo", event.getResourceName(),
                    "resourceName must be preserved");
            assertNotNull(event.getEventType(),
                    "eventType must be preserved");
            assertEquals("actor-123", event.getIdentifyActorGivenId(),
                    "identifyActorGivenId must be preserved");
            assertEquals("John Doe", event.getIdentifyActorName(),
                    "identifyActorName must be preserved");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void redactsNestedStringFields() throws Exception {
        // ported from: "should redact nested string fields"
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("query", "sensitive query");            // 15 chars
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("apiKey", "secret-key-123");       // 14 chars
        options.put("timeout", 5000);
        map.put("options", options);

        Map<String, Object> result = applyStringRedaction(map, MOCK_REDACT);

        assertEquals("[REDACTED-15]", result.get("query"),
                "top-level string must be redacted");
        Map<String, Object> resultOptions = (Map<String, Object>) result.get("options");
        assertNotNull(resultOptions, "nested map must be present");
        assertEquals("[REDACTED-14]", resultOptions.get("apiKey"),
                "nested string must be redacted");
        assertEquals(5000, resultOptions.get("timeout"),
                "nested number must not be redacted");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integration tests — redactSensitiveInformation (string-level) via McpTestContext
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void redactionIntegration_properlyRedactsSensitiveData() throws Exception {
        // ported from: "should properly redact sensitive data in published events"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setRedactSensitiveInformation(text -> {
            if (text.contains("@")) {
                return CompletableFuture.completedFuture("[REDACTED-EMAIL]");
            }
            if (text.toLowerCase().contains("secret") || text.toLowerCase().contains("key")) {
                return CompletableFuture.completedFuture("[REDACTED-SECRET]");
            }
            if (text.toLowerCase().contains("password") || text.toLowerCase().contains("ssn")) {
                return CompletableFuture.completedFuture("[REDACTED-SENSITIVE]");
            }
            return CompletableFuture.completedFuture(text);
        });
        opts.setIdentify((req, extra) -> CompletableFuture.completedFuture(
                new UserIdentity("test-user-123", "John Doe", null)));

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().listTools();
            ctx.client().callTool(new McpSchema.CallToolRequest("add_todo",
                    Map.of("text", "Send email to admin@company.com with password reset",
                           "context", "Adding a todo item for reset task")));

            capture.flush();
            PublishEventRequest event = capture.findByTool("add_todo");
            assertNotNull(event, "event must be captured");

            // Verify sensitive data in parameters was redacted
            Map<String, Object> params = (Map<String, Object>) event.getParameters();
            // The text contains an email → [REDACTED-EMAIL]
            String capturedText = extractToolArgText(params, "text");
            assertEquals("[REDACTED-EMAIL]", capturedText,
                    "text containing email address must be redacted");

            // Context should not be redacted (no sensitive patterns)
            String capturedContext = extractToolArgText(params, "context");
            assertNotNull(capturedContext);
            assertFalse(capturedContext.contains("[REDACTED"),
                    "context without sensitive data must not be redacted");

            // Protected fields must NOT be redacted
            assertTrue(event.getSessionId().startsWith("ses_"),
                    "sessionId must be preserved");
            assertEquals("test-project", event.getProjectId());
            assertEquals("add_todo", event.getResourceName());

            // Actor info is stamped on the event and left unredacted
            assertEquals("test-user-123", event.getIdentifyActorGivenId());
            assertEquals("John Doe", event.getIdentifyActorName());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integration tests — redactEvent (event-level hook) via McpTestContext
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void applyEventRedaction_appliesSynchronousHookInPlace() throws Exception {
        // ported from: "should apply a synchronous hook in place"
        // Hook replaces params with a scrubbed version
        AgentCatOptions opts = new AgentCatOptions();
        opts.setRedactEvent(eventMap -> {
            Map<String, Object> modified = new LinkedHashMap<>(eventMap);
            modified.put("params", Map.of("text", "[SCRUBBED]"));
            return CompletableFuture.completedFuture(modified);
        });

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().listTools();
            callAddTodo(ctx, "Buy milk");

            capture.flush();
            PublishEventRequest event = capture.findByTool("add_todo");
            assertNotNull(event, "event must be captured (hook returned event, not null)");

            // params must have been replaced by the hook
            Map<String, Object> params = (Map<String, Object>) event.getParameters();
            assertNotNull(params, "parameters must be present");
            assertEquals("[SCRUBBED]", params.get("text"),
                    "params must be replaced by the hook");

            // System-managed fields remain intact even though the hook returned a modified map
            assertTrue(event.getSessionId().startsWith("ses_"));
            assertEquals("test-project", event.getProjectId());
            assertEquals("add_todo", event.getResourceName());
        }
    }

    @Test
    void applyEventRedaction_reportDropAndLeaveEventUntouchedWhenHookReturnsNull() throws Exception {
        // ported from: "should report a drop and leave the event untouched when the hook returns null"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setRedactEvent(eventMap -> CompletableFuture.completedFuture(null)); // drop all events

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().listTools();
            callAddTodo(ctx, "Buy milk");

            // Give the event pipeline time to process
            Thread.sleep(200);

            // Event must have been dropped — EventCapture should have nothing
            List<PublishEventRequest> events = capture.getEvents();
            assertTrue(events.stream().noneMatch(e -> "add_todo".equals(e.getResourceName())),
                    "event must be dropped when hook returns null");
        }
    }

    @Test
    void applyEventRedaction_restoresSystemManagedFieldsIfHookChangesThem() throws Exception {
        // ported from: "should restore system-managed fields if the hook changes or removes them"
        //
        // Protected fields (sessionId, projectId) are restored from the original — the hook
        // cannot override them. This matches TypeScript RESTORED_FIELDS.
        // toolName (resourceName) is NOT protected — the hook can change it, mirroring TypeScript.
        String sessionId = TestUtils.sid("original");
        AtomicBoolean hookCalled = new AtomicBoolean(false);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setRedactEvent(eventMap -> {
            hookCalled.set(true);
            Map<String, Object> modified = new LinkedHashMap<>(eventMap);
            // Attempt to forge protected fields
            modified.put("sessionId",  "ses_forged000000000000000000000");
            modified.put("projectId",  "proj_forged");
            // toolName is mutable — hook can change it (not in RESTORED_FIELDS)
            modified.put("toolName",   "forged_tool");
            // Also modify params
            modified.put("params", Map.of("overridden", true));
            return CompletableFuture.completedFuture(modified);
        });

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().listTools();
            ctx.client().callTool(new McpSchema.CallToolRequest("add_todo",
                    Map.of("text", "test",
                           "context", "testing",
                           "session_id", sessionId)));

            capture.flush();
            // toolName was changed to "forged_tool" by the hook
            PublishEventRequest event = capture.findByTool("forged_tool");
            assertNotNull(event, "event must be captured");
            assertTrue(hookCalled.get(), "hook must have been called");

            // Protected fields must NOT be overrideable by the hook
            assertEquals(sessionId, event.getSessionId(),
                    "sessionId must not be overrideable by redactEvent hook");
            assertEquals("test-project", event.getProjectId(),
                    "projectId must not be overrideable by redactEvent hook");
            // toolName is mutable — hook changed it successfully
            assertEquals("forged_tool", event.getResourceName(),
                    "toolName (resourceName) must be mutable by redactEvent hook");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void redactEventHookIntegration_letsHookInspectMetadataAndReplaceEventFields() throws Exception {
        // ported from: "should let the hook inspect event metadata and replace event fields"
        List<String> seenToolNames = new CopyOnWriteArrayList<>();

        AgentCatOptions opts = new AgentCatOptions();
        opts.setRedactEvent(eventMap -> {
            String toolName = (String) eventMap.get("toolName");
            seenToolNames.add(toolName);
            if ("add_todo".equals(toolName)) {
                Map<String, Object> modified = new LinkedHashMap<>(eventMap);
                modified.put("params", Map.of("replaced", true));
                return CompletableFuture.completedFuture(modified);
            }
            return CompletableFuture.completedFuture(eventMap);
        });

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().listTools();
            callAddTodo(ctx, "Buy milk");

            capture.flush();
            PublishEventRequest event = capture.findByTool("add_todo");
            assertNotNull(event);

            assertTrue(seenToolNames.contains("add_todo"),
                    "hook must have been called with toolName=add_todo");

            // params must have been replaced
            Map<String, Object> params = (Map<String, Object>) event.getParameters();
            assertEquals(Map.of("replaced", true), params,
                    "params must equal the hook-replaced value");

            // System-managed fields remain intact
            assertTrue(event.getSessionId().startsWith("ses_"));
            assertEquals("test-project", event.getProjectId());
            assertNotNull(event.getEventType());
        }
    }

    @Test
    void redactEventHookIntegration_dropsOnlyRejectedEvents() throws Exception {
        // ported from: "should drop only the events the hook rejects, while the others still publish"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setRedactEvent(eventMap -> {
            String toolName = (String) eventMap.get("toolName");
            if ("add_todo".equals(toolName)) {
                return CompletableFuture.completedFuture(null); // drop add_todo events
            }
            return CompletableFuture.completedFuture(eventMap); // keep others
        });

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().listTools();
            callAddTodo(ctx, "Buy milk");
            ctx.client().callTool(new McpSchema.CallToolRequest("list_todos",
                    Map.of("context", "Listing todos")));

            // Wait for the list_todos event to arrive
            capture.flush(1);

            List<PublishEventRequest> events = capture.getEvents();

            // add_todo must be dropped
            assertTrue(events.stream().noneMatch(e -> "add_todo".equals(e.getResourceName())),
                    "add_todo events must be dropped");

            // list_todos must still arrive
            assertEquals(1, events.stream()
                    .filter(e -> "list_todos".equals(e.getResourceName()))
                    .count(),
                    "list_todos event must still be published");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void redactEventHookIntegration_runsHookOnRawValuesBeforeStringRedaction() throws Exception {
        // ported from: "should run the event hook on raw values before string redaction"
        List<String> rawTexts = new CopyOnWriteArrayList<>();

        AgentCatOptions opts = new AgentCatOptions();
        opts.setRedactEvent(eventMap -> {
            // Inspect raw params before string redaction runs
            Map<String, Object> p = extractNestedToolArg(eventMap, "text");
            // p is actually the whole params map here; dig into it
            String text = extractNestedText(eventMap);
            if (text != null) rawTexts.add(text);
            return CompletableFuture.completedFuture(eventMap);
        });
        opts.setRedactSensitiveInformation(text ->
                CompletableFuture.completedFuture(
                        text.contains("secret") ? "[REDACTED-SECRET]" : text));

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().listTools();
            callAddTodo(ctx, "this contains a secret value");

            capture.flush();

            // Event hook saw the raw, unredacted text
            assertTrue(rawTexts.contains("this contains a secret value"),
                    "event hook must see raw (unredacted) text before string redaction runs");

            // String redaction still ran afterwards on the published event
            PublishEventRequest event = capture.findByTool("add_todo");
            assertNotNull(event);
            String publishedText = extractToolArgText(
                    (Map<String, Object>) event.getParameters(), "text");
            assertEquals("[REDACTED-SECRET]", publishedText,
                    "published event must have string-redacted value");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers — extract nested tool argument from event params
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the value of {@code argumentKey} from the deeply nested
     * {@code params -> request -> params -> arguments -> argumentKey} path
     * that EventPublisher serialises tool call arguments into.
     */
    @SuppressWarnings("unchecked")
    private static String extractToolArgText(Map<String, Object> params, String argumentKey) {
        if (params == null) return null;
        try {
            // Attempt: params is the arguments map directly (stripped after sanitise)
            Object direct = params.get(argumentKey);
            if (direct instanceof String s) return s;

            // Attempt: params wraps the original MCP request
            Map<String, Object> request = (Map<String, Object>) params.get("request");
            if (request != null) {
                Map<String, Object> inner = (Map<String, Object>) request.get("params");
                if (inner != null) {
                    Map<String, Object> args = (Map<String, Object>) inner.get("arguments");
                    if (args != null) {
                        Object val = args.get(argumentKey);
                        if (val instanceof String s) return s;
                    }
                }
            }
        } catch (ClassCastException ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String extractNestedText(Map<String, Object> eventMap) {
        try {
            Map<String, Object> params = (Map<String, Object>) eventMap.get("params");
            if (params == null) return null;
            // Try direct
            Object direct = params.get("text");
            if (direct instanceof String s) return s;
            // Try nested request path
            Map<String, Object> request = (Map<String, Object>) params.get("request");
            if (request != null) {
                Map<String, Object> inner = (Map<String, Object>) request.get("params");
                if (inner != null) {
                    Map<String, Object> args = (Map<String, Object>) inner.get("arguments");
                    if (args != null) {
                        Object val = args.get("text");
                        if (val instanceof String s) return s;
                    }
                }
            }
        } catch (ClassCastException ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractNestedToolArg(Map<String, Object> eventMap, String key) {
        try {
            return (Map<String, Object>) eventMap.get("params");
        } catch (ClassCastException ignored) {
            return null;
        }
    }
}
