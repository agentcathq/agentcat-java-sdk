package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.integration.TestUtils;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/context-parameters.test.ts
 *
 * TypeScript {@code addContextParameterToTools(tools)} → Java {@link ParameterInjector#injectParams}
 *
 * Integration tests use McpTestContext + EventCapture.
 */
class ContextParametersTest {

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

    // ── Helper ─────────────────────────────────────────────────────────────────

    private static McpSchema.Tool toolWithSchema(String name, Map<String, Object> inputSchema) {
        return McpSchema.Tool.builder(name, inputSchema).build();
    }

    private static McpSchema.Tool simpleTool(String name) {
        return McpSchema.Tool.builder(name, Map.of("type", "object", "properties", Map.of())).build();
    }

    private McpTestContext setupEchoServer(AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("ctx-params", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("echo",
                                        Map.of("type", "object",
                                               "properties", Map.of("msg", Map.of("type", "string"))))
                                .description("Echo")
                                .build(),
                        (exchange, req) -> McpSchema.CallToolResult.builder()
                                .addTextContent("echo: " + req.arguments().get("msg"))
                                .build())
                .build();
        AgentCat.track(server, "proj_test", opts);
        McpTestContext ctx = McpTestContext.start(transport, server);
        ctx.client().listTools(); // prime schema registry
        return ctx;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // addContextParameterToTools — unit tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void addsContextParameterToToolsWithExistingInputSchema() {
        // ported from: "should add context parameter to tools with existing inputSchema"
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("text", Map.of("type", "string", "description", "Some text"));
        inputSchema.put("properties", properties);
        inputSchema.put("required", new java.util.ArrayList<>(List.of("text")));

        McpSchema.Tool tool = toolWithSchema("existing_tool", inputSchema);
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(true);
        opts.setEnableTracing(true);
        McpSchema.Tool result = ParameterInjector.injectParams(tool, opts);

        Map<String, Object> props = (Map<String, Object>) result.inputSchema().get("properties");
        assertNotNull(props.get("text"), "Original text property must survive");
        assertNotNull(props.get("context"), "context property must be injected");
        assertEquals("string", ((Map<String, Object>) props.get("context")).get("type"));

        List<String> required = (List<String>) result.inputSchema().get("required");
        assertTrue(required.contains("text"));
        assertTrue(required.contains("context"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotDuplicateContextParameterIfAlreadyExists() {
        // ported from: "should not duplicate context parameter if already exists"
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("context", Map.of("type", "string", "description", "Existing context"));
        inputSchema.put("properties", properties);
        inputSchema.put("required", new java.util.ArrayList<>(List.of("context")));

        McpSchema.Tool tool = toolWithSchema("tool_with_context", inputSchema);
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(true);
        opts.setEnableTracing(true);
        McpSchema.Tool result = ParameterInjector.injectParams(tool, opts);

        Map<String, Object> props = (Map<String, Object>) result.inputSchema().get("properties");
        Map<String, Object> contextProp = (Map<String, Object>) props.get("context");
        assertEquals("Existing context", contextProp.get("description"),
                "Existing context description must be preserved");

        List<String> required = (List<String>) result.inputSchema().get("required");
        long count = required.stream().filter("context"::equals).count();
        assertEquals(1, count, "context must not be duplicated in required");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addsContextParameterToToolsWithEmptyRequiredArray() {
        // ported from: "should handle tools with empty required array"
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("optional", Map.of("type", "string"));
        inputSchema.put("properties", properties);
        inputSchema.put("required", new java.util.ArrayList<>());

        McpSchema.Tool tool = toolWithSchema("optional_tool", inputSchema);
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(true);
        opts.setEnableTracing(true);
        McpSchema.Tool result = ParameterInjector.injectParams(tool, opts);

        List<String> required = (List<String>) result.inputSchema().get("required");
        assertTrue(required.contains("context"));
        // Java: injectParams is a single function that injects all handles at once.
        // TypeScript had separate addContextParameterToTools (only context) and
        // addHandleParametersToTools (session_id + agent_id). In Java, both run together,
        // so session_id is also added to required when resolveSessionId is not set.
        assertEquals(2, required.size()); // context + session_id
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integration tests
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "should capture context parameter when tools are called after tracking"
     *
     * <p>Ported from: src/tests/context-parameters.test.ts
     */
    @Test
    void capturesContextParameterWhenToolsCalledAfterTracking() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableToolCallContext(true);

        String contextString = "Testing context parameter injection for analytics";

        try (McpTestContext ctx = setupEchoServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("msg", "test", "context", contextString)));

            capture.flush();
            PublishEventRequest event = capture.findByTool("echo");
            assertNotNull(event, "event must be published");
            assertEquals(contextString, event.getUserIntent(),
                    "context argument must be captured as userIntent");
        }
    }

    /**
     * "should work with tools that have context parameter"
     *
     * <p>Ported from: src/tests/context-parameters.test.ts
     */
    @Test
    void worksWithToolsThatHaveContextParameter() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(true);

        String completionContext = "Testing completion with context tracking";

        try (McpTestContext ctx = setupEchoServer(opts)) {
            // First call with context
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("msg", "first", "context", "Creating initial data")));

            // Second call with different context
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("msg", "second", "context", completionContext)));

            capture.flush(2);
            List<PublishEventRequest> events = capture.getEvents();
            assertTrue(events.size() >= 2, "both tool calls must produce events");

            PublishEventRequest lastEvent = events.get(1);
            assertEquals(completionContext, lastEvent.getUserIntent(),
                    "context from second call must be captured as userIntent");
        }
    }

    /**
     * "should capture userIntent only when context is provided"
     *
     * <p>Ported from: src/tests/context-parameters.test.ts
     */
    @Test
    void capturesUserIntentOnlyWhenContextProvided() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(true);
        String sessionId = TestUtils.sid("ctxtest");

        try (McpTestContext ctx = setupEchoServer(opts)) {
            // Call without context — tool still works, userIntent null
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("msg", "no context", "session_id", sessionId)));

            // Call with context
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("msg", "with context",
                           "context", "Listing todos to check current status",
                           "session_id", sessionId)));

            capture.flush(2);
            List<PublishEventRequest> events = capture.getEvents();
            assertTrue(events.size() >= 2);

            // First call: no userIntent
            PublishEventRequest noCtxEvent = events.get(0);
            assertNull(noCtxEvent.getUserIntent(),
                    "userIntent must be null when no context is provided");

            // Second call: userIntent set
            PublishEventRequest withCtxEvent = events.get(1);
            assertEquals("Listing todos to check current status", withCtxEvent.getUserIntent(),
                    "userIntent must equal the context argument");
        }
    }

    /**
     * "should inject context into tool schemas when listing tools"
     *
     * <p>Ported from: src/tests/context-parameters.test.ts
     */
    @Test
    @SuppressWarnings("unchecked")
    void injectsContextIntoToolSchemasWhenListingTools() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(true);

        try (McpTestContext ctx = setupEchoServer(opts)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();

            McpSchema.Tool echo = tools.stream()
                    .filter(t -> "echo".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("echo not found"));

            Map<String, Object> props = (Map<String, Object>) echo.inputSchema().get("properties");
            assertNotNull(props.get("context"),
                    "context must be injected into the tool schema");
            Map<String, Object> contextProp = (Map<String, Object>) props.get("context");
            assertEquals("string", contextProp.get("type"));
            assertNotNull(contextProp.get("description"),
                    "context must have a description");
        }
    }

    /**
     * "should use default context description when no custom description is provided"
     *
     * <p>Ported from: src/tests/context-parameters.test.ts
     */
    @Test
    @SuppressWarnings("unchecked")
    void usesDefaultContextDescriptionWhenNoCustomDescriptionProvided() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(true);
        // No customContextDescription set — default must be used

        try (McpTestContext ctx = setupEchoServer(opts)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();

            McpSchema.Tool echo = tools.stream()
                    .filter(t -> "echo".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("echo not found"));

            Map<String, Object> props = (Map<String, Object>) echo.inputSchema().get("properties");
            Map<String, Object> contextProp = (Map<String, Object>) props.get("context");
            assertNotNull(contextProp);

            String desc = (String) contextProp.get("description");
            assertNotNull(desc, "Default description must be present");
            assertFalse(desc.isEmpty(), "Default description must not be empty");
            // Default description explains the 15-25 word policy
            assertTrue(desc.contains("15-25 words"),
                    "Default description must mention the 15-25 word requirement");
        }
    }

    /**
     * "should remove context parameter before calling tool callback"
     *
     * <p>The SDK injects context into the schema but strips it before calling
     * the user's handler — the handler never sees {@code context} in its arguments.
     *
     * <p>Ported from: src/tests/context-parameters.test.ts
     */
    @Test
    void removesContextParameterBeforeCallingToolCallback() throws Exception {
        AtomicReference<Map<String, Object>> capturedArgs = new AtomicReference<>();

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableToolCallContext(true);

        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("ctx-strip", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("inspect",
                                        Map.of("type", "object",
                                               "properties", Map.of(
                                                       "testParam", Map.of("type", "string"))))
                                .description("Inspect callback args")
                                .build(),
                        (exchange, req) -> {
                            capturedArgs.set(new LinkedHashMap<>(req.arguments()));
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("captured")
                                    .build();
                        })
                .build();
        AgentCat.track(server, "proj_test", opts);

        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            ctx.client().listTools();

            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("inspect",
                            Map.of("testParam", "test-value",
                                   "context", "This context should be removed before callback")));

            // Tool call must succeed
            assertNotNull(result);
            assertFalse(Boolean.TRUE.equals(result.isError()), "tool call must not be an error");

            // Verify that the handler received testParam but NOT context
            Map<String, Object> args = capturedArgs.get();
            assertNotNull(args, "handler must have been called");
            assertEquals("test-value", args.get("testParam"),
                    "testParam must be passed to the handler");
            assertFalse(args.containsKey("context"),
                    "context must be stripped before the handler is called, but was present in: " + args);
        }
    }
}
