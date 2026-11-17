package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.api.model.PublishServerInfoRequest;
import com.agentcat.sdk.AgentCatTestExtension;
import com.agentcat.sdk.modules.events.EventQueue;
import com.agentcat.sdk.modules.events.HttpEventSender;
import com.agentcat.sdk.modules.serverinfo.ServerInfoFixture.Kind;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerInfoSendTest extends HeartbeatTestSupport {

    @Test
    void v13_a413SendsOnceMoreWithoutSchemas() throws Exception {
        recorder.answer(413, 202);

        ServerInfoFixture.tracked(Kind.SYNC).call("alpha");

        List<PublishServerInfoRequest> posts = postsAfterIdle();
        assertEquals(2, posts.size());
        List<Map<String, Object>> full = list(posts.get(0), "tools");
        List<Map<String, Object>> slim = list(posts.get(1), "tools");
        assertTrue(full.stream().anyMatch(tool -> tool.containsKey("inputSchema")));
        assertTrue(full.stream().anyMatch(tool -> tool.containsKey("outputSchema")));
        assertEquals(full.size(), slim.size());
        for (int i = 0; i < full.size(); i++) {
            assertFalse(slim.get(i).containsKey("inputSchema"));
            assertFalse(slim.get(i).containsKey("outputSchema"));
            assertEquals(full.get(i).get("name"), slim.get(i).get("name"));
            assertEquals(full.get(i).get("title"), slim.get(i).get("title"));
            assertEquals(full.get(i).get("description"), slim.get(i).get("description"));
        }
        assertEquals(features(posts.get(0)).get("prompts"), features(posts.get(1)).get("prompts"));
        assertEquals(posts.get(0).getInstructions(), posts.get(1).getInstructions());
    }

    @Test
    void v13_aSecond413EndsTheAttempt() throws Exception {
        recorder.answer(413, 413);

        ServerInfoFixture.tracked(Kind.STATELESS_ASYNC).call("alpha");

        assertEquals(2, postsAfterIdle().size());
    }

    @Test
    void v14_aCustomBaseUrlGetsThePost() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> authorization = new CopyOnWriteArrayList<>();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            authorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            try (InputStream in = exchange.getRequestBody()) {
                bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] answer = "{\"status\":\"accepted\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        api.start();
        String custom = "http://127.0.0.1:" + api.getAddress().getPort() + "/custom";
        try {
            EventQueue.getInstance().configure(custom);

            ServerInfoFixture.tracked(Kind.ASYNC).call("alpha");
            awaitIdle();
        } finally {
            EventQueue.getInstance().configure("http://localhost:1");
            AgentCatTestExtension.installServerInfoSender(recorder);
            api.stop(0);
        }

        assertTrue(paths.contains("/custom/v1/public/server-info/"), "paths: " + paths);
        int index = paths.indexOf("/custom/v1/public/server-info/");
        assertTrue(bodies.get(index).contains("\"server_name\":\"demo-server\""));
        assertEquals("null", authorization.get(index));
    }

    @Test
    void theHttpSenderGivesTheStatusOfAnyAnswer() throws Exception {
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(413, -1);
            exchange.close();
        });
        api.start();
        try {
            HttpEventSender sender = new HttpEventSender("http://127.0.0.1:" + api.getAddress().getPort());
            assertEquals(413, sender.sendServerInfo(new PublishServerInfoRequest().projectId("proj_x")));
        } finally {
            api.stop(0);
        }
    }

    @Test
    void withoutSchemasKeepsEveryOtherList() {
        Map<String, Object> features = Map.of(
                "tools", List.of(Map.of("name", "a", "inputSchema", Map.of())),
                "prompts", List.of(Map.of("name", "p")));

        Map<String, Object> slim = ServerInfoBody.withoutSchemas(features);

        assertEquals(features.get("prompts"), slim.get("prompts"));
        assertNull(((Map<?, ?>) ((List<?>) slim.get("tools")).get(0)).get("inputSchema"));
    }
}
