package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/custom-context-description.test.ts
 *
 * Unit tests validate the customContextDescription option on ParameterInjector.
 * Integration tests use McpTestContext + EventCapture.
 */
class CustomContextDescriptionTest {

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

    /**
     * Builds a server with two tools (echo + summarize) for multi-tool tests.
     */
    private McpTestContext setupTwoToolServer(AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("custom-ctx-desc", "1.0.0")
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
                .toolCall(
                        McpSchema.Tool.builder("summarize",
                                        Map.of("type", "object",
                                               "properties", Map.of("text", Map.of("type", "string"))))
                                .description("Summarize")
                                .build(),
                        (exchange, req) -> McpSchema.CallToolResult.builder()
                                .addTextContent("summary: " + req.arguments().get("text"))
                                .build())
                .build();
        AgentCat.track(server, "proj_test", opts);
        McpTestContext ctx = McpTestContext.start(transport, server);
        ctx.client().listTools(); // prime schema registry
        return ctx;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unit tests via ParameterInjector
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void usesCustomDescriptionInsteadOfDefault() {
        // ported from: "should use custom description instead of default for JSON Schema tools"
        String customDescription = "Explain your reasoning for this action";

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableToolCallContext(true);
        opts.setCustomContextDescription(customDescription);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(Map.of("text", Map.of("type", "string"))));

        McpSchema.Tool tool = McpSchema.Tool.builder("add_todo", schema).build();
        McpSchema.Tool result = ParameterInjector.injectParams(tool, opts);

        Map<String, Object> props = (Map<String, Object>) result.inputSchema().get("properties");
        assertNotNull(props.get("context"));
        assertEquals(customDescription,
                ((Map<String, Object>) props.get("context")).get("description"),
                "Custom description must be used when provided");
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesDefaultDescriptionWhenCustomNotProvided() {
        // ported from: "should use default description when customContextDescription is not provided"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableToolCallContext(true);
        opts.setCustomContextDescription(null);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());

        McpSchema.Tool tool = McpSchema.Tool.builder("add_todo", schema).build();
        McpSchema.Tool result = ParameterInjector.injectParams(tool, opts);

        Map<String, Object> props = (Map<String, Object>) result.inputSchema().get("properties");
        assertNotNull(props.get("context"));
        String desc = (String) ((Map<String, Object>) props.get("context")).get("description");
        assertNotNull(desc, "Default description must be present");
        assertFalse(desc.isEmpty(), "Default description must not be empty");
    }

    @Test
    @SuppressWarnings("unchecked")
    void worksWithLongCustomDescriptions() {
        // ported from: "should use custom description even with long descriptions"
        String customDescription = "Please provide a comprehensive explanation of your reasoning, " +
                "including the broader context of this action within your workflow, the expected " +
                "outcomes, and how this contributes to your overall objectives. Be as detailed as possible.";

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableToolCallContext(true);
        opts.setCustomContextDescription(customDescription);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());

        McpSchema.Tool tool = McpSchema.Tool.builder("add_todo", schema).build();
        McpSchema.Tool result = ParameterInjector.injectParams(tool, opts);

        Map<String, Object> props = (Map<String, Object>) result.inputSchema().get("properties");
        assertEquals(customDescription,
                ((Map<String, Object>) props.get("context")).get("description"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void worksWithSpecialCharactersInDescription() {
        // ported from: "should use custom description with special characters"
        String customDescription = "Why? (explain in detail) - \"Be specific!\"";

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableToolCallContext(true);
        opts.setCustomContextDescription(customDescription);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());

        McpSchema.Tool tool = McpSchema.Tool.builder("add_todo", schema).build();
        McpSchema.Tool result = ParameterInjector.injectParams(tool, opts);

        Map<String, Object> props = (Map<String, Object>) result.inputSchema().get("properties");
        assertEquals(customDescription,
                ((Map<String, Object>) props.get("context")).get("description"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integration tests
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "should apply custom description to all tools when listing"
     *
     * <p>Ported from: src/tests/custom-context-description.test.ts
     */
    @Test
    @SuppressWarnings("unchecked")
    void appliesCustomDescriptionToAllToolsWhenListing() throws Exception {
        String customDescription = "Why are you calling this tool?";

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(true);
        opts.setCustomContextDescription(customDescription);

        try (McpTestContext ctx = setupTwoToolServer(opts)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();

            // Check both user tools have the custom description
            for (String toolName : List.of("echo", "summarize")) {
                McpSchema.Tool tool = tools.stream()
                        .filter(t -> toolName.equals(t.name()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(toolName + " not found"));

                Map<String, Object> props = (Map<String, Object>) tool.inputSchema().get("properties");
                assertNotNull(props.get("context"), toolName + " must have context property");
                Map<String, Object> contextProp = (Map<String, Object>) props.get("context");
                assertEquals(customDescription, contextProp.get("description"),
                        toolName + " must have the custom context description");
            }
        }
    }

    /**
     * "should capture tool calls with custom description configured"
     *
     * <p>Ported from: src/tests/custom-context-description.test.ts
     */
    @Test
    void capturesToolCallsWithCustomDescriptionConfigured() throws Exception {
        String customDescription = "Tell me what you're trying to accomplish";
        String contextString = "I need to add a task to my list";

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(true);
        opts.setEnableTracing(true);
        opts.setCustomContextDescription(customDescription);

        try (McpTestContext ctx = setupTwoToolServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("msg", "test", "context", contextString)));

            capture.flush();
            PublishEventRequest event = capture.findByTool("echo");
            assertNotNull(event, "event must be published");
            assertEquals(contextString, event.getUserIntent(),
                    "context value must be captured as userIntent regardless of description");
        }
    }

    /**
     * "should work end-to-end with custom description and multiple tool calls"
     *
     * <p>Ported from: src/tests/custom-context-description.test.ts
     */
    @Test
    @SuppressWarnings("unchecked")
    void worksEndToEndWithMultipleToolCalls() throws Exception {
        String customDescription = "Explain your intent for this operation";

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(true);
        opts.setEnableTracing(true);
        opts.setCustomContextDescription(customDescription);

        try (McpTestContext ctx = setupTwoToolServer(opts)) {
            // Verify tools list has custom description
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();
            McpSchema.Tool echo = tools.stream()
                    .filter(t -> "echo".equals(t.name()))
                    .findFirst().orElseThrow();
            Map<String, Object> props = (Map<String, Object>) echo.inputSchema().get("properties");
            Map<String, Object> ctxProp = (Map<String, Object>) props.get("context");
            assertEquals(customDescription, ctxProp.get("description"));

            // Make multiple tool calls with different contexts
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("msg", "first", "context", "Setting up my first task")));
            ctx.client().callTool(new McpSchema.CallToolRequest("summarize",
                    Map.of("text", "some text", "context", "Summarizing notes")));
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("msg", "third", "context", "Checking my progress")));

            capture.flush(3);
            List<PublishEventRequest> events = capture.getEvents();
            assertTrue(events.size() >= 3, "all tool calls must produce events");

            // Find events by tool and check userIntent
            PublishEventRequest echoEvent1 = events.stream()
                    .filter(e -> "echo".equals(e.getResourceName()))
                    .findFirst().orElseThrow();
            PublishEventRequest summaryEvent = events.stream()
                    .filter(e -> "summarize".equals(e.getResourceName()))
                    .findFirst().orElseThrow();

            assertEquals("Setting up my first task", echoEvent1.getUserIntent());
            assertEquals("Summarizing notes", summaryEvent.getUserIntent());
        }
    }
}
