package com.agentcat.sdk.modules.handles;

import com.agentcat.sdk.AgentCatOptions;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ParameterInjector}.
 * Ported from: agentcat-typescript-sdk/src/tests/handle-injection.test.ts
 */
class ParameterInjectorTest {

    // ── Test helpers ──────────────────────────────────────────────────────────

    private static McpSchema.Tool tool(String name, Map<String, Object> schema) {
        return McpSchema.Tool.builder(name, schema).build();
    }

    /** Schema with a single customer field {@code text:string} */
    private static Map<String, Object> customerSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new ArrayList<>(List.of("text")));
        return schema;
    }

    /** Options with session_id + agent_id injection, no context */
    private static AgentCatOptions optsHandles() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(true);
        opts.setEnableToolCallContext(false);
        return opts;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> props(McpSchema.Tool t) {
        return (Map<String, Object>) ((Map<String, Object>) t.inputSchema()).get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(McpSchema.Tool t) {
        return (List<String>) ((Map<String, Object>) t.inputSchema()).get("required");
    }

    // ── injectParams — input schema ────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void injectsSessionIdAndAgentIdAfterCustomerParams() {
        // ported from: "injects session_id and agent_id after customer params, both required"
        McpSchema.Tool result = ParameterInjector.injectParams(tool("add_todo", customerSchema()), optsHandles());

        assertEquals(List.of("text", "session_id", "agent_id"),
                new ArrayList<>(props(result).keySet()),
                "injected params must follow customer params in order");
        assertEquals(List.of("text", "session_id", "agent_id"), required(result),
                "injected params must be added to required");

        Map<String, Object> sidProp = (Map<String, Object>) props(result).get("session_id");
        assertEquals(ParameterInjector.SESSION_ID_PATTERN, sidProp.get("pattern"),
                "session_id must carry the start|ses_ JSON Schema pattern");
        Map<String, Object> aidProp = (Map<String, Object>) props(result).get("agent_id");
        assertNull(aidProp.get("pattern"),
                "agent_id must have no pattern — it is self-chosen by the agent");
    }

    @Test
    void createsRequiredArrayWhenSchemaHasNone() {
        // ported from: "creates the required array when the schema has none"
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        // no "required" key

        McpSchema.Tool result = ParameterInjector.injectParams(tool("bare", schema), optsHandles());
        assertEquals(List.of("session_id", "agent_id"), required(result));
    }

    @Test
    void doesNotDuplicateSessionIdInRequired() {
        // ported from: "does not duplicate session_id in an existing required array"
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        schema.put("required", new ArrayList<>(List.of("session_id")));

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(false);
        opts.setEnableToolCallContext(false);

        McpSchema.Tool result = ParameterInjector.injectParams(tool("weird_session", schema), opts);
        assertEquals(List.of("session_id"), required(result),
                "session_id already in required must not be pushed again");
    }

    @Test
    void doesNotDuplicateAgentIdInRequired() {
        // ported from: "does not duplicate agent_id in an existing required array"
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        schema.put("required", new ArrayList<>(List.of("agent_id")));

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(true);
        opts.setEnableToolCallContext(false);
        opts.setResolveSessionId((req, extra) -> CompletableFuture.completedFuture("x")); // hook mode → no session_id

        McpSchema.Tool result = ParameterInjector.injectParams(tool("weird", schema), opts);
        assertEquals(List.of("agent_id"), required(result),
                "agent_id already in required must not be pushed again");
    }

    @Test
    @SuppressWarnings("unchecked")
    void leavesRequiredUntouchedWhenCustomerDeclaresAgentId() {
        // ported from: "leaves required untouched when the customer declares agent_id"
        Map<String, Object> agentIdProp = new LinkedHashMap<>();
        agentIdProp.put("type", "string");
        agentIdProp.put("description", "mine");
        Map<String, Object> propsMap = new LinkedHashMap<>();
        propsMap.put("agent_id", agentIdProp);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", propsMap);
        schema.put("required", new ArrayList<>());

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(true);
        opts.setEnableToolCallContext(false);
        opts.setResolveSessionId((req, extra) -> CompletableFuture.completedFuture("x"));

        McpSchema.Tool result = ParameterInjector.injectParams(tool("owns_agent", schema), opts);
        assertTrue(required(result).isEmpty(),
                "customer-declared agent_id must not be re-added to required");
        assertEquals("mine", ((Map<?, ?>) props(result).get("agent_id")).get("description"),
                "customer's agent_id description must not be overwritten");
    }

    @Test
    void fullOrderWithContext() {
        // ported from: "full order with context injector: customer, session_id, agent_id, context"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(true);
        opts.setEnableToolCallContext(true);

        McpSchema.Tool result = ParameterInjector.injectParams(tool("add_todo", customerSchema()), opts);

        assertEquals(List.of("text", "session_id", "agent_id", "context"),
                new ArrayList<>(props(result).keySet()),
                "injection order: customer params → session_id → agent_id → context");
        assertEquals(List.of("text", "session_id", "agent_id", "context"), required(result));
    }

    @Test
    void hookMode_noSessionIdInjected() {
        // ported from: "hook mode: no session_id, agent_id uses the same single description"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(true);
        opts.setEnableToolCallContext(false);
        opts.setResolveSessionId((req, extra) -> CompletableFuture.completedFuture("x"));

        McpSchema.Tool result = ParameterInjector.injectParams(tool("add_todo", customerSchema()), opts);

        assertFalse(props(result).containsKey("session_id"),
                "hook mode must suppress session_id injection");
        assertTrue(props(result).containsKey("agent_id"),
                "agent_id is still injected in hook mode");
        assertEquals(List.of("text", "agent_id"), required(result));
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsCollisionParam() {
        // ported from: "skips a param the tool already defines, and does not record it"
        Map<String, Object> existingSessionId = new LinkedHashMap<>();
        existingSessionId.put("type", "string");
        existingSessionId.put("description", "customer's own");
        Map<String, Object> propsMap = new LinkedHashMap<>();
        propsMap.put("text", Map.of("type", "string"));
        propsMap.put("session_id", existingSessionId);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", propsMap);
        schema.put("required", new ArrayList<>(List.of("text")));

        McpSchema.Tool result = ParameterInjector.injectParams(tool("deploy", schema), optsHandles());

        // customer's session_id is completely untouched
        Map<String, Object> sidProp = (Map<String, Object>) props(result).get("session_id");
        assertEquals("customer's own", sidProp.get("description"));
        assertNull(sidProp.get("pattern"),
                "customer's session_id must not receive an AgentCat pattern");
        // agent_id WAS injected — it alone joins required
        assertEquals(List.of("text", "agent_id"), required(result));
    }

    @Test
    void skipsComplexSchemas() {
        // ported from: "skips complex schemas entirely"
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("oneOf", List.of(Map.of("type", "object")));

        McpSchema.Tool original = tool("odd", schema);
        McpSchema.Tool result = ParameterInjector.injectParams(original, optsHandles());
        assertSame(original, result, "tool with oneOf/allOf/anyOf must be returned unchanged");
    }

    @Test
    @SuppressWarnings("unchecked")
    void removesAdditionalPropertiesFalse() {
        // ported from: "removes additionalProperties:false and handles missing schemas"
        Map<String, Object> schema = new LinkedHashMap<>(customerSchema());
        schema.put("additionalProperties", false);

        McpSchema.Tool result = ParameterInjector.injectParams(tool("strict", schema), optsHandles());
        assertFalse(((Map<String, Object>) result.inputSchema()).containsKey("additionalProperties"),
                "additionalProperties:false must be removed so injected params pass schema validation");
    }

    @Test
    void doesNotSkipGetMoreTools() {
        // ported from: "does NOT skip get_more_tools (handles are injected; context stays bespoke)"
        Map<String, Object> contextProp = new LinkedHashMap<>();
        contextProp.put("type", "string");
        contextProp.put("description", "bespoke");
        Map<String, Object> propsMap = new LinkedHashMap<>();
        propsMap.put("context", contextProp);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", propsMap);
        schema.put("required", new ArrayList<>(List.of("context")));

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(true);
        opts.setEnableToolCallContext(true); // context NOT injected for get_more_tools (it owns its own)

        McpSchema.Tool result = ParameterInjector.injectParams(tool("get_more_tools", schema), opts);

        // session_id and agent_id injected after the bespoke context; context NOT duplicated
        assertEquals(List.of("context", "session_id", "agent_id"),
                new ArrayList<>(props(result).keySet()),
                "handles must be injected after get_more_tools' own context");
        assertEquals("bespoke", ((Map<?, ?>) props(result).get("context")).get("description"),
                "bespoke context description must not be overwritten");
        assertEquals(List.of("context", "session_id", "agent_id"), required(result));
    }

    @Test
    void agentIdDescriptionContent() {
        // ported from: "agent_id copy prescribes the self-chosen model|harness|nonce format"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(true);
        opts.setEnableToolCallContext(false);
        McpSchema.Tool result = ParameterInjector.injectParams(tool("t", customerSchema()), opts);

        String aidDesc = (String) ((Map<?, ?>) props(result).get("agent_id")).get("description");
        assertTrue(aidDesc.contains("required on every call including your first"),
                "agent_id description must say it is required on the first call");
        assertTrue(aidDesc.contains("opus-4.80-1m|claude-code|k3n9x"),
                "agent_id description must include the canonical format example");
        assertTrue(aidDesc.contains("never inherited"),
                "agent_id description must state it is never inherited from parent agents");
        assertFalse(aidDesc.contains("the server will issue"),
                "agent_id is self-chosen — description must not promise server issuance");
        assertFalse(aidDesc.contains("session_id"),
                "agent_id description must be self-contained and not reference session_id");
    }

    // ── addMcpSessionToOutputSchema ────────────────────────────────────────────

    private static McpSchema.Tool toolWithOutputSchema(String name, Map<String, Object> outputSchema) {
        return McpSchema.Tool.builder(name, customerSchema())
                .outputSchema(outputSchema)
                .build();
    }

    private static Map<String, Object> plainOutputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(Map.of("count", Map.of("type", "number"))));
        schema.put("required", List.of("count"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsMcpSessionIntoOutputSchema() {
        // ported from: "injects mcp_session into a plain object outputSchema"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(true);

        McpSchema.Tool result = ParameterInjector.addMcpSessionToOutputSchema(
                toolWithOutputSchema("get_stats", plainOutputSchema()), opts);

        Map<String, Object> outProps = (Map<String, Object>) result.outputSchema().get("properties");
        Map<String, Object> mcpSession = (Map<String, Object>) outProps.get("mcp_session");
        assertNotNull(mcpSession, "mcp_session must be injected into outputSchema");

        Map<String, Object> sub = (Map<String, Object>) mcpSession.get("properties");
        assertEquals(List.of("session_id", "agent_id", "status"),
                new ArrayList<>(sub.keySet()),
                "mcp_session sub-properties must be session_id, agent_id, status in that order");

        // Customer's declared contract is otherwise untouched
        @SuppressWarnings("unchecked")
        List<String> outRequired = (List<String>) result.outputSchema().get("required");
        assertEquals(List.of("count"), outRequired, "customer's required must be preserved");
        assertNotNull(outProps.get("count"), "customer's count property must survive");
    }

    @Test
    @SuppressWarnings("unchecked")
    void hookMode_outputSchemaMcpSessionHasNoSessionFields() {
        // ported from: "sub-properties track modes: hook mode drops session_id and status"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(true);
        opts.setResolveSessionId((req, extra) -> CompletableFuture.completedFuture("x")); // hook mode

        McpSchema.Tool result = ParameterInjector.addMcpSessionToOutputSchema(
                toolWithOutputSchema("t", plainOutputSchema()), opts);

        Map<String, Object> outProps = (Map<String, Object>) result.outputSchema().get("properties");
        Map<String, Object> mcpSession = (Map<String, Object>) outProps.get("mcp_session");
        Map<String, Object> sub = (Map<String, Object>) mcpSession.get("properties");

        assertFalse(sub.containsKey("session_id"),
                "hook mode: session_id must not appear in mcp_session sub-properties");
        assertFalse(sub.containsKey("status"),
                "hook mode: status must not appear in mcp_session sub-properties");
        assertTrue(sub.containsKey("agent_id"),
                "hook mode: agent_id must still appear in mcp_session sub-properties");
    }

    @Test
    void doesNotMutateCustomerOutputSchema() {
        // ported from: "does not mutate the customer's outputSchema object"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(true);

        Map<String, Object> original = plainOutputSchema();
        ParameterInjector.addMcpSessionToOutputSchema(toolWithOutputSchema("t", original), opts);

        assertFalse(((Map<?, ?>) original.get("properties")).containsKey("mcp_session"),
                "original outputSchema must not be mutated by addMcpSessionToOutputSchema");
    }

    @Test
    void skipsComplexOutputSchema() {
        // ported from: "skips complex outputSchema (oneOf/allOf/anyOf) and does not register"
        Map<String, Object> complex = new LinkedHashMap<>();
        complex.put("oneOf", List.of(Map.of("type", "object")));

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(true);

        McpSchema.Tool original = toolWithOutputSchema("poly", complex);
        McpSchema.Tool result = ParameterInjector.addMcpSessionToOutputSchema(original, opts);
        assertSame(original, result,
                "complex outputSchema (oneOf) must be returned unchanged");
    }

    @Test
    void skipsWhenNoOutputSchema() {
        // ported from: "no outputSchema: untouched, nothing registered"
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(true);

        McpSchema.Tool plain = tool("t", customerSchema()); // no outputSchema
        McpSchema.Tool result = ParameterInjector.addMcpSessionToOutputSchema(plain, opts);
        assertSame(plain, result,
                "tool without outputSchema must be returned unchanged");
    }

    @Test
    @SuppressWarnings("unchecked")
    void collisionGuard_customerMcpSessionNeverClobbered() {
        // ported from: "collision: a customer-declared mcp_session is never clobbered"
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("mcp_session", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);

        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(true);

        McpSchema.Tool original = toolWithOutputSchema("t", schema);
        McpSchema.Tool result = ParameterInjector.addMcpSessionToOutputSchema(original, opts);
        assertSame(original, result,
                "customer-declared mcp_session in outputSchema must never be overwritten");
    }
}
