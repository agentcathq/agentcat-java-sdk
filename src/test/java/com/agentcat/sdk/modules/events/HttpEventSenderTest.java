package com.agentcat.sdk.modules.events;

import com.agentcat.api.model.PublishEventRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SDK's own HTTP sender (java.net.http, no OkHttp, no Gson) posts the
 * models-only {@link PublishEventRequest} JSON to the public events endpoint.
 */
class HttpEventSenderTest {

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(202);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = "{\"status\":\"accepted\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void postsJsonBodyToPublicEventsEndpoint() throws Exception {
        PublishEventRequest req = new PublishEventRequest()
                .id("evt_1")
                .projectId("proj_1")
                .sessionId(null)
                .eventType("agentcat:custom")
                .timestamp(OffsetDateTime.of(2026, 11, 2, 13, 48, 55, 569_394_068, ZoneOffset.UTC))
                .parameters(Map.of("x", 1.0));

        new HttpEventSender(baseUrl()).send(req);

        assertEquals("POST", method.get());
        assertEquals("/v1/public/events/", path.get());
        assertTrue(contentType.get().startsWith("application/json"), contentType.get());
        String json = body.get();
        assertTrue(json.contains("\"session_id\":null"), json);
        assertTrue(json.contains("\"event_type\":\"agentcat:custom\""), json);
        assertTrue(json.contains("\"timestamp\":\"2026-11-02T13:48:55.569Z\""), json);
        assertTrue(json.contains("\"parameters\":{\"x\":1}"), json);
    }

    @Test
    void trailingSlashOnBaseUrlIsHarmless() throws Exception {
        new HttpEventSender(baseUrl() + "/").send(new PublishEventRequest().projectId("proj_1"));
        assertEquals("/v1/public/events/", path.get());
    }

    @Test
    void nonSuccessStatusThrowsWithTheStatusCode() {
        status.set(400);
        EventSendException e = assertThrows(EventSendException.class,
                () -> new HttpEventSender(baseUrl()).send(new PublishEventRequest().projectId("proj_1")));
        assertEquals(400, e.getStatusCode());
        assertTrue(e.isClientError());
    }

    @Test
    void connectionFailureThrowsWithoutStatusCode() {
        server.stop(0);
        EventSendException e = assertThrows(EventSendException.class,
                () -> new HttpEventSender(baseUrl()).send(new PublishEventRequest().projectId("proj_1")));
        assertEquals(-1, e.getStatusCode());
        assertFalse(e.isClientError());
    }

    @Test
    void stalledResponseBodyIsCutOffByTheTotalDeadline() throws Exception {
        // Headers arrive at once, then the body never finishes: the deadline must
        // cover the body too, or one bad response holds a worker forever.
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        HttpServer stalling = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stalling.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(202, 0); // chunked body
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        stalling.start();
        try {
            HttpEventSender sender = new HttpEventSender(
                    "http://127.0.0.1:" + stalling.getAddress().getPort(), java.time.Duration.ofMillis(500));
            long start = System.nanoTime();
            EventSendException e = assertThrows(EventSendException.class,
                    () -> sender.send(new PublishEventRequest().projectId("proj_1")));
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertEquals(-1, e.getStatusCode());
            assertTrue(ms < 5_000, "send must give up at the deadline, took " + ms + " ms");
        } finally {
            release.countDown();
            stalling.stop(0);
        }
    }
}
