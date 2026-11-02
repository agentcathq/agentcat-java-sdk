package com.agentcat.sdk.e2e;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.TestUtils;
import com.agentcat.sdk.model.UserIdentity;
import com.agentcat.sdk.modules.handles.HandleTags;
import com.agentcat.sdk.modules.handles.MintBackHandler;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * All 22 e2e scenario definitions, ported from the TypeScript
 * {@code v1-matrix.test.ts} scenario array.
 *
 * <h3>Shared fixture constants</h3>
 * These mirror the TypeScript {@code scenario-types.ts} exports and are the single
 * source of truth for values asserted across scenarios.
 */
public final class ScenarioTypes {

    // ── Shared fixture constants ──────────────────────────────────────────────

    public static final String SESSION_HOOK_VALUE       = "corr-7";
    public static final String AGENT_SESSION_HOOK_VALUE = "corr-agent-7";
    public static final String CUSTOM_CONTEXT_DESCRIPTION = "why?";
    public static final String REDACTION_SECRET         = "SECRET_TOKEN";
    public static final String REDACTION_REPLACEMENT    = "[REDACTED]";
    public static final String IDENTITY_USER_ID         = "e2e-user-1";
    public static final String IDENTITY_USER_NAME       = "E2E User";
    public static final String CUSTOMER_TAG_KEY         = "e2e_customer_tag";
    public static final String CUSTOMER_TAG_VALUE       = "customer-tag-present";
    public static final String COLLIDING_TAG_VALUE      = "customer-collision-loses";
    public static final String CUSTOMER_PROP_KEY        = "e2e_customer_prop";
    public static final String CUSTOMER_PROP_VALUE      = "prop-value";

    /** Lane IDs for per-request topologies (stateless). */
    private static final List<String> PER_REQUEST_LANES = List.of("per-request-sync");
    /** Lane IDs for stateful topologies. */
    private static final List<String> STATEFUL_LANES = List.of("stateful-sync");

    private ScenarioTypes() {}

    // ── Scenario list ─────────────────────────────────────────────────────────

