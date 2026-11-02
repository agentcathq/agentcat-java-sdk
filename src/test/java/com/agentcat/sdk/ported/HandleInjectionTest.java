package com.agentcat.sdk.ported;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.modules.handles.MintBackHandler;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ParameterInjector#addMcpSessionToOutputSchema} —
 * ported from TypeScript {@code handle-injection.test.ts}, {@code describe("outputSchema injection")}.
 *
 * <p>The critical invariant: the sub-properties declared in {@code outputSchema.mcp_session}
 * must exactly mirror what {@link MintBackHandler#buildStructuredMintBack} will put into the
 * actual response. A mismatch causes ajv-strict clients (e.g. the TypeScript MCP SDK) to reject
 * the response as "additional property not allowed".
 *
 * <h3>Mode mapping (TypeScript → Java)</h3>
 * <ul>
 *   <li>{@code injectSessionId=true}  ↔ {@code opts.setResolveSessionId(= null} (default)</li>
 *   <li>{@code injectSessionId=false} ↔ hook mode: {@code opts.resolveSessionId} is set</li>
 *   <li>{@code injectAgentId=true}    ↔ {@code opts.setEnableAgentTracking(true}</li>
 *   <li>{@code injectAgentId=false}   ↔ {@code opts.setEnableAgentTracking(false} (default)</li>
 * </ul>
 */
class HandleInjectionTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Normal mode: session_id injected, agent tracking on. */
    private static AgentCatOptions normalOpts() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true); // injectAgentId=true
        // resolveSessionId=null → injectSessionId=true
        return opts;
    }

    /** Hook mode: resolveSessionId hook set → session_id NOT injected into schema. */
    private static AgentCatOptions hookModeOpts() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(true);
        opts.setResolveSessionId((req, extra) -> CompletableFuture.completedFuture("user-123"));
        return opts;
    }

    /** Session-only mode: session_id injected, agent tracking off. */
    private static AgentCatOptions sessionOnlyOpts() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(false); // injectAgentId=false
        // resolveSessionId=null → injectSessionId=true
        return opts;
    }

    /** Neither session_id nor agent_id injected. */
    private static AgentCatOptions noInjectionOpts() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableAgentTracking(false);
        opts.setResolveSessionId((req, extra) -> CompletableFuture.completedFuture("u"));
        return opts;
    }

    /**
     * Builds a tool that has both an inputSchema and an outputSchema.
     * Mirrors TypeScript {@code structuredTool(name, outputSchema)}.
     */
    private static McpSchema.Tool structuredTool(String name, Map<String, Object> outputSchema) {
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query")
        );
        return McpSchema.Tool.builder(name, inputSchema)
                .outputSchema(outputSchema)
                .build();
    }

    /**
     * Plain object outputSchema with {@code additionalProperties: false} —
     * mirrors TypeScript {@code objectOutputSchema()}.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectOutputSchema() {
        return new java.util.LinkedHashMap<>(Map.of(
                "type", "object",
                "properties", new java.util.LinkedHashMap<>(Map.of(
                        "count", Map.of("type", "number")
                )),
                "required", List.of("count"),
                "additionalProperties", false
        ));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mcpSessionProp(McpSchema.Tool tool) {
        Map<String, Object> schema = tool.outputSchema();
        assertNotNull(schema, "outputSchema must not be null");
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull(props, "outputSchema.properties must not be null");
        return (Map<String, Object>) props.get(MintBackHandler.MCP_SESSION_KEY);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Normal mode (session + agent tracking): mcp_session must declare
     * session_id, agent_id, and status — in that order.
     * Mirrors: "injects mcp_session into a plain object outputSchema and registers the tool"
     */
    @Test
    @SuppressWarnings("unchecked")
    void injectsFullMcpSessionIntoOutputSchema() {
        McpSchema.Tool tool = structuredTool("get_stats", objectOutputSchema());
        McpSchema.Tool result = ParameterInjector.addMcpSessionToOutputSchema(tool, normalOpts());

        Map<String, Object> prop = mcpSessionProp(result);
        assertNotNull(prop, "mcp_session property must be injected");
        assertEquals("object", prop.get("type"));

        Map<String, Object> subProps = (Map<String, Object>) prop.get("properties");
        assertNotNull(subProps);
        assertEquals(
                List.of("session_id", "agent_id", "status"),
                List.copyOf(subProps.keySet()),
                "mcp_session sub-properties must be [session_id, agent_id, status] in that order"
        );

        // status must be an enum
        Map<String, Object> statusProp = (Map<String, Object>) subProps.get("status");
        assertEquals("string", statusProp.get("type"));
        assertEquals(List.of("issued", "active", "unrecognized"), statusProp.get("enum"));

        // customer's original outputSchema contract is untouched
        Map<String, Object> outputSchema = result.outputSchema();
        assertEquals(List.of("count"), outputSchema.get("required"),
                "customer's required array must be preserved");
        assertEquals(false, outputSchema.get("additionalProperties"),
                "additionalProperties: false must be preserved (our property is declared)");
        Map<String, Object> outProps = (Map<String, Object>) outputSchema.get("properties");
        assertEquals(Map.of("type", "number"), outProps.get("count"),
                "customer's count property must be untouched");
    }

    /**
     * Hook mode (resolveSessionId set): session_id and status must be absent from
     * mcp_session sub-properties; only agent_id remains.
     * Mirrors: "sub-properties track modes: hook mode drops session_id and status, tracking off drops agent_id" (first half)
     */
    @Test
    @SuppressWarnings("unchecked")
    void hookModeDropsSessionIdAndStatusFromOutputSchema() {
        McpSchema.Tool tool = structuredTool("t", objectOutputSchema());
        McpSchema.Tool result = ParameterInjector.addMcpSessionToOutputSchema(tool, hookModeOpts());

        Map<String, Object> prop = mcpSessionProp(result);
        assertNotNull(prop, "mcp_session must still be injected in hook mode (agent_id is still echoed)");

        Map<String, Object> subProps = (Map<String, Object>) prop.get("properties");
        assertEquals(
                List.of("agent_id"),
                List.copyOf(subProps.keySet()),
                "hook mode: only agent_id must appear in mcp_session sub-properties"
        );
    }

    /**
     * Agent tracking off: agent_id must be absent; only session_id and status remain.
     * Mirrors: "sub-properties track modes: hook mode drops session_id and status, tracking off drops agent_id" (second half)
     */
    @Test
    @SuppressWarnings("unchecked")
    void agentTrackingOffDropsAgentIdFromOutputSchema() {
        McpSchema.Tool tool = structuredTool("t", objectOutputSchema());
        McpSchema.Tool result = ParameterInjector.addMcpSessionToOutputSchema(tool, sessionOnlyOpts());

        Map<String, Object> prop = mcpSessionProp(result);
        assertNotNull(prop);

        Map<String, Object> subProps = (Map<String, Object>) prop.get("properties");
        assertEquals(
                List.of("session_id", "status"),
                List.copyOf(subProps.keySet()),
                "agent tracking off: mcp_session must contain only [session_id, status]"
        );
    }

    /**
     * The customer's original outputSchema object must not be mutated.
     * Mirrors: "does not mutate the customer's outputSchema object"
     */
    @Test
    @SuppressWarnings("unchecked")
    void doesNotMutateCustomerOutputSchema() {
        Map<String, Object> originalSchema = objectOutputSchema();
        McpSchema.Tool tool = structuredTool("t", originalSchema);

        ParameterInjector.addMcpSessionToOutputSchema(tool, normalOpts());

        Map<String, Object> props = (Map<String, Object>) originalSchema.get("properties");
        assertFalse(props.containsKey(MintBackHandler.MCP_SESSION_KEY),
                "addMcpSessionToOutputSchema must not mutate the customer's outputSchema object");
    }

    /**
     * Complex outputSchema (oneOf/allOf/anyOf): injection skipped, tool returned unchanged.
     * Mirrors: "skips complex outputSchema (oneOf/allOf/anyOf) and does not register"
     */
    @Test
    void skipsComplexOutputSchema() {
        Map<String, Object> complex = Map.of(
                "oneOf", List.of(Map.of("type", "object", "properties", Map.of("a", Map.of("type", "string"))))
        );
        McpSchema.Tool tool = structuredTool("poly", complex);
        McpSchema.Tool result = ParameterInjector.addMcpSessionToOutputSchema(tool, normalOpts());

        assertSame(tool, result, "tool with complex outputSchema must be returned unchanged");
    }

    /**
     * No outputSchema: tool returned unchanged.
     * Mirrors: "no outputSchema: untouched, nothing registered"
     */
    @Test
    void noOutputSchemaIsUntouched() {
        Map<String, Object> inputSchema = Map.of("type", "object", "properties", Map.of());
        McpSchema.Tool tool = McpSchema.Tool.builder("plain", inputSchema).build();

        McpSchema.Tool result = ParameterInjector.addMcpSessionToOutputSchema(tool, normalOpts());

        assertSame(tool, result, "tool without outputSchema must be returned unchanged");
        assertNull(result.outputSchema(), "outputSchema must remain null");
    }

    /**
     * Both inject flags off (hook mode + tracking off): nothing to announce, tool unchanged.
     * Mirrors: "no injection at all (neither handle) leaves outputSchema alone"
     */
    @Test
    @SuppressWarnings("unchecked")
    void noInjectionLeavesOutputSchemaAlone() {
        McpSchema.Tool tool = structuredTool("t", objectOutputSchema());
        McpSchema.Tool result = ParameterInjector.addMcpSessionToOutputSchema(tool, noInjectionOpts());

        Map<String, Object> outProps = (Map<String, Object>) result.outputSchema().get("properties");
        assertFalse(outProps.containsKey(MintBackHandler.MCP_SESSION_KEY),
                "mcp_session must not be injected when neither session_id nor agent_id is tracked");
    }

    /**
     * Collision guard: a customer-declared mcp_session key must never be overwritten.
     * Mirrors: "collision: a customer-declared mcp_session is never clobbered"
     */
    @Test
    @SuppressWarnings("unchecked")
    void collisionGuardNeverClobbersCustomerMcpSession() {
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new java.util.LinkedHashMap<>(Map.of(
                MintBackHandler.MCP_SESSION_KEY, Map.of("type", "string")
        )));
        McpSchema.Tool tool = structuredTool("t", schema);

        McpSchema.Tool result = ParameterInjector.addMcpSessionToOutputSchema(tool, normalOpts());

        Map<String, Object> props = (Map<String, Object>) result.outputSchema().get("properties");
        assertEquals(Map.of("type", "string"), props.get(MintBackHandler.MCP_SESSION_KEY),
                "customer-declared mcp_session must be preserved as-is");
    }
}
