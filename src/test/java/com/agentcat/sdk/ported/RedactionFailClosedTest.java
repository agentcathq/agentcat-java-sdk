package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redaction never lets raw data through. A redaction hook that does not finish in
 * time drops the event (fail closed), as a hook that throws already does; and
 * redaction reaches every string of the response, also inside object-typed
 * {@code structuredContent}.
 */
class RedactionFailClosedTest {

    /** A Java object as structuredContent, as MCP 2.x allows. */
    public record Customer(String email, String plan) {}

    private EventCapture capture;
    private McpTestContext ctx;

    @BeforeEach
    void startCapture() throws Exception {
        capture = new EventCapture();
        capture.start();
    }

    @AfterEach
    void stop() throws Exception {
        if (ctx != null) ctx.close();
        capture.stop();
    }

    private void startServer(AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("redaction-fail-closed", "1.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("echo")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("text", Map.of("type", "string")))
                                        .required(List.of("text"))
                                        .build())
                                .build(),
                        (exchange, req) -> McpSchema.CallToolResult.builder()
                                .addTextContent("secret " + req.arguments().get("text"))
                                .build())
                .toolCall(
                        McpSchema.Tool.builder("customer")
                                .description("customer")
                                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                                .build(),
                        (exchange, req) -> McpSchema.CallToolResult.builder()
                                .addTextContent("ok")
                                .structuredContent(new Customer("jane@example.com", "pro"))
                                .build())
                .build();
        AgentCat.track(server, "proj_test", opts);
        ctx = McpTestContext.start(transport, server);
    }

    /** Waits longer than the hook timeout, then makes sure that no event went out. */
    private void assertNoEventWithin(long ms) throws InterruptedException {
        Thread.sleep(ms);
        assertTrue(capture.getEvents().isEmpty(), "event must be dropped, got " + capture.getEvents());
    }

    @Test
    void redactSensitiveInformationTimeoutDropsTheEvent() throws Exception {
        startServer(new AgentCatOptions().setRedactSensitiveInformation(text -> new CompletableFuture<>()));

        ctx.client().callTool(new McpSchema.CallToolRequest("echo", Map.of("text", "jane@example.com")));

        // Longer than the params and response stages together (2 x 5 s), so a fallback
        // to the original strings would have published by now.
        assertNoEventWithin(12_000);
    }

    @Test
    void redactEventTimeoutDropsTheEvent() throws Exception {
        startServer(new AgentCatOptions().setRedactEvent(event -> new CompletableFuture<>()));

        ctx.client().callTool(new McpSchema.CallToolRequest("echo", Map.of("text", "jane@example.com")));

        assertNoEventWithin(7_000);
    }

    @Test
    void objectStructuredContentIsRedacted() throws Exception {
        startServer(new AgentCatOptions().setRedactSensitiveInformation(text ->
                CompletableFuture.completedFuture(text.replace("jane@example.com", "[EMAIL]"))));

        ctx.client().callTool(new McpSchema.CallToolRequest("customer", Map.of()));
        capture.flush();

        PublishEventRequest event = capture.findByTool("customer");
        assertNotNull(event);
        String json = event.toJson();
        assertFalse(json.contains("jane@example.com"), json);
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) event.getResponse();
        assertEquals(Map.of("email", "[EMAIL]", "plan", "pro"), response.get("structuredContent"));
    }
}
