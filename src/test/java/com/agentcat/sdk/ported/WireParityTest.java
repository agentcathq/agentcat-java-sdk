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

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Event fields that must match the TypeScript SDK on the wire.
 */
class WireParityTest {

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

    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder(name)
                .description(name)
                .inputSchema(McpSchema.JsonSchema.builder().type("object").build())
                .build();
    }

    private void startServer(String projectId) throws Exception {
        startServer(projectId, new AgentCatOptions());
    }

    private void startServer(String projectId, AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        Map<String, Object> withNaN = new HashMap<>();
        withNaN.put("ratio", Double.NaN);
        withNaN.put("missing", null);
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("wire-parity", "1.0")
                .validateToolInputs(false)
                .toolCall(tool("ok"), (ex, req) -> McpSchema.CallToolResult.builder().addTextContent("ok").build())
                .toolCall(tool("boom"), (ex, req) -> {
                    throw new IllegalStateException("boom happened");
                })
                .toolCall(tool("resources"), (ex, req) -> McpSchema.CallToolResult.builder()
                        .addContent(McpSchema.EmbeddedResource.builder(
                                McpSchema.TextResourceContents.builder("file:///notes.txt", "remember the milk")
                                        .mimeType("text/plain").build()).build())
                        .addContent(McpSchema.ResourceLink.builder()
                                .name("report").uri("file:///report.pdf").build())
                        .build())
                .toolCall(tool("nan"), (ex, req) -> McpSchema.CallToolResult.builder()
                        .addTextContent("n").structuredContent(withNaN).build())
                .toolCall(tool("get_more_tools"), (ex, req) ->
                        McpSchema.CallToolResult.builder().addTextContent("mine").build())
                .build();
        AgentCat.track(server, projectId, opts);
        ctx = McpTestContext.start(transport, server);
    }

    private PublishEventRequest call(String toolName) throws Exception {
        ctx.client().callTool(new McpSchema.CallToolRequest(toolName, Map.of()));
        capture.flush();
        PublishEventRequest event = capture.findByTool(toolName);
        assertNotNull(event, "event for " + toolName);
        return event;
    }

    @Test
    void eventIdIsAKsuidWithTheEvtPrefix() throws Exception {
        startServer("proj_test");
        String id = call("ok").getId();
        assertTrue(id.matches("evt_[0-9A-Za-z]{27}"), id);
        // A UUID-derived id is lower-case hex only; a base62 KSUID is not.
        assertFalse(id.matches("evt_[0-9a-f]{27}"), "id looks like UUID hex, not a KSUID: " + id);
    }

    @Test
    void errorCarriesTheFullyQualifiedExceptionClass() throws Exception {
        startServer("proj_test");
        try {
            ctx.client().callTool(new McpSchema.CallToolRequest("boom", Map.of()));
        } catch (Exception expected) {
            // MCP 2.x reports a thrown handler exception to the client as an error
        }
        capture.flush();
        PublishEventRequest event = capture.findByTool("boom");
        assertNotNull(event);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) event.getError();
        assertNotNull(error);
        assertEquals("java.lang.IllegalStateException", error.get("type"));
        assertTrue(String.valueOf(error.get("message")).contains("boom happened"));
    }

    @Test
    void textResourcesAndResourceLinksAreKept() throws Exception {
        startServer("proj_test");
        String json = call("resources").toJson();
        assertTrue(json.contains("remember the milk"), json);
        assertTrue(json.contains("\"type\":\"resource\""), json);
        assertTrue(json.contains("\"type\":\"resource_link\""), json);
        assertFalse(json.contains("unsupported content type"), json);
    }

    @Test
    void nonFiniteNumbersAndNestedNullsSurviveEncoding() throws Exception {
        startServer("proj_test");
        String json = call("nan").toJson();
        assertTrue(json.contains("\"ratio\":null"), json);
        assertTrue(json.contains("\"missing\":null"), json);
    }

    @Test
    void nullProjectIdSendsNoEvent() throws Exception {
        startServer(null);
        ctx.client().callTool(new McpSchema.CallToolRequest("ok", Map.of()));
        Thread.sleep(1_500);
        assertTrue(capture.getEvents().isEmpty(), "TS sends only when projectId is set: " + capture.getEvents());
    }

    @Test
    void customerGetMoreToolsIsNotDuplicated() throws Exception {
        startServer("proj_test");
        List<McpSchema.Tool> tools = ctx.client().listTools().tools();
        assertEquals(1, tools.stream().filter(t -> "get_more_tools".equals(t.name())).count(),
                tools.stream().map(McpSchema.Tool::name).toList().toString());
    }

    private PublishEventRequest callBoom() throws Exception {
        try {
            ctx.client().callTool(new McpSchema.CallToolRequest("boom", Map.of()));
        } catch (Exception expected) {
            // MCP 2.x reports a thrown handler exception to the client as an error
        }
        capture.flush();
        PublishEventRequest event = capture.findByTool("boom");
        assertNotNull(event);
        return event;
    }

    @Test
    void redactEventSeesTheErrorType() throws Exception {
        AtomicReference<Object> seen = new AtomicReference<>();
        startServer("proj_test", new AgentCatOptions().setRedactEvent(event -> {
            seen.set(event.get("error"));
            return CompletableFuture.completedFuture(event);
        }));
        callBoom();
        assertTrue(String.valueOf(seen.get()).contains("java.lang.IllegalStateException"), String.valueOf(seen.get()));
    }

    @Test
    void redactEventCanReplaceTheErrorType() throws Exception {
        startServer("proj_test", new AgentCatOptions().setRedactEvent(event -> {
            event.put("error", Map.of("message", "hidden", "type", "Redacted"));
            return CompletableFuture.completedFuture(event);
        }));
        assertEquals(Map.of("message", "hidden", "type", "Redacted"), callBoom().getError());
    }

    @Test
    void redactEventThatRemovesTheErrorRemovesItsType() throws Exception {
        startServer("proj_test", new AgentCatOptions().setRedactEvent(event -> {
            event.put("error", null);
            return CompletableFuture.completedFuture(event);
        }));
        assertNull(callBoom().getError(), "a hook that clears error must not leave {message:\"\", type}");
    }
}
