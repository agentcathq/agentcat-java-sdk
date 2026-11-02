package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/basic-server.test.ts
 *
 * Eight tests matching the TypeScript describe "Basic Server Test" block.
 */
class BasicServerTest {

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

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Builds an untracked server with add_todo + list_todos tools.
     * Corresponds to TypeScript {@code setupTestServerAndClient()}.
     */
    private record PlainSetup(McpTestContext ctx, McpSyncServer server) {}

    private PlainSetup plainServer() throws Exception {
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

        return new PlainSetup(McpTestContext.start(transport, server), server);
    }

    /** Options matching TypeScript {@code { enableToolCallContext: true, enableTracing: true }}. */
    private AgentCatOptions trackingOpts() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableToolCallContext(true);
        opts.setEnableTracing(true);
        return opts;
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    void canCallToolsWithoutTracking() throws Exception {
        // ported from: "should be able to call tools without tracking"
        PlainSetup s = plainServer();
        try (McpTestContext ctx = s.ctx()) {
            McpSchema.ListToolsResult toolsResponse = ctx.client().listTools();
            assertNotNull(toolsResponse.tools(), "tools must be defined");
            assertTrue(toolsResponse.tools().size() > 0, "must have at least one tool");

            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("add_todo", Map.of("text", "Test todo")));
            assertNotNull(result, "result must be defined");
            assertNotNull(result.content(), "content must be defined");
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertTrue(text.contains("Added todo"), "result must contain 'Added todo'");
        }
    }

    @Test
    void canAccessServerInfoFromRegularServerInstance() throws Exception {
        // ported from: "should be able to access server info from regular Server instance"
        PlainSetup s = plainServer();
        try (McpTestContext ctx = s.ctx()) {
            McpSchema.Implementation serverInfo = s.server().getServerInfo();
            assertNotNull(serverInfo, "serverInfo must be defined");
            assertEquals("test server", serverInfo.name());
            assertEquals("1.0", serverInfo.version());
        }
    }

    @Test
    void canAccessServerInfoWithMcpServerIfAvailable() throws Exception {
        // ported from: "should be able to access server info with McpServer if available"
        // Java: McpServer.sync() always supports getServerInfo() — no version check needed
        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer mcpServer = McpServer.sync(transport)
                .serverInfo("test-mcp-server-info", "2.0.0")
                .build();

        McpSchema.Implementation serverInfo = mcpServer.getServerInfo();
        assertNotNull(serverInfo, "serverInfo must be defined");
        assertEquals("test-mcp-server-info", serverInfo.name());
        assertEquals("2.0.0", serverInfo.version());

        mcpServer.closeGracefully();
    }

    @Test
    void serverRemainsFullyFunctionalAfterTrack() throws Exception {
        // ported from: "should verify that server info is used by isCompatibleServerType"
        // Java adaptation: AgentCat.track() is the compatibility-check equivalent —
        // it must return the same server instance and leave it fully functional.
        PlainSetup s = plainServer();
        try (McpTestContext ctx = s.ctx()) {
            McpSyncServer returned = AgentCat.track(s.server(), "proj_test");

            assertSame(s.server(), returned, "track() must return the same server instance");

            McpSchema.Implementation serverInfo = returned.getServerInfo();
            assertNotNull(serverInfo, "serverInfo must be accessible after track()");
            assertEquals("test server", serverInfo.name());

            // Server must still respond to tool calls after track()
            // content[1]: content[0] is the mint-back block, content[1] is the handler response
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("add_todo",
                            Map.of("text", "test after track",
                                   "context", "verifying server still works")));
            assertNotNull(result);
            String toolText = ((McpSchema.TextContent) result.content().get(1)).text();
            assertTrue(toolText.contains("test after track"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void tracesToolAddedAfterTrack() throws Exception {
        // ported from: "should properly trace tools added after track() is called"
        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("test server", "1.0")
                .validateToolInputs(false)
                .build();

        AgentCat.track(server, "test-project", trackingOpts());

        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            // Add new_tool_after_track AFTER track() has been called via server.tool()
            McpSchema.Tool newToolSpec = McpSchema.Tool.builder("new_tool_after_track",
                            Map.of("type", "object",
                                   "properties", Map.of("data", Map.of("type", "string"))))
                    .description("A tool added after track() was called")
                    .build();

            server.getAsyncServer().addTool(
                    new McpServerFeatures.AsyncToolSpecification(newToolSpec,
                            (McpAsyncServerExchange exchange, McpSchema.CallToolRequest req) -> {
                                String data = String.valueOf(
                                        req.arguments().getOrDefault("data", "no data"));
                                return Mono.just(McpSchema.CallToolResult.builder()
                                        .addTextContent("New tool called with data: " + data)
                                        .build());
                            })
            ).toFuture().get();

            // List tools — verify context was injected into the new tool
            McpSchema.ListToolsResult toolsResponse = ctx.client().listTools();
            McpSchema.Tool newTool = toolsResponse.tools().stream()
                    .filter(t -> "new_tool_after_track".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("new_tool_after_track not found"));

            assertNotNull(newTool.inputSchema());
            Map<String, Object> props = (Map<String, Object>) newTool.inputSchema().get("properties");
            assertNotNull(props.get("context"), "context must be injected into schema");
            assertEquals("string", ((Map<?, ?>) props.get("context")).get("type"),
                    "context parameter type must be string");
            assertNotNull(props.get("data"), "original param 'data' must still be present");

            // Call the new tool — content[1] is the handler response (content[0] is mint-back)
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("new_tool_after_track",
                            Map.of("context", "Testing new tool after track",
                                   "data", "test data")));
            assertNotNull(result);
            String toolText = ((McpSchema.TextContent) result.content().get(1)).text();
            assertTrue(toolText.contains("test data"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlesToolWithMinimalSchemaAddedAfterTrack() throws Exception {
        // ported from: "should handle tools with shorthand Zod schema syntax
        //               (reproduces context injection issue)"
        // Java: add a tool with minimal schema (no description, no required) after track() —
        // mirrors TypeScript's shorthand Zod syntax: server.tool("calculator_add", { a: z.number(), b: z.number() }, handler)
        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("test server", "1.0")
                .validateToolInputs(false)
                .build();

        AgentCat.track(server, "test-project", trackingOpts());

        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            McpSchema.Tool calcToolSpec = McpSchema.Tool.builder("calculator_add",
                            Map.of("type", "object",
                                   "properties", Map.of(
                                           "a", Map.of("type", "number"),
                                           "b", Map.of("type", "number"))))
                    .build(); // no description — mirrors shorthand Zod syntax

            server.getAsyncServer().addTool(
                    new McpServerFeatures.AsyncToolSpecification(calcToolSpec,
                            (McpAsyncServerExchange exchange, McpSchema.CallToolRequest req) -> {
                                double a = ((Number) req.arguments().getOrDefault("a", 0)).doubleValue();
                                double b = ((Number) req.arguments().getOrDefault("b", 0)).doubleValue();
                                return Mono.just(McpSchema.CallToolResult.builder()
                                        .addTextContent(String.valueOf((int) (a + b)))
                                        .build());
                            })
            ).toFuture().get();

            // List tools — verify context was injected
            McpSchema.ListToolsResult toolsResponse = ctx.client().listTools();
            McpSchema.Tool calculatorTool = toolsResponse.tools().stream()
                    .filter(t -> "calculator_add".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("calculator_add not found"));

            assertNotNull(calculatorTool.inputSchema());
            Map<String, Object> props = (Map<String, Object>) calculatorTool.inputSchema().get("properties");
            assertNotNull(props.get("context"), "context must be injected");
            assertEquals("string", ((Map<?, ?>) props.get("context")).get("type"),
                    "context parameter type must be string");
            assertNotNull(props.get("a"), "original param 'a' must still be present");
            assertNotNull(props.get("b"), "original param 'b' must still be present");

            // Call the tool — content[1] is the handler response (content[0] is mint-back)
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("calculator_add",
                            Map.of("context", "Testing calculator with shorthand syntax",
                                   "a", 5,
                                   "b", 3)));
            assertNotNull(result);
            assertEquals("8", ((McpSchema.TextContent) result.content().get(1)).text());
        }
    }

    @Test
    void publishesEventsOnlyOnce() throws Exception {
        // ported from: "should publish events only once (no duplicates)"
        PlainSetup s = plainServer();
        try (McpTestContext ctx = s.ctx()) {
            AgentCat.track(s.server(), "test-dedup-project", trackingOpts());

            long ts1 = System.currentTimeMillis();
            long ts2 = ts1 + 1;

            ctx.client().callTool(new McpSchema.CallToolRequest("add_todo",
                    Map.of("context", "test context 1",
                           "text", "First todo with unique ID: " + ts1)));
            ctx.client().callTool(new McpSchema.CallToolRequest("add_todo",
                    Map.of("context", "test context 2",
                           "text", "Second todo with unique ID: " + ts2)));
            ctx.client().callTool(new McpSchema.CallToolRequest("list_todos",
                    Map.of("context", "test context 3")));

            capture.flush(3);

            List<PublishEventRequest> events = capture.getEvents();
            assertTrue(events.size() > 0, "must have captured some events");

            // Check for duplicates using the same composite key as the TypeScript test:
            // eventType + resourceName + parameters
            Set<String> seen = new HashSet<>();
            boolean duplicateFound = false;
            for (PublishEventRequest event : events) {
                String eventId = Objects.toString(event.getEventType()) + "_"
                        + event.getResourceName() + "_"
                        + Objects.toString(event.getParameters());
                if (!seen.add(eventId)) {
                    duplicateFound = true;
                }
            }
            assertFalse(duplicateFound, "no duplicate events must be published");

            // Verify each tool call resulted in exactly one event
            long addTodoCount  = events.stream().filter(e -> "add_todo".equals(e.getResourceName())).count();
            long listTodosCount = events.stream().filter(e -> "list_todos".equals(e.getResourceName())).count();
            assertEquals(2, addTodoCount,   "must have exactly 2 add_todo events");
            assertEquals(1, listTodosCount, "must have exactly 1 list_todos event");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void tracesToolsDefinedBeforeTrack() throws Exception {
        // ported from: "should handle tools defined BEFORE track() with shorthand Zod schema"
        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        // Define tools BEFORE calling track() — exactly like the user's code
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("test calculator server", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("add",
                                        Map.of("type", "object",
                                               "properties", Map.of(
                                                       "a", Map.of("type", "number"),
                                                       "b", Map.of("type", "number"))))
                                .build(),
                        (exchange, req) -> {
                            double a = ((Number) req.arguments().getOrDefault("a", 0)).doubleValue();
                            double b = ((Number) req.arguments().getOrDefault("b", 0)).doubleValue();
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent(String.valueOf((int) (a + b)))
                                    .build();
                        })
                .toolCall(
                        McpSchema.Tool.builder("calculate",
                                        Map.of("type", "object",
                                               "properties", Map.of(
                                                       "operation", Map.of("type", "string",
                                                               "enum", List.of("add", "subtract", "multiply", "divide")),
                                                       "a", Map.of("type", "number"),
                                                       "b", Map.of("type", "number"))))
                                .build(),
                        (exchange, req) -> {
                            String op = String.valueOf(req.arguments().get("operation"));
                            double a  = ((Number) req.arguments().getOrDefault("a", 0)).doubleValue();
                            double b  = ((Number) req.arguments().getOrDefault("b", 0)).doubleValue();
                            double res = switch (op) {
                                case "add"      -> a + b;
                                case "subtract" -> a - b;
                                case "multiply" -> a * b;
                                case "divide"   -> b == 0 ? 0 : a / b;
                                default         -> 0;
                            };
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent(String.valueOf((int) res))
                                    .build();
                        })
                .build();

        // NOW call track — after tools are already defined
        AgentCat.track(server, "test-project", trackingOpts());

        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            McpSchema.ListToolsResult toolsResponse = ctx.client().listTools();

            // Check the "add" tool
            McpSchema.Tool addTool = toolsResponse.tools().stream()
                    .filter(t -> "add".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("add tool not found"));
            assertNotNull(addTool.inputSchema());
            Map<String, Object> addProps = (Map<String, Object>) addTool.inputSchema().get("properties");
            assertNotNull(addProps.get("context"), "context must be injected into add");
            assertEquals("string", ((Map<?, ?>) addProps.get("context")).get("type"));
            assertNotNull(addProps.get("a"), "a must still be present");
            assertNotNull(addProps.get("b"), "b must still be present");

            // Check the "calculate" tool
            McpSchema.Tool calculateTool = toolsResponse.tools().stream()
                    .filter(t -> "calculate".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("calculate tool not found"));
            assertNotNull(calculateTool.inputSchema());
            Map<String, Object> calcProps = (Map<String, Object>) calculateTool.inputSchema().get("properties");
            assertNotNull(calcProps.get("context"),   "context must be injected into calculate");
            assertNotNull(calcProps.get("operation"), "operation must still be present");

            // Call add — content[1] is the handler response (content[0] is mint-back)
            McpSchema.CallToolResult addResult = ctx.client().callTool(
                    new McpSchema.CallToolRequest("add",
                            Map.of("context", "Testing add with context",
                                   "a", 5,
                                   "b", 3)));
            assertNotNull(addResult);
            assertEquals("8", ((McpSchema.TextContent) addResult.content().get(1)).text());

            // Call calculate with multiply — content[1] is the handler response (content[0] is mint-back)
            McpSchema.CallToolResult calcResult = ctx.client().callTool(
                    new McpSchema.CallToolRequest("calculate",
                            Map.of("context", "Testing calculate with context",
                                   "operation", "multiply",
                                   "a", 4,
                                   "b", 7)));
            assertNotNull(calcResult);
            assertEquals("28", ((McpSchema.TextContent) calcResult.content().get(1)).text());
        }
    }
}
