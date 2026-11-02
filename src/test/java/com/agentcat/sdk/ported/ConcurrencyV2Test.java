package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.integration.TestUtils;
import com.agentcat.sdk.model.UserIdentity;
import com.agentcat.sdk.modules.handles.HandleTags;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/v2/concurrency.test.ts
 *
 * Tests that concurrent tool calls on a v2 McpServer do not clobber each other's
 * attribution (session_id, agent_id, actor identity).
 */
class ConcurrencyV2Test {

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

    @Test
    @SuppressWarnings("unchecked")
    void eachEventKeepsItsOwnSessionIdAgentIdAndActorUnderInterleaving() throws Exception {
        // ported from: "each event keeps its own session_id, agent_id, and actor under interleaving"
        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("v2-concurrent", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("slow_echo")
                                .description("echoes slowly")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of(
                                                "text", Map.of("type", "string"),
                                                "delay_ms", Map.of("type", "integer")
                                        ))
                                        .required(List.of("text", "delay_ms"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            String text = String.valueOf(req.arguments().get("text"));
                            Object delayObj = req.arguments().get("delay_ms");
                            int delay = delayObj instanceof Number n ? n.intValue() : 0;
                            try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent(text)
                                    .build();
                        })
                .build();

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        // Identity derives from the request — cross-contamination shows immediately.
        opts.setIdentify((request, extra) -> {
            String text = String.valueOf(request.arguments().get("text"));
            return CompletableFuture.completedFuture(
                    new UserIdentity("actor-" + text, null, null));
        });
        AgentCat.track(server, "proj_test", opts);

        record CallSpec(String text, int delayMs, String sessionId, String agentId) {}
        List<CallSpec> calls = List.of(
                new CallSpec("A", 120, TestUtils.sid("session_A"), "agt_A"),
                new CallSpec("B",  40, TestUtils.sid("session_B"), "agt_B"),
                new CallSpec("C",  80, TestUtils.sid("session_C"), "agt_C")
        );

        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            ctx.client().listTools();

            // Fire all three calls concurrently
            ExecutorService pool = Executors.newFixedThreadPool(3);
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (CallSpec c : calls) {
                futures.add(pool.submit(() -> {
                    try {
                        ctx.client().callTool(new McpSchema.CallToolRequest("slow_echo",
                                Map.of("text", c.text(),
                                       "delay_ms", c.delayMs(),
                                       "context", "concurrency regression test",
                                       "session_id", c.sessionId(),
                                       "agent_id", c.agentId())));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            for (Future<?> f : futures) f.get();
            pool.shutdown();

            capture.flush(3);

            List<PublishEventRequest> events = capture.getEvents().stream()
                    .filter(e -> "mcp:tools/call".equals(e.getEventType()))
                    .toList();
            assertEquals(3, events.size(), "must have exactly 3 tool call events");

            for (CallSpec c : calls) {
                PublishEventRequest event = events.stream()
                        .filter(e -> c.sessionId().equals(e.getSessionId()))
                        .findFirst()
                        .orElse(null);
                assertNotNull(event,
                        "event for session " + c.sessionId() + " (text=" + c.text() + ") must exist");
                assertEquals(c.sessionId(), event.getSessionId(),
                        "session_id must not be clobbered for call " + c.text());
                assertEquals("supplied", event.getTags().get(HandleTags.SESSION_ID_SOURCE_TAG),
                        "session source must be 'supplied' for call " + c.text());
                assertEquals(c.agentId(), event.getTags().get(HandleTags.AGENT_ID_TAG),
                        "agent_id must not be clobbered for call " + c.text());
                assertEquals("actor-" + c.text(), event.getIdentifyActorGivenId(),
                        "identity must not be clobbered for call " + c.text());
            }
        }
    }
}
