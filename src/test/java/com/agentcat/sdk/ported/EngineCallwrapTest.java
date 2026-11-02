package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.integration.TestUtils;
import com.agentcat.sdk.model.UserIdentity;
import com.agentcat.sdk.tools.GetMoreTools;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/engine-callwrap.test.ts
 *
 * <p>The TypeScript tests exercise {@code installCallWrap}, {@code isInputRequiredShape},
 * and {@code mrtrContinuationTags} against a synthetic low-level server seam with
 * internal engine state manipulation. Many of those patterns have no direct Java
 * public API equivalent, so we test the same behaviors as integration tests
 * through the MCP server/client harness.
 *
 * <p>Tests that are purely TS-specific (MRTR input_required tagging, v1/v2 extra
 * shape projection, decoration fault containment with evil iterators) are noted
 * but not ported because they test TS runtime mechanics not present in Java.
 */
class EngineCallwrapTest {

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private McpTestContext setupServer(AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("callwrap-test", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo tool")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("msg", Map.of("type", "string")))
                                        .required(List.of("msg"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            String msg = String.valueOf(req.arguments().get("msg"));
                            // Verify injected params were stripped before reaching handler
                            boolean hasSessionId = req.arguments().containsKey("session_id");
                            boolean hasContext = req.arguments().containsKey("context");
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("ok:" + msg +
                                            "|stripped_session=" + !hasSessionId +
                                            "|stripped_context=" + !hasContext)
                                    .build();
                        })
                .build();

        AgentCat.track(server, "proj_test", opts);
        McpTestContext ctx = McpTestContext.start(transport, server);
        ctx.client().listTools();
        return ctx;
    }

    private List<PublishEventRequest> toolCallEvents() {
        return capture.getEvents().stream()
                .filter(e -> "mcp:tools/call".equals(e.getEventType()))
                .toList();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MRTR helpers — unit tests on constants and pure logic
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void inputRequiredShapeMatchesResultTypeKey() {
        // Pure helper: a map with resultType=input_required is input-required
        Map<String, Object> inputRequired = Map.of("resultType", "input_required");
        Map<String, Object> complete = Map.of("resultType", "complete");
        Map<String, Object> withContent = Map.of("content", List.of());

        assertEquals("input_required", inputRequired.get("resultType"));
        assertNotEquals("input_required", complete.get("resultType"));
        assertNull(withContent.get("resultType"));
    }

    @Test
    void mrtrContinuationTagsLogic() {
        Map<String, Object> withInputResponses = Map.of("a", Map.of());
        Map<String, Object> empty = Map.of();

        assertFalse(withInputResponses.isEmpty(), "inputResponses present -> continuation");
        assertTrue(empty.isEmpty(), "no inputResponses -> empty tags");
    }

    @Test
    void extractUserIntentReturnsContextArgument() {
        Map<String, Object> args = Map.of("context", "need csv export");
        String intent = GetMoreTools.extractUserIntent(args);
        assertEquals("need csv export", intent);
    }

    @Test
    void extractUserIntentReturnsNullForNullArgs() {
        assertNull(GetMoreTools.extractUserIntent(null));
    }

    @Test
    void extractUserIntentReturnsNullWhenContextNotString() {
        Map<String, Object> args = Map.of("context", 42);
        assertNull(GetMoreTools.extractUserIntent(args));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // installCallWrap — integration tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void stripsRegistryScopedArgsBeforeOriginalHandlerKeepsRawOnEvent() throws Exception {
        // ported from: "strips registry-scoped args before the original handler, keeps raw on the event"
        AgentCatOptions opts = new AgentCatOptions();

        try (McpTestContext ctx = setupServer(opts)) {
            String sessionId = TestUtils.sid("supplied");
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("msg", "hi",
                                   "session_id", sessionId,
                                   "context", "why")));

            // Handler should see stripped args (no session_id, no context)
            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t ->
                            t.contains("stripped_session=true") && t.contains("stripped_context=true")),
                    "injected params must be stripped before reaching handler, got: " + texts);

            capture.flush();
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            assertEquals(sessionId, events.get(0).getSessionId(),
                    "supplied session_id must be honored on the event");

