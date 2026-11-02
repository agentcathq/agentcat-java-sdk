package com.agentcat.sdk.ported;

import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.McpTestContext;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import com.agentcat.sdk.modules.events.EventQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/v2/schema-edges.test.ts
 *
 * Injection edge cases through real v2 server listing: composed schemas (oneOf,
 * allOf, anyOf), bare/schema-less tools, additionalProperties removal, and
 * outputSchema declaration. Unit tests via ParameterInjector are active;
 * full server-listing integration tests are {@code @Disabled}.
 */
class SchemaEdgesTest {

    @BeforeEach
    void resetQueue() throws InterruptedException {
        EventQueue.getInstance().resetForTesting(10_000);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unit tests — ParameterInjector edge cases
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "skips handle injection entirely on composed (oneOf) input schemas"
     */
    @Test
    @SuppressWarnings("unchecked")
    void skipsInjectionOnComposedOneOfInputSchema() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(true);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("oneOf", List.of(
                Map.of("type", "object", "properties", Map.of("a", Map.of("type", "string"))),
                Map.of("type", "object", "properties", Map.of("b", Map.of("type", "string")))
        ));

        McpSchema.Tool tool = McpSchema.Tool.builder("composed", schema).build();
        McpSchema.Tool result = ParameterInjector.injectParams(tool, opts);

        // Original tool must be returned unchanged for oneOf schemas
        assertSame(tool, result, "Tool with oneOf schema must not be modified");
    }

