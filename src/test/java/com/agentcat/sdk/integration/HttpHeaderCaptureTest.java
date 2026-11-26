package com.agentcat.sdk.integration;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP headers over real sockets: the Java twin of the TypeScript SDK's
 * {@code e2e-http/header-capture.test.ts}. A client sends headers on the
 * {@code tools/call} request, and the published event must carry every one of them at
 * {@code parameters.extra.http.req.headers}, for stateless and stateful servers alike.
 */
class HttpHeaderCaptureTest {

    private static final String SENTINEL_HEADER = "x-agentcat-health-run";
    private static final String SENTINEL = "hcrun:header-capture";
    private static final String USER_AGENT = "openai-mcp/1.0.0 (Codex)";
    private static final String CUSTOMER_KEY = "customer-key";

    private final HttpClient http = HttpClient.newHttpClient();
    private EventCapture capture;
    private Server jetty;
    private String baseUrl;

    @BeforeEach
    void startCapture() throws Exception {
        capture = new EventCapture();
        capture.start();
    }

    @AfterEach
    void stop() throws Exception {
        capture.stop();
        if (jetty != null) jetty.stop();
    }

    @Test
    void statelessServerPublishesEveryRequestHeader() throws Exception {
        var transport = HttpServletStatelessServerTransport.builder().build();
        McpStatelessSyncServer server = McpServer.sync(transport)
                .serverInfo("header-capture", "1.0")
                .validateToolInputs(false)
                .toolCall(echoTool(), (ctx, req) -> ok())
                .build();
        AgentCat.track(server, "proj_test");
        serve(transport);

        HttpResponse<String> response = post(CALL_ECHO, null);
        assertEquals(200, response.statusCode(), response.body());

        assertHeadersPublished();
        server.close();
    }

    @Test
    void statefulServerPublishesEveryRequestHeader() throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("header-capture", "1.0")
                .validateToolInputs(false)
                .toolCall(echoTool(), (exchange, req) -> ok())
                .build();
        AgentCat.track(server, "proj_test");
        serve(transport);

        String sessionId = post(INITIALIZE, null).headers().firstValue("mcp-session-id").orElseThrow();
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", sessionId);
        HttpResponse<String> response = post(CALL_ECHO, sessionId);
        assertEquals(200, response.statusCode(), response.body());

        Map<String, Object> headers = assertHeadersPublished();
        assertEquals(sessionId, headers.get("mcp-session-id"));
        assertEquals("Bearer secret-token", headers.get("authorization"));
        server.closeGracefully();
    }

    @Test
    void customerContextExtractorStillReachesTheHandler() throws Exception {
        AtomicReference<Object> seenByHandler = new AtomicReference<>();
        var transport = HttpServletStatelessServerTransport.builder()
                .contextExtractor(request -> McpTransportContext.create(
                        Map.of(CUSTOMER_KEY, request.getHeader(SENTINEL_HEADER))))
                .build();
        McpStatelessSyncServer server = McpServer.sync(transport)
                .serverInfo("header-capture", "1.0")
                .validateToolInputs(false)
                .toolCall(echoTool(), (ctx, req) -> {
                    seenByHandler.set(ctx.get(CUSTOMER_KEY));
                    return ok();
                })
                .build();
        AgentCat.track(server, "proj_test");
        AgentCat.track(server, "proj_test"); // a second track() must not wrap twice
        serve(transport);

        assertEquals(200, post(CALL_ECHO, null).statusCode());

        assertEquals(SENTINEL, seenByHandler.get(), "the customer's own context must be unchanged");
        assertHeadersPublished();
        server.close();
    }

    @Test
    void redactEventCanRemoveAHeader() throws Exception {
        // The hook gets a map it can change, so it can remove a header in place.
        AgentCatOptions opts = new AgentCatOptions()
                .setRedactEvent(event -> {
                    if (event.get("params") instanceof Map<?, ?> params
                            && params.get("extra") instanceof Map<?, ?> extra
                            && extra.get("http") instanceof Map<?, ?> http
                            && http.get("req") instanceof Map<?, ?> req
                            && req.get("headers") instanceof Map<?, ?> headers) {
                        headers.remove("authorization");
                        headers.remove("cookie");
                    }
                    return CompletableFuture.completedFuture(event);
                });
        var transport = HttpServletStatelessServerTransport.builder().build();
        McpStatelessSyncServer server = McpServer.sync(transport)
                .serverInfo("header-capture", "1.0")
                .validateToolInputs(false)
                .toolCall(echoTool(), (ctx, req) -> ok())
                .build();
        AgentCat.track(server, "proj_test", opts);
        serve(transport);

        assertEquals(200, post(CALL_ECHO, null).statusCode());

        Map<String, Object> headers = assertHeadersPublished();
        assertFalse(headers.containsKey("authorization"), "headers=" + headers);
        server.close();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
            + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"header-capture-client\",\"version\":\"1.0\"}}}";

    private static final String CALL_ECHO = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
            + "\"name\":\"echo\",\"arguments\":{\"msg\":\"hi\",\"context\":\"header capture\"}}}";

    @SuppressWarnings("unchecked")
    private Map<String, Object> assertHeadersPublished() {
        capture.flush();
        PublishEventRequest event = capture.findByTool("echo");
        assertNotNull(event, "the echo call must publish an event");

        Map<String, Object> parameters = (Map<String, Object>) event.getParameters();
        Map<String, Object> extra = (Map<String, Object>) parameters.get("extra");
        Map<String, Object> httpInfo = (Map<String, Object>) extra.get("http");
        assertNotNull(httpInfo, "extra.http must be present, extra=" + extra);
        Map<String, Object> req = (Map<String, Object>) httpInfo.get("req");
        Map<String, Object> headers = (Map<String, Object>) req.get("headers");

        assertEquals(SENTINEL, headers.get(SENTINEL_HEADER));
        assertEquals(USER_AGENT, headers.get("user-agent"), "header names must be lower case");
        assertEquals("ClaudeCode", headers.get("x-anthropic-client"));
        assertEquals("application/json", headers.get("content-type"));
        assertTrue(headers.containsKey("host"), "every header is published, headers=" + headers);
        assertEquals("POST", req.get("method"));
        assertTrue(String.valueOf(req.get("url")).endsWith("/mcp?run=1"), "url=" + req.get("url"));
        return headers;
    }

    private void serve(HttpServlet transport) throws Exception {
        int port = TestUtils.findFreePort();
        jetty = new Server(port);
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(new ServletHolder(transport), "/*");
        jetty.setHandler(context);
        jetty.start();
        baseUrl = "http://localhost:" + port + "/mcp?run=1";
    }

    private HttpResponse<String> post(String body, String sessionId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("User-Agent", USER_AGENT)
                .header("X-Anthropic-Client", "ClaudeCode")
                .header("Authorization", "Bearer secret-token")
                .header(SENTINEL_HEADER, SENTINEL)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) request.header("Mcp-Session-Id", sessionId);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static McpSchema.Tool echoTool() {
        return McpSchema.Tool.builder("echo")
                .description("echoes a message")
                .inputSchema(McpSchema.JsonSchema.builder()
                        .type("object")
                        .properties(Map.of("msg", Map.of("type", "string")))
                        .build())
                .build();
    }

    private static McpSchema.CallToolResult ok() {
        return McpSchema.CallToolResult.builder().addTextContent("ok").build();
    }
}
