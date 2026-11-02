package com.agentcat.sdk.ported;

import com.agentcat.api.model.PublishEventRequest;
import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.integration.TestUtils;
import com.agentcat.sdk.modules.handles.HandleTags;
import com.agentcat.sdk.tools.GetMoreTools;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/v2/get-more-tools.test.ts
 *
 * Tests for the {@code get_more_tools} tool on a tracked v2 McpServer.
 * Unit tests for schema and annotation constants are active;
 * integration tests use McpTestContext + EventCapture.
 */
class GetMoreToolsPortedTest {

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

    private McpTestContext setupEchoServer(AgentCatOptions opts) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("v2-gmt", "1.0.0")
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
                .build();
        AgentCat.track(server, "proj_test", opts);
        McpTestContext ctx = McpTestContext.start(transport, server);
        ctx.client().listTools(); // prime schema registry
        return ctx;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unit tests — schema, annotations, and extractUserIntent
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getMoreToolsDescriptionContainsCheckForAdditionalTools() {
        // ported from: "advertises the tool with required bespoke context, handles, and honest annotations"
        var spec = GetMoreTools.buildTool();
        assertNotNull(spec);
        assertNotNull(spec.tool());
        assertEquals("get_more_tools", spec.tool().name());
        assertTrue(spec.tool().description().contains("Check for additional tools"),
                "get_more_tools description must mention 'Check for additional tools'");
    }

    @Test
    void getMoreToolsContextSchemaRequiresContextField() {
        @SuppressWarnings("unchecked")
        java.util.List<String> required = (java.util.List<String>) GetMoreTools.CONTEXT_INPUT_SCHEMA.get("required");
        assertNotNull(required);
        assertTrue(required.contains("context"));
    }

    @Test
    void extractUserIntentFromContextArg() {
        String intent = GetMoreTools.extractUserIntent(Map.of("context", "need csv export"));
        assertEquals("need csv export", intent);
    }

    @Test
    void extractUserIntentNullWhenMissing() {
        assertNull(GetMoreTools.extractUserIntent(Map.of()));
        assertNull(GetMoreTools.extractUserIntent(null));
    }

    @Test
    void annotationsAreCorrectlySet() {
        var ann = GetMoreTools.buildAnnotations();
        if (ann == null) return; // older SDK without ToolAnnotations
        assertEquals("Get More Tools", ann.title());
        assertTrue(Boolean.TRUE.equals(ann.readOnlyHint()));
        assertTrue(Boolean.FALSE.equals(ann.destructiveHint()));
        assertTrue(Boolean.TRUE.equals(ann.idempotentHint()));
        assertTrue(Boolean.FALSE.equals(ann.openWorldHint()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integration tests
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "advertises the tool with required bespoke context, handles, and honest annotations"
     *
     * <p>Ported from: src/tests/v2/get-more-tools.test.ts
     */
    @Test
    @SuppressWarnings("unchecked")
    void advertisesToolWithRequiredContextHandlesAndAnnotations() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);

        try (McpTestContext ctx = setupEchoServer(opts)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();

            McpSchema.Tool gmt = tools.stream()
                    .filter(t -> "get_more_tools".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("get_more_tools not found in listing"));

            assertNotNull(gmt);
            assertTrue(gmt.description().contains("Check for additional tools"),
                    "description must contain 'Check for additional tools'");

            Map<String, Object> props = (Map<String, Object>) gmt.inputSchema().get("properties");
            assertNotNull(props.get("context"), "get_more_tools must have context property");
            assertNotNull(props.get("session_id"), "get_more_tools must have session_id property");
            assertNotNull(props.get("agent_id"), "get_more_tools must have agent_id property");

            List<String> required = (List<String>) gmt.inputSchema().get("required");
            assertNotNull(required);
            assertTrue(required.contains("context"), "context must be required");
            assertTrue(required.contains("session_id"), "session_id must be required");
            assertTrue(required.contains("agent_id"), "agent_id must be required");

            // Check annotations
            McpSchema.ToolAnnotations ann = gmt.annotations();
            if (ann != null) {
                assertEquals("Get More Tools", ann.title());
                assertTrue(Boolean.TRUE.equals(ann.readOnlyHint()));
                assertTrue(Boolean.FALSE.equals(ann.destructiveHint()));
                assertTrue(Boolean.TRUE.equals(ann.idempotentHint()));
                assertTrue(Boolean.FALSE.equals(ann.openWorldHint()));
            }
        }
    }

    /**
     * "answers with the canned text and publishes an event carrying userIntent"
     *
     * <p>Ported from: src/tests/v2/get-more-tools.test.ts
     */
    @Test
    void returnsAcknowledgementResponseAndPublishesEventWithUserIntent() throws Exception {
        String missingDescription = "Need a database query tool for SQL operations";

        try (McpTestContext ctx = setupEchoServer(new AgentCatOptions())) {
            McpSchema.CallToolResult result = ctx.client().callTool(
                    new McpSchema.CallToolRequest("get_more_tools",
                            Map.of("context", missingDescription)));

            assertNotNull(result);
            List<String> text = TestUtils.textContents(result);
            assertTrue(text.stream().anyMatch(t -> t.contains("we have shown you the full tool list")),
                    "get_more_tools must return the canned acknowledgement");

            capture.flush();
            PublishEventRequest event = capture.findByTool("get_more_tools");
            assertNotNull(event, "event must be published for get_more_tools call");
            assertEquals("get_more_tools", event.getResourceName());
            assertEquals(missingDescription, event.getUserIntent(),
                    "userIntent must be the context argument");
            assertTrue(event.getSessionId() != null && event.getSessionId().startsWith("ses_"),
                    "event must have a ses_ session ID");
        }
    }

    /**
     * "is excluded when enableReportMissing is false"
     *
     * <p>Ported from: src/tests/v2/get-more-tools.test.ts
     */
    @Test
    void isExcludedWhenEnableReportMissingIsFalse() throws Exception {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableReportMissing(false);

        try (McpTestContext ctx = setupEchoServer(opts)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();

            assertNull(tools.stream()
                            .filter(t -> "get_more_tools".equals(t.name()))
                            .findFirst().orElse(null),
                    "get_more_tools must not be listed when enableReportMissing is false");
        }
    }

    /**
     * "captures context as userIntent on event"
     *
     * <p>Ported from: src/tests/v2/get-more-tools.test.ts
     */
    @Test
    void capturesContextAsUserIntentOnEvent() throws Exception {
        String intent = "need csv export";

        try (McpTestContext ctx = setupEchoServer(new AgentCatOptions())) {
            ctx.client().callTool(new McpSchema.CallToolRequest("get_more_tools",
                    Map.of("context", intent)));

            capture.flush();
            PublishEventRequest event = capture.findByTool("get_more_tools");
            assertNotNull(event);
            assertEquals(intent, event.getUserIntent(),
                    "context argument must be captured as userIntent");
        }
    }
}