    /**
     * "removes additionalProperties: false from injected copy"
     */
    @Test
    @SuppressWarnings("unchecked")
    void removesAdditionalPropertiesFalseFromInjectedCopy() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(Map.of("msg", Map.of("type", "string"))));
        schema.put("additionalProperties", false);

        McpSchema.Tool tool = McpSchema.Tool.builder("strict", schema).build();
        McpSchema.Tool result = ParameterInjector.injectParams(tool, opts);

        // The injected copy must not have additionalProperties: false
        assertNotEquals(tool, result, "Strict tool must be modified to allow injected params");
        assertNull(result.inputSchema().get("additionalProperties"),
                "additionalProperties must be removed in injected copy");
    }

    /**
     * "tool already declares session_id — no double injection"
     */
    @Test
    @SuppressWarnings("unchecked")
    void noDoubleInjectionWhenToolAlreadyDeclaresSessionId() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);

        Map<String, Object> existingSessionProp = Map.of("type", "string", "description", "customer-owned");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("session_id", existingSessionProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);

        McpSchema.Tool tool = McpSchema.Tool.builder("owns_session", schema).build();
        McpSchema.Tool result = ParameterInjector.injectParams(tool, opts);

        Map<String, Object> resultProps = (Map<String, Object>) result.inputSchema().get("properties");
        // Customer's session_id must remain — not overwritten
        assertEquals("customer-owned",
                ((Map<String, Object>) resultProps.get("session_id")).get("description"),
                "Customer-owned session_id must not be overwritten");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Integration tests
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "builds a default object schema for a tool listed without one"
     *
     * <p>TypeScript uses a low-level Server that returns a bare tool with no inputSchema.
     * Java equivalent: McpSyncServer with empty-map inputSchema. After {@code AgentCat.track()},
     * listTools must return the tool with a fully-injected {type, properties, required} schema.
     *
     * <p>Ported from: src/tests/v2/schema-edges.test.ts
     */
    @Test
    @SuppressWarnings("unchecked")
    void buildsDefaultObjectSchemaForToolListedWithoutOne() throws Exception {
        // ported from: "builds a default object schema for a tool listed without one"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);

        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("schema-edges-bare", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        // Bare tool: empty inputSchema map (no type / properties / required)
                        McpSchema.Tool.builder("bare", Map.of())
                                .description("no schema")
                                .build(),
                        (exchange, req) -> McpSchema.CallToolResult.builder()
                                .addTextContent("ok")
                                .build())
                .build();
        AgentCat.track(server, "proj_test", opts);

        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();

            McpSchema.Tool bare = tools.stream()
                    .filter(t -> "bare".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("bare not found in listing"));

            Map<String, Object> schema = bare.inputSchema();
            assertNotNull(schema, "injected inputSchema must not be null");
            assertEquals("object", schema.get("type"), "default type must be 'object'");

            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            assertNotNull(props, "properties must be present after injection");
            assertNotNull(props.get("session_id"), "session_id must be injected");
            assertNotNull(props.get("agent_id"),   "agent_id must be injected");
            assertNotNull(props.get("context"),    "context must be injected");

            // session_id carries the validation pattern
            Map<String, Object> sessionIdProp = (Map<String, Object>) props.get("session_id");
            assertEquals(ParameterInjector.SESSION_ID_PATTERN, sessionIdProp.get("pattern"));

            List<String> required = (List<String>) schema.get("required");
            assertNotNull(required);
            assertTrue(required.contains("session_id"), "session_id must be in required");
            assertTrue(required.contains("agent_id"),   "agent_id must be in required");
        }
    }

    /**
     * "declares mcp_session in a listed plain-object outputSchema"
     *
     * <p>A tool that declares an {@code outputSchema} must have {@code mcp_session} injected
     * into its outputSchema's {@code properties} on listing, with sub-properties
     * {@code session_id}, {@code agent_id}, {@code status} (in that order).
     *
     * <p>Ported from: src/tests/v2/schema-edges.test.ts — outputSchema injection section
     */
    @Test
    @SuppressWarnings("unchecked")
    void outputSchemaDeclaredToolGetsAdditionalMcpSessionOnListing() throws Exception {
        // ported from: "declares mcp_session in a listed plain-object outputSchema"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);

        Map<String, Object> outputSchema = new LinkedHashMap<>();
        outputSchema.put("type", "object");
        outputSchema.put("properties", new LinkedHashMap<>(Map.of("count", Map.of("type", "number"))));
        outputSchema.put("required", List.of("count"));
        outputSchema.put("additionalProperties", false);

        var transport = HttpServletStreamableServerTransportProvider.builder().build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("schema-edges-output", "1.0.0")
                .validateToolInputs(false)
                .toolCall(
                        McpSchema.Tool.builder("stats",
                                        Map.of("type", "object", "properties", Map.of()))
                                .description("returns stats")
                                .outputSchema(outputSchema)
                                .build(),
                        (exchange, req) -> McpSchema.CallToolResult.builder()
                                .addTextContent("ok")
                                .build())
                .build();
        AgentCat.track(server, "proj_test", opts);

        try (McpTestContext ctx = McpTestContext.start(transport, server)) {
            List<McpSchema.Tool> tools = ctx.client().listTools().tools();

            McpSchema.Tool stats = tools.stream()
                    .filter(t -> "stats".equals(t.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("stats not found in listing"));

            Map<String, Object> listedOutput = stats.outputSchema();
            assertNotNull(listedOutput, "outputSchema must be present in listing");

            Map<String, Object> outProps = (Map<String, Object>) listedOutput.get("properties");
            assertNotNull(outProps, "outputSchema must have properties");

            Map<String, Object> mcpSessionProp = (Map<String, Object>) outProps.get("mcp_session");
            assertNotNull(mcpSessionProp, "mcp_session must be declared in outputSchema");
            assertEquals("object", mcpSessionProp.get("type"));

            // Sub-properties order: session_id, agent_id, status (mirrors TypeScript)
            Map<String, Object> subProps = (Map<String, Object>) mcpSessionProp.get("properties");
            assertNotNull(subProps);
            assertEquals(List.of("session_id", "agent_id", "status"),
                    List.copyOf(subProps.keySet()),
                    "mcp_session sub-properties must be in order: session_id, agent_id, status");
        }
    }
}