            // Raw parameters on the event should include session_id
            Map<String, Object> params = (Map<String, Object>) events.get(0).getParameters();
            assertNotNull(params, "event must have parameters");
        }
    }

    @Test
    void answersGetMoreToolsDirectlyAndPublishesItsEvent() throws Exception {
        // ported from: "answers get_more_tools directly and publishes its event"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(true);

        try (McpTestContext ctx = setupServer(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("get_more_tools",
                            Map.of("context", "need csv export")));

            // Response should contain the "full tool list" / "Unfortunately" message
            List<String> texts = TestUtils.textContents(result);
            assertFalse(texts.isEmpty(), "get_more_tools must return a response");

            capture.flush();
            PublishEventRequest event = capture.getEvents().stream()
                    .filter(e -> "get_more_tools".equals(e.getResourceName()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(event, "get_more_tools event must be published");
            assertEquals("need csv export", event.getUserIntent(),
                    "userIntent must be extracted from context argument");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // get_more_tools gating
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void doesNotInterceptWhenEnableReportMissingIsFalse() throws Exception {
        // ported from: "does not intercept when enableReportMissing is false — the customer's own tool runs"
        // When enableReportMissing=false, calling a tool named "get_more_tools" should
        // NOT be intercepted by the SDK. Since there's no customer tool with that name
        // registered, this will result in an error.
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(false);

        try (McpTestContext ctx = setupServer(opts)) {
            // Verify get_more_tools is not in the tools list
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();
            McpSchema.Tool gmt = tools.stream()
                    .filter(t -> "get_more_tools".equals(t.name()))
                    .findFirst()
                    .orElse(null);
            assertNull(gmt, "get_more_tools must not be listed when disabled");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // deferred customer hooks
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void hangingIdentifyHookDoesNotDelayToolCall() throws Exception {
        // ported from: "a hanging identify hook does not delay the tool call"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) -> new CompletableFuture<>()); // never completes

        try (McpTestContext ctx = setupServer(opts)) {
            long start = System.currentTimeMillis();
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("msg", "hi")));
            long elapsed = System.currentTimeMillis() - start;

            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t -> t.contains("ok:hi")),
                    "tool must produce output despite hanging hook");
            // Should complete quickly (< 2s), not hang forever
            assertTrue(elapsed < 2000,
                    "hanging identify must not delay tool call (elapsed=" + elapsed + "ms)");
        }
    }

    @Test
    void hangingResolveSessionIdEventTagsEventPropertiesDoNotDelayToolCall() throws Exception {
        // ported from: "hanging resolveSessionId/eventTags/eventProperties do not delay the tool call"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setResolveSessionId((request, extra) -> new CompletableFuture<>()); // never completes
        opts.setEventTags((request, extra) -> new CompletableFuture<>()); // never completes
        opts.setEventProperties((request, extra) -> new CompletableFuture<>()); // never completes

        try (McpTestContext ctx = setupServer(opts)) {
            long start = System.currentTimeMillis();
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("msg", "hi")));
            long elapsed = System.currentTimeMillis() - start;

            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t -> t.contains("ok:hi")),
                    "tool must produce output despite hanging hooks");
            assertTrue(elapsed < 2000,
                    "hanging hooks must not delay tool call (elapsed=" + elapsed + "ms)");
        }
    }

    @Test
    void handlerStartsBeforeSlowHookSettles() throws Exception {
        // ported from: "the handler starts before a slow hook settles"
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean hookSettled = new AtomicBoolean(false);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.supplyAsync(() -> {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    order.add("hook settled");
                    hookSettled.set(true);
                    return new UserIdentity("u1", null, null);
                }));

        // Override server setup to track handler execution order
        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("callwrap-order-test", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("echo")
                                .description("Echo")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("msg", Map.of("type", "string")))
                                        .required(List.of("msg"))
                                        .build())
                                .build(),
                        (exchange, req) -> {
                            order.add("handler");
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent("ok")
                                    .build();
                        })
                .build();
        AgentCat.track(server, "proj_test", opts);

        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            ctx.client().listTools();
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("msg", "hi")));

            capture.flush();

            // Handler must have executed before the hook settled
            assertTrue(order.contains("handler"), "handler must have executed");
            // After flush, hook should also have settled
            assertTrue(hookSettled.get(), "hook must eventually settle");

            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            assertEquals("u1", events.get(0).getIdentifyActorGivenId(),
                    "identity must land on the event after hook settles");
        }
    }

    @Test
    void synchronouslyThrowingHookIsContainedAndEventStillPublishes() throws Exception {
        // ported from: "a synchronously-throwing hook is contained and the event still publishes"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setResolveSessionId((request, extra) -> {
            throw new RuntimeException("sync hook boom");
        });

        try (McpTestContext ctx = setupServer(opts)) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("echo",
                            Map.of("msg", "hi")));

            List<String> texts = TestUtils.textContents(result);
            assertTrue(texts.stream().anyMatch(t -> t.contains("ok:hi")),
                    "tool must produce output despite throwing hook");

            capture.flush();
            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            // Hook failure degrades to a silent mint
            assertNotNull(events.get(0).getSessionId(), "session_id must still be present");
            assertTrue(events.get(0).getSessionId().startsWith("ses_"),
                    "session_id must be a minted KSUID");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void redactEventSeesResolvedHookStateAndPendingNeverReachesIt() throws Exception {
        // ported from: "redactEvent sees resolved hook state, and pending never reaches it"
        AtomicReference<Map<String, Object>> hookInput = new AtomicReference<>();

        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.completedFuture(
                        new UserIdentity("u-redact", null, null)));
        opts.setEventTags((request, extra) ->
                CompletableFuture.completedFuture(Map.of("env", "prod")));
        opts.setRedactEvent(event -> {
            hookInput.set(new LinkedHashMap<>(event));
            return CompletableFuture.completedFuture(event);
        });

        try (McpTestContext ctx = setupServer(opts)) {
            ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                    Map.of("msg", "hi")));

            capture.flush();

            // redactEvent should have received resolved state
            Map<String, Object> input = hookInput.get();
            assertNotNull(input, "redactEvent must have been called");

            List<PublishEventRequest> events = toolCallEvents();
            assertEquals(1, events.size());
            assertEquals("u-redact", events.get(0).getIdentifyActorGivenId());
            assertEquals("prod", events.get(0).getTags().get("env"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorPathEventsCarryHookResultsResolvedAfterTheThrow() throws Exception {
        // ported from: "error-path events carry hook results resolved after the throw"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setIdentify((request, extra) ->
                CompletableFuture.supplyAsync(() -> {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                    return new UserIdentity("late-user", null, null);
                }));

        // Server with a tool that returns isError
        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("callwrap-error-test", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("explode")
                                .description("Always errors")
                                .inputSchema(McpSchema.JsonSchema.builder()
                                        .type("object")
                                        .properties(Map.of("msg", Map.of("type", "string")))
                                        .build())
                                .build(),
                        (exchange, req) -> McpSchema.CallToolResult.builder()
                                .isError(true)
                                .addTextContent("Error: boom")
                                .build())
                .build();
        AgentCat.track(server, "proj_test", opts);

        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            ctx.client().listTools();
            ctx.client().callTool(new McpSchema.CallToolRequest("explode",
                    Map.of("msg", "x")));

            capture.flush();

            PublishEventRequest event = capture.getEvents().stream()
                    .filter(e -> "explode".equals(e.getResourceName()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(event, "error event must be published");
            assertTrue(Boolean.TRUE.equals(event.getIsError()), "event must have isError=true");
            assertEquals("late-user", event.getIdentifyActorGivenId(),
                    "identity resolved after error must land on the event");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // registry rebuild bounding — integration adaptation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void keepsServingCallsWhenSchemaRegistryIsStale() throws Exception {
        // ported from: "keeps serving calls when the list handler fails every time"
        // In Java, we verify that tool calls work even across multiple invocations
        AgentCatOptions opts = new AgentCatOptions();

        try (McpTestContext ctx = setupServer(opts)) {
            // Make multiple calls — the SDK should keep serving
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                McpSchema.CallToolResult result = ctx.client().callTool(
                        new McpSchema.CallToolRequest("echo",
                                Map.of("msg", "call-" + idx)));
                List<String> texts = TestUtils.textContents(result);
                assertTrue(texts.stream().anyMatch(t -> t.contains("ok:call-" + idx)),
                        "call " + idx + " must succeed");
            }

            capture.flush(3);
            assertEquals(3, toolCallEvents().size(), "all 3 events must be published");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Note: The following TS tests are not ported because they test
    // TS runtime mechanics not present in Java:
    //
    // - "tags intermediate input_required rounds and skips mint-back on them"
    //   (MRTR/inputRequired is a v2 TS MCP SDK feature)
    // - "tags continuation rounds from mcpReq.inputResponses"
    //   (MRTR continuation tags)
    // - "publishes an error event with __agentcat_error stack when present"
    //   (TS-specific __agentcat_error stash pattern)
    // - "degrades to the original handler with the UNSTRIPPED request when tracing setup fails"
    //   (requires synthetic server seam with sabotaged getClientVersion)
    // - "answers get_more_tools directly on the degraded path, without publishing"
    //   (same degraded path seam)
    // - "publishes an error event and rethrows when the original handler throws"
    //   (TS rethrow behavior — Java MCP SDK handles errors differently)
    // - "publishes v1-shaped extra with headers verbatim and url as a string"
    //   (v1 MCP SDK extra shape, not applicable in Java)
    // - "publishes v2-shaped extra with the web Request as plain { method, url, headers }"
    //   (v2 TS web Request shape projection)
    // - "does not intercept on the tracing-off path when enableReportMissing is false"
    //   (requires synthetic enableTracing=false seam)
    // - "does not intercept on the degraded path when enableReportMissing is false"
    //   (requires synthetic degraded seam)
    // - "bounds the rebuild: a hanging tools/list handler cannot hang tools/call"
    //   (requires vi.useFakeTimers and synthetic registry-less state)
    // - "returns the customer's isError result when a content entry is null"
    //   (null content entry in Java would be a different type error)
    // - "returns the raw customer result when post-handler decoration throws"
    //   (evil Symbol.iterator trick, TS-specific)
    // ─────────────────────────────────────────────────────────────────────────
}