    public static final List<E2EScenario> SCENARIOS = List.of(

            // ── 1 ── baseline: injection, mint-back, one event (RAW) + sink (STRIPPED)
            new E2EScenario("baseline-defaults", new AgentCatOptions(), ctx -> {
                McpSchema.ListToolsResult list = ctx.client().listTools(null);
                List<String> names = list.tools().stream().map(McpSchema.Tool::name).toList();
                assertTrue(names.contains("echo"), "echo must be listed");
                assertTrue(names.contains("get_more_tools"), "get_more_tools must be listed");

                McpSchema.Tool echo = toolByName(list, "echo");
                assertNotNull(props(echo).get("context"), "context must be injected");
                assertNotNull(props(echo).get("session_id"), "session_id must be injected");
                assertTrue(required(echo).contains("session_id"), "session_id must be required");
                assertEquals(ParameterInjector.SESSION_ID_PATTERN,
                        prop(echo, "session_id").get("pattern"), "session_id pattern must match");

                McpSchema.CallToolResult result = ctx.client().callTool(
                        new McpSchema.CallToolRequest("echo",
                                Map.of("msg", "hi", "context", "e2e smoke intent")));

                String mintBack = TestUtils.mintBackOf(result);
                assertNotNull(mintBack, "mint-back must be present");
                assertTrue(mintBack.contains("[session_id issued"), "mint-back must contain issued header");
                String sessionId = TestUtils.handleFrom(mintBack, "session_id");
                assertTrue(sessionId.startsWith("ses_"), "session_id must start with ses_");

                List<PublishEventRequest> events = ctx.waitForEvents(1);
                assertEquals(1, events.size());
                PublishEventRequest event = events.get(0);
                assertEquals("echo", event.getResourceName());
                assertEquals(sessionId, event.getSessionId());
                assertEquals("e2e smoke intent", event.getUserIntent());
                assertEquals("minted", event.getTags().get(HandleTags.SESSION_ID_SOURCE_TAG));

                // RAW on event: context survives as sent over the wire
                Map<String, Object> raw = ScenarioContext.rawArgsOf(event);
                assertEquals("e2e smoke intent", raw.get("context"));
                assertEquals("hi", raw.get("msg"));

                // Strip-proof: customer handler saw msg but NOT injected params
                List<ToolSink.Entry> seen = ctx.sink().filter("echo");
                assertEquals(1, seen.size());
                assertEquals("hi", seen.get(0).args().get("msg"));
                assertNull(seen.get(0).args().get("session_id"), "session_id must be stripped");
                assertNull(seen.get(0).args().get("context"), "context must be stripped");
            }),

            // ── 2 ── tracing off: no injection, no mint-back, zero events
            new E2EScenario("tracing-off", opts(o -> o.setEnableTracing(false)), ctx -> {
                McpSchema.ListToolsResult list = ctx.client().listTools(null);
                McpSchema.Tool echo = toolByName(list, "echo");
                assertNull(props(echo).get("session_id"), "session_id must NOT be injected");
                assertNull(props(echo).get("agent_id"), "agent_id must NOT be injected");
                // TS SDK: enableTracing=false skips only handle injection (session_id, agent_id).
                // context is gated independently by enableToolCallContext (default true).
                assertNotNull(props(echo).get("context"), "context must still be injected when only tracing is off");

                McpSchema.CallToolResult result = ctx.client().callTool(
                        new McpSchema.CallToolRequest("echo", Map.of("msg", "hi")));
                assertEquals("hi", TestUtils.textContents(result).get(0));
                assertNull(TestUtils.mintBackOf(result), "no mint-back when tracing off");

                ScenarioContext.settle();
                assertEquals(0, ctx.capture().getEvents().size(), "zero events when tracing off");
                assertEquals(1, ctx.sink().filter("echo").size());
            }),

            // ── 3 ── tracing off × report-missing on: gmt still listed, 0 events
            new E2EScenario("tracing-off-x-report-missing-on",
                    opts(o -> o.setEnableTracing(false)), ctx -> {
                McpSchema.ListToolsResult list = ctx.client().listTools(null);
                assertNotNull(toolByNameOrNull(list, "get_more_tools"),
                        "get_more_tools must be listed even with tracing off");

                McpSchema.CallToolResult result = ctx.client().callTool(
                        new McpSchema.CallToolRequest("get_more_tools",
                                Map.of("context", "need a search tool")));
                String text = result.content().stream()
                        .filter(c -> c instanceof McpSchema.TextContent)
                        .map(c -> ((McpSchema.TextContent) c).text())
                        .findFirst().orElse("");
                assertTrue(text.contains("we have shown you the full tool list"),
                        "gmt response must contain full-tool-list message");
                assertNull(TestUtils.mintBackOf(result), "no mint-back when tracing off");

                ScenarioContext.settle();
                assertEquals(0, ctx.capture().getEvents().size());
            }),

            // ── 4 ── report-missing off: no gmt; handle/context injection unaffected
            new E2EScenario("report-missing-off",
                    opts(o -> o.setEnableReportMissing(false)), ctx -> {
                McpSchema.ListToolsResult list = ctx.client().listTools(null);
                assertNull(toolByNameOrNull(list, "get_more_tools"),
                        "get_more_tools must NOT be listed");
                McpSchema.Tool echo = toolByName(list, "echo");
                assertNotNull(props(echo).get("session_id"), "session_id must still be injected");
                assertNotNull(props(echo).get("context"), "context must still be injected");

                McpSchema.CallToolResult result = ctx.client().callTool(
                        new McpSchema.CallToolRequest("echo",
                                Map.of("msg", "hi", "context", "no gmt")));
                String sessionId = TestUtils.handleFrom(TestUtils.mintBackOf(result), "session_id");
                assertTrue(sessionId.startsWith("ses_"));
                assertEquals(1, ctx.waitForEvents(1).size());
            }),

            // ── 5 ── get_more_tools call: canned text; event with userIntent; wire-only mint-back
            new E2EScenario("report-missing-call", new AgentCatOptions(), ctx -> {
                ctx.client().listTools(null);
                String missing = "Need a database query tool for SQL operations";
                McpSchema.CallToolResult result = ctx.client().callTool(
                        new McpSchema.CallToolRequest("get_more_tools",
                                Map.of("context", missing)));

                // Second content item is the canned "Unfortunately" message
                String gmtText = result.content().stream()
                        .filter(c -> c instanceof McpSchema.TextContent)
                        .map(c -> ((McpSchema.TextContent) c).text())
                        .filter(t -> t.contains("Unfortunately"))
                        .findFirst().orElse(null);
                assertNotNull(gmtText, "gmt response must contain 'Unfortunately'");
                assertTrue(gmtText.contains("we have shown you the full tool list"));

                String mintBack = TestUtils.mintBackOf(result);
                assertNotNull(mintBack);
                String sessionId = TestUtils.handleFrom(mintBack, "session_id");
                assertTrue(sessionId.startsWith("ses_"));

                List<PublishEventRequest> events = ctx.waitForEvents(1);
                assertEquals(1, events.size());
                PublishEventRequest event = events.get(0);
                assertEquals("get_more_tools", event.getResourceName());
                assertEquals(missing, event.getUserIntent());
                assertEquals(sessionId, event.getSessionId());
                // Mint-back is wire-only — never recorded on the published event
                assertFalse(
                        String.valueOf(event.getResponse()).contains("[session_id"),
                        "mint-back must not appear in published response");
            }),

            // ── 6 ── context off: no context param; handles independent
            new E2EScenario("context-off",
                    opts(o -> o.setEnableToolCallContext(false)), ctx -> {
                McpSchema.ListToolsResult list = ctx.client().listTools(null);
                McpSchema.Tool echo = toolByName(list, "echo");
                assertNull(props(echo).get("context"), "context must NOT be injected");
                assertNotNull(props(echo).get("session_id"), "session_id must still be injected");

                ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                        Map.of("msg", "hi", "session_id", TestUtils.sid("ctxoff"))));
                List<PublishEventRequest> events = ctx.waitForEvents(1);
                PublishEventRequest event = events.get(0);
                assertEquals(TestUtils.sid("ctxoff"), event.getSessionId());
                assertEquals("supplied", event.getTags().get(HandleTags.SESSION_ID_SOURCE_TAG));
                assertNull(event.getUserIntent(), "userIntent must be absent when context off");
            }),

            // ── 7 ── custom context description on customer tools; gmt keeps its own
            new E2EScenario("context-custom-description",
                    opts(o -> o.setCustomContextDescription(CUSTOM_CONTEXT_DESCRIPTION)), ctx -> {
                McpSchema.ListToolsResult list = ctx.client().listTools(null);
                McpSchema.Tool echo = toolByName(list, "echo");
                assertEquals(CUSTOM_CONTEXT_DESCRIPTION,
                        prop(echo, "context").get("description"),
                        "custom context description must be used on customer tools");

                // get_more_tools keeps its own bespoke context description
                McpSchema.Tool gmt = toolByName(list, "get_more_tools");
                assertNotEquals(CUSTOM_CONTEXT_DESCRIPTION,
                        prop(gmt, "context").get("description"),
                        "get_more_tools must keep its own description");

                ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                        Map.of("msg", "hi", "context", "custom description intent")));
                List<PublishEventRequest> events = ctx.waitForEvents(1);
                assertEquals("echo", events.get(0).getResourceName());
                assertEquals("custom description intent", events.get(0).getUserIntent());
            }),

            // ── 8 ── agent tracking: agent_id injected, required; structured mirror
            new E2EScenario("agent-tracking-mint",
                    opts(o -> o.setEnableAgentTracking(true)), ctx -> {
                McpSchema.ListToolsResult list = ctx.client().listTools(null);
                McpSchema.Tool structured = toolByName(list, "structured");
                assertNotNull(props(structured).get("agent_id"), "agent_id must be injected");
                assertNotNull(props(structured).get("session_id"), "session_id must be injected");
                assertTrue(required(structured).contains("agent_id"), "agent_id must be required");
                assertTrue(required(structured).contains("session_id"), "session_id must be required");

                McpSchema.CallToolResult result = ctx.client().callTool(
                        new McpSchema.CallToolRequest("structured",
                                Map.of("msg", "hi", "context", "agent minting", "session_id", "start")));
                String mintBack = TestUtils.mintBackOf(result);
                assertNotNull(mintBack);
                assertTrue(mintBack.contains("[session_id issued"));
                assertFalse(mintBack.contains("agent_id"), "agent_id must not appear in text mint-back");

                String sessionId = TestUtils.handleFrom(mintBack, "session_id");
                assertTrue(sessionId.startsWith("ses_"));

                // Structured mirror
                @SuppressWarnings("unchecked")
                Map<String, Object> sc = (Map<String, Object>) result.structuredContent();
                assertNotNull(sc, "structuredContent must be present");
                @SuppressWarnings("unchecked")
                Map<String, Object> mirror = (Map<String, Object>) sc.get(MintBackHandler.MCP_SESSION_KEY);
                assertNotNull(mirror, "mcp_session mirror must be present");
                assertEquals(sessionId, mirror.get("session_id"));
                assertNull(mirror.get("agent_id"), "agent_id must not appear in mirror when not supplied");
                assertEquals("issued", mirror.get("status"));
                assertEquals("hi", sc.get("echoed"));

                List<PublishEventRequest> events = ctx.waitForEvents(1);
                PublishEventRequest event = events.get(0);
                assertEquals(sessionId, event.getSessionId());
                assertEquals("minted", event.getTags().get(HandleTags.SESSION_ID_SOURCE_TAG));
                assertNull(event.getTags().get(HandleTags.AGENT_ID_TAG),
                        "agent_id tag must not appear when not supplied");
            }),

            // ── 9 ── agent tracking: self-chosen agent_id tagged "supplied"
            new E2EScenario("agent-tracking-supplied",
                    opts(o -> o.setEnableAgentTracking(true)), ctx -> {
                ctx.client().listTools(null);
                McpSchema.CallToolResult first = ctx.client().callTool(
                        new McpSchema.CallToolRequest("echo",
                                Map.of("msg", "one", "context", "agent supplied 1",
                                        "session_id", "start")));
                String mintBack = TestUtils.mintBackOf(first);
                assertNotNull(mintBack);
                assertTrue(mintBack.contains("[session_id issued"));
                assertFalse(mintBack.contains("agent_id"));
                String sessionId = TestUtils.handleFrom(mintBack, "session_id");

                McpSchema.CallToolResult second = ctx.client().callTool(
                        new McpSchema.CallToolRequest("echo",
                                Map.of("msg", "two", "context", "agent supplied 2",
                                        "session_id", sessionId,
                                        "agent_id", "opus-4.80-1m|claude-code|k3n9x")));
                assertNull(TestUtils.mintBackOf(second), "no mint-back on second call");

                List<PublishEventRequest> events = ctx.waitForEvents(2);
                assertEquals(2, events.size());
                assertNull(events.get(0).getTags().get(HandleTags.AGENT_ID_TAG),
                        "no agent tag on first call");
                assertEquals(sessionId, events.get(1).getSessionId());
                assertEquals("supplied", events.get(1).getTags().get(HandleTags.SESSION_ID_SOURCE_TAG));
                assertEquals("opus-4.80-1m|claude-code|k3n9x",
                        events.get(1).getTags().get(HandleTags.AGENT_ID_TAG));
                assertEquals("supplied", events.get(1).getTags().get(HandleTags.AGENT_ID_SOURCE_TAG));
            }),

            // ── 10 ── agent tracking × task hook: no session_id param; no text mint-back
            new E2EScenario("agent-tracking-x-hook",
                    opts(o -> {
                        o.setEnableAgentTracking(true);
                        o.setResolveSessionId((req, extra) ->
                                CompletableFuture.completedFuture(AGENT_SESSION_HOOK_VALUE));
                    }), ctx -> {
                McpSchema.ListToolsResult list = ctx.client().listTools(null);
                for (String name : new String[]{"echo", "get_more_tools"}) {
                    McpSchema.Tool tool = toolByName(list, name);
                    assertNull(props(tool).get("session_id"),
                            name + ": session_id must NOT be injected in hook mode");
                    assertNotNull(props(tool).get("agent_id"),
                            name + ": agent_id must be injected");
                    assertTrue(required(tool).contains("agent_id"),
                            name + ": agent_id must be required");
                }

                McpSchema.CallToolResult result = ctx.client().callTool(
                        new McpSchema.CallToolRequest("echo",
                                Map.of("msg", "hi", "context", "hook plus agent",
                                        "agent_id", "opus-4.80-1m|claude-code|k3n9x")));
                assertNull(TestUtils.mintBackOf(result), "no mint-back in hook mode");

                List<PublishEventRequest> events = ctx.waitForEvents(1);
                PublishEventRequest event = events.get(0);
                assertTrue(event.getSessionId().startsWith("ses_"));
                assertEquals("hook", event.getTags().get(HandleTags.SESSION_ID_SOURCE_TAG));
                assertEquals("opus-4.80-1m|claude-code|k3n9x",
                        event.getTags().get(HandleTags.AGENT_ID_TAG));
                assertEquals("supplied", event.getTags().get(HandleTags.AGENT_ID_SOURCE_TAG));
            }),

            // ── 11 ── deterministic hook: same ses_ across calls
            new E2EScenario("task-hook-deterministic",
                    opts(o -> o.setResolveSessionId((req, extra) ->
                            CompletableFuture.completedFuture(SESSION_HOOK_VALUE))), ctx -> {
                ctx.client().listTools(null);
                ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                        Map.of("msg", "one", "context", "hook determinism")));
                ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                        Map.of("msg", "two", "context", "hook determinism")));

                List<PublishEventRequest> events = ctx.waitForEvents(2);
                assertEquals(2, events.size());
                assertTrue(events.get(0).getSessionId().startsWith("ses_"));
                assertEquals(events.get(0).getSessionId(), events.get(1).getSessionId(),
                        "same hook input must yield same ses_ ID across calls");
                for (PublishEventRequest e : events) {
                    assertEquals("hook", e.getTags().get(HandleTags.SESSION_ID_SOURCE_TAG));
                }
            }),

            // ── 12 ── hook null: silent per-call mint, distinct ses_ per call
            new E2EScenario("task-hook-null",
                    opts(o -> o.setResolveSessionId((req, extra) ->
                            CompletableFuture.completedFuture(null))), ctx -> {
                ctx.client().listTools(null);
                McpSchema.CallToolResult first = ctx.client().callTool(
                        new McpSchema.CallToolRequest("echo",
                                Map.of("msg", "one", "context", "hook null")));
                McpSchema.CallToolResult second = ctx.client().callTool(
                        new McpSchema.CallToolRequest("echo",
                                Map.of("msg", "two", "context", "hook null")));
                assertNull(TestUtils.mintBackOf(first), "no mint-back in hook mode");
                assertNull(TestUtils.mintBackOf(second), "no mint-back in hook mode");

                List<PublishEventRequest> events = ctx.waitForEvents(2);
                assertEquals(2, events.size());
                for (PublishEventRequest e : events) {
                    assertTrue(e.getSessionId().startsWith("ses_"));
                    assertEquals("minted", e.getTags().get(HandleTags.SESSION_ID_SOURCE_TAG));
                }
                assertNotEquals(events.get(0).getSessionId(), events.get(1).getSessionId(),
                        "null hook must yield distinct ses_ per call");
            }),

            // ── 13 ── minted → supplied continuity across two calls
            new E2EScenario("task-supplied-continuity", new AgentCatOptions(), ctx -> {
                ctx.client().listTools(null);
                McpSchema.CallToolResult first = ctx.client().callTool(
                        new McpSchema.CallToolRequest("echo",
                                Map.of("msg", "one", "context", "continuity 1",
                                        "session_id", "start")));
                String sessionId = TestUtils.handleFrom(TestUtils.mintBackOf(first), "session_id");
                assertTrue(sessionId.startsWith("ses_"));

                McpSchema.CallToolResult second = ctx.client().callTool(
                        new McpSchema.CallToolRequest("echo",
                                Map.of("msg", "two", "context", "continuity 2",
                                        "session_id", sessionId)));
                assertNull(TestUtils.mintBackOf(second), "no mint-back on subsequent call");

                List<PublishEventRequest> events = ctx.waitForEvents(2);
                assertEquals(2, events.size());
                assertEquals(sessionId, events.get(0).getSessionId());
                assertEquals(sessionId, events.get(1).getSessionId());
                assertEquals("minted", events.get(0).getTags().get(HandleTags.SESSION_ID_SOURCE_TAG));
                assertEquals("supplied", events.get(1).getTags().get(HandleTags.SESSION_ID_SOURCE_TAG));
            }),

            // ── 14 ── structured mirror: text mint-back + mcp_session, schema-valid
            new E2EScenario("mint-back-structured-mirror", new AgentCatOptions(), ctx -> {
                McpSchema.ListToolsResult list = ctx.client().listTools(null);
                McpSchema.Tool listed = toolByName(list, "structured");
                // outputSchema must declare mcp_session after injection
                assertNotNull(listed.outputSchema().get("properties"),
                        "outputSchema must have properties");
                @SuppressWarnings("unchecked")
                Map<String, Object> outProps =
                        (Map<String, Object>) listed.outputSchema().get("properties");
                assertNotNull(outProps.get(MintBackHandler.MCP_SESSION_KEY),
                        "mcp_session must be declared in outputSchema");

                McpSchema.CallToolResult result = ctx.client().callTool(
                        new McpSchema.CallToolRequest("structured",
                                Map.of("msg", "mirrored", "context", "structured mirror",
                                        "session_id", "start")));
                String mintBack = TestUtils.mintBackOf(result);
                assertNotNull(mintBack);
                String sessionId = TestUtils.handleFrom(mintBack, "session_id");

                @SuppressWarnings("unchecked")
                Map<String, Object> sc = (Map<String, Object>) result.structuredContent();
                assertNotNull(sc);
                @SuppressWarnings("unchecked")
                Map<String, Object> mirror = (Map<String, Object>) sc.get(MintBackHandler.MCP_SESSION_KEY);
                assertNotNull(mirror);
                assertEquals(sessionId, mirror.get("session_id"));
                assertEquals("issued", mirror.get("status"));
                assertEquals("mirrored", sc.get("echoed"));
                // Mirror leads structuredContent
                assertEquals(MintBackHandler.MCP_SESSION_KEY,
                        sc.keySet().iterator().next(),
                        "mcp_session must be the first key in structuredContent");

                List<PublishEventRequest> events = ctx.waitForEvents(1);
                assertEquals(sessionId, events.get(0).getSessionId());
                // Mirror is wire-only: not present in the published response
                assertFalse(String.valueOf(events.get(0).getResponse()).contains("mcp_session"),
                        "mcp_session must not appear in published response");
            }),

            // ── 15 ── strict schema: additionalProperties removed; handles ride along
            new E2EScenario("strict-schema-injection", new AgentCatOptions(), ctx -> {
                McpSchema.ListToolsResult list = ctx.client().listTools(null);
                McpSchema.Tool strict = toolByName(list, "strict");
                // additionalProperties:false must be removed by injection
                assertNull(strict.inputSchema().get("additionalProperties"),
                        "additionalProperties must be removed after injection");
                assertNotNull(props(strict).get("session_id"));
                assertNotNull(props(strict).get("context"));

                // Handles riding on the call succeed because AgentCat strips them
                // before the customer's strict parse runs
                McpSchema.CallToolResult result = ctx.client().callTool(
                        new McpSchema.CallToolRequest("strict",
                                Map.of("msg", "strict ok", "context", "strict schema test",
                                        "session_id", TestUtils.sid("strict1"))));
                assertFalse(Boolean.TRUE.equals(result.isError()), "strict call must not be an error");
                assertTrue(TestUtils.textContents(result).stream()
                        .anyMatch(t -> t.contains("strict ok")));

                List<PublishEventRequest> events = ctx.waitForEvents(1);
                assertEquals(TestUtils.sid("strict1"), events.get(0).getSessionId());

                // Sink: handler received only msg (injected params stripped)
                List<ToolSink.Entry> seen = ctx.sink().filter("strict");
                assertEquals(1, seen.size());
                assertEquals(Map.of("msg", "strict ok"), seen.get(0).args());
            }),

            // ── 16 ── strip proof on a loose schema: RAW on event, STRIPPED in sink
            new E2EScenario("strip-proof-loose", new AgentCatOptions(), ctx -> {
                ctx.client().listTools(null);
                ctx.client().callTool(new McpSchema.CallToolRequest("loose",
                        Map.of("msg", "keep",
                                "customer_extra", "customer says hi",
                                "context", "strip proof",
                                "session_id", TestUtils.sid("raw1"))));

                // Sink: customer unknown key survived; injected params did not
                List<ToolSink.Entry> seen = ctx.sink().filter("loose");
                assertEquals(1, seen.size());
                assertEquals("keep", seen.get(0).args().get("msg"));
                assertEquals("customer says hi", seen.get(0).args().get("customer_extra"));
                assertNull(seen.get(0).args().get("session_id"), "session_id must be stripped");
                assertNull(seen.get(0).args().get("context"), "context must be stripped");

                // Event: RAW wire args survive in full
                List<PublishEventRequest> events = ctx.waitForEvents(1);
                Map<String, Object> raw = ScenarioContext.rawArgsOf(events.get(0));
                assertEquals("keep", raw.get("msg"));
                assertEquals("customer says hi", raw.get("customer_extra"));
                assertEquals("strip proof", raw.get("context"));
                assertEquals(TestUtils.sid("raw1"), raw.get("session_id"));
                assertEquals(TestUtils.sid("raw1"), events.get(0).getSessionId());
                assertEquals("strip proof", events.get(0).getUserIntent());
            }),

            // ── 17 ── error path: handler throw propagates as McpError; isError event; instance survives
            // Mirrors TypeScript SDK callWrap.ts: handler exceptions are re-thrown (not swallowed),
            // analytics is recorded with isError=true, and the server keeps serving.
            new E2EScenario("error-path", new AgentCatOptions(), ctx -> {
                ctx.client().listTools(null);

                // Handler throw propagates as McpError to the client — not swallowed into isError result
                assertThrows(McpError.class,
                        () -> ctx.client().callTool(
                                new McpSchema.CallToolRequest("boom",
                                        Map.of("context", "error path intent"))),
                        "boom must propagate as McpError, not be swallowed into isError result");

                List<PublishEventRequest> events = ctx.waitForEvents(1);
                PublishEventRequest errorEvent = events.get(0);
                assertTrue(Boolean.TRUE.equals(errorEvent.getIsError()),
                        "event must carry isError=true");
                assertNotNull(errorEvent.getError(), "error detail must be present");
                assertEquals("error path intent", errorEvent.getUserIntent());
                assertTrue(errorEvent.getSessionId().startsWith("ses_"));
                assertEquals("minted", errorEvent.getTags().get(HandleTags.SESSION_ID_SOURCE_TAG));

                // Same instance keeps serving after a handler throw
                McpSchema.CallToolResult followUp = ctx.client().callTool(
                        new McpSchema.CallToolRequest("echo",
                                Map.of("msg", "still alive", "context", "post-error")));
                assertFalse(Boolean.TRUE.equals(followUp.isError()));
                List<PublishEventRequest> allEvents = ctx.waitForEvents(2);
                assertEquals(2, allEvents.size());
                assertEquals("echo", allEvents.get(1).getResourceName());
                assertFalse(Boolean.TRUE.equals(allEvents.get(1).getIsError()));
            }),

            // ── 18 ── identify + eventTags (collision) + eventProperties
            new E2EScenario("hooks-identity-tags-props",
                    opts(o -> {
                        o.setIdentify((req, extra) -> CompletableFuture.completedFuture(
                                new UserIdentity(IDENTITY_USER_ID, IDENTITY_USER_NAME,
                                        Map.of("plan", "enterprise"))));
                        o.setEventTags((req, extra) -> CompletableFuture.completedFuture(Map.of(
                                // Collision on an SDK-owned tag: SDK's value must win
                                HandleTags.SESSION_ID_SOURCE_TAG, COLLIDING_TAG_VALUE,
                                CUSTOMER_TAG_KEY, CUSTOMER_TAG_VALUE)));
                        o.setEventProperties((req, extra) -> CompletableFuture.completedFuture(
                                Map.of(CUSTOMER_PROP_KEY, CUSTOMER_PROP_VALUE)));
                    }), ctx -> {
                ctx.client().listTools(null);
                ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                        Map.of("msg", "hi", "context", "hooks combo")));

                List<PublishEventRequest> events = ctx.waitForEvents(1);
                PublishEventRequest event = events.get(0);
                assertEquals(IDENTITY_USER_ID, event.getIdentifyActorGivenId());
                assertEquals(IDENTITY_USER_NAME, event.getIdentifyActorName());
                // Customer tag present
                assertEquals(CUSTOMER_TAG_VALUE, event.getTags().get(CUSTOMER_TAG_KEY));
                // SDK's own value wins the collision
                assertEquals("minted", event.getTags().get(HandleTags.SESSION_ID_SOURCE_TAG),
                        "SDK tag value must win over customer collision value");
                // Properties merged from customer callback
                @SuppressWarnings("unchecked")
                Map<String, Object> props = (Map<String, Object>) event.getProperties();
                assertNotNull(props);
                assertEquals(CUSTOMER_PROP_VALUE, props.get(CUSTOMER_PROP_KEY));
            }),

            // ── 19 ── redaction: secret scrubbed from response/args/userIntent
            new E2EScenario("redaction",
                    opts(o -> o.setRedactSensitiveInformation(s ->
                            CompletableFuture.completedFuture(
                                    s.replace(REDACTION_SECRET, REDACTION_REPLACEMENT)))),
                    ctx -> {
                ctx.client().listTools(null);
                ctx.client().callTool(new McpSchema.CallToolRequest("echo",
                        Map.of("msg", "payload with " + REDACTION_SECRET + " inside",
                                "context", "intent mentioning " + REDACTION_SECRET,
                                "session_id", TestUtils.sid("redact"))));

                // Pipeline mutates asynchronously after add(); settle before asserting
                ctx.waitForEvents(1);
                ScenarioContext.settle();

                PublishEventRequest event = ctx.capture().getEvents().get(0);
                String responseText = String.valueOf(event.getResponse());
                assertTrue(responseText.contains(REDACTION_REPLACEMENT),
                        "replacement must appear in response");
                assertFalse(responseText.contains(REDACTION_SECRET),
                        "secret must not appear in response");

                Map<String, Object> raw = ScenarioContext.rawArgsOf(event);
                assertEquals("payload with " + REDACTION_REPLACEMENT + " inside", raw.get("msg"));
                // TS SDK applies redactSensitiveInformation to userIntent too.
                assertEquals("intent mentioning " + REDACTION_REPLACEMENT,
                        event.getUserIntent());

                // Protected fields survive redaction untouched
                assertEquals(TestUtils.sid("redact"), event.getSessionId());
                assertEquals("echo", event.getResourceName());
            }),

            // ── 20 ── session continuity on stateful lane (stateful lanes only)
            new E2EScenario("session-continuity-stateful", new AgentCatOptions(),
                    STATEFUL_LANES, ctx -> {
                ctx.client().listTools(null);
                McpSchema.CallToolResult first = ctx.client().callTool(
                        new McpSchema.CallToolRequest("echo",
                                Map.of("msg", "one", "context", "session continuity 1",
                                        "session_id", "start")));
                String sessionId = TestUtils.handleFrom(TestUtils.mintBackOf(first), "session_id");

                McpSchema.CallToolResult second = ctx.client().callTool(
                        new McpSchema.CallToolRequest("echo",
                                Map.of("msg", "two", "context", "session continuity 2",
                                        "session_id", sessionId)));
                assertNull(TestUtils.mintBackOf(second), "no mint-back on subsequent call");

                List<PublishEventRequest> events = ctx.waitForEvents(2);
                assertEquals(2, events.size());
                assertEquals(sessionId, events.get(0).getSessionId());
                assertEquals(sessionId, events.get(1).getSessionId());
                assertEquals("minted", events.get(0).getTags().get(HandleTags.SESSION_ID_SOURCE_TAG));
                assertEquals("supplied", events.get(1).getTags().get(HandleTags.SESSION_ID_SOURCE_TAG));
            })
    );

    // ── Schema navigation helpers (used inside scenario lambdas) ─────────────

    @SuppressWarnings("unchecked")
    static Map<String, Object> props(McpSchema.Tool tool) {
        Object p = tool.inputSchema().get("properties");
        return p instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> prop(McpSchema.Tool tool, String name) {
        Object p = props(tool).get(name);
        return p instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<String> required(McpSchema.Tool tool) {
        Object r = tool.inputSchema().get("required");
        return r instanceof List<?> l ? (List<String>) l : List.of();
    }

    static McpSchema.Tool toolByName(McpSchema.ListToolsResult list, String name) {
        return list.tools().stream().filter(t -> name.equals(t.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + name));
    }

    static McpSchema.Tool toolByNameOrNull(McpSchema.ListToolsResult list, String name) {
        return list.tools().stream().filter(t -> name.equals(t.name()))
                .findFirst().orElse(null);
    }

    /** Builds an {@link AgentCatOptions} by applying a customiser lambda. */
    static AgentCatOptions opts(java.util.function.Consumer<AgentCatOptions> customiser) {
        AgentCatOptions o = new AgentCatOptions();
        customiser.accept(o);
        return o;
    }
}
