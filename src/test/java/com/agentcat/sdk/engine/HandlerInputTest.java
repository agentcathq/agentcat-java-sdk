package com.agentcat.sdk.engine;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the customer's tool handler receives. The rule (TS {@code stripInjectedArguments},
 * C# Q7): the handler gets the client's request with exactly the names AgentCat injected
 * into this tool's schema removed — on every path, tracing on or off — and nothing else
 * changes: its own same-named arguments, {@code _meta}, argument order and mutability stay.
 */
class HandlerInputTest {

    private static McpSchema.Tool tool(String name, Map<String, Object> schema) {
        return McpSchema.Tool.builder(name, schema).description(name).build();
    }

    private static Map<String, Object> objectSchema(Map<String, Object> props) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        return schema;
    }

    /** Registers the tool through the injector, as the wrappers do, then prepares a call. */
    private static CallToolRequest handlerInput(McpSchema.Tool tool, AgentCatOptions opts, CallToolRequest request) {
        InjectedParamsRegistry registry = new InjectedParamsRegistry();
        DeclaredSessionParamsRegistry declared = new DeclaredSessionParamsRegistry();
        ParameterInjector.injectParams(tool, opts, registry, declared);
        return ToolCallWrapper.prepare(request, null, tool.name(), "srv", "1.0", opts, registry, declared)
                .callArgs().stripped();
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ── The customer's own same-named arguments ─────────────────────────────

    @Test
    void toolThatDeclaresSessionIdAndContextKeepsBoth() {
        // Both names collide, so AgentCat injects nothing into this tool.
        McpSchema.Tool t = tool("lookup", objectSchema(Map.of(
                "session_id", Map.of("type", "string"),
                "context", Map.of("type", "string"))));

        CallToolRequest in = handlerInput(t, new AgentCatOptions(),
                new CallToolRequest("lookup", ordered("session_id", "their-session", "context", "their-context")));

        assertEquals("their-session", in.arguments().get("session_id"));
        assertEquals("their-context", in.arguments().get("context"));
    }

    @Test
    void composedRootSchemaKeepsItsOwnSessionIdAndContext() {
        // oneOf at the root: AgentCat skips injection entirely.
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("oneOf", List.of(Map.of("properties", Map.of("session_id", Map.of("type", "string")))));

        CallToolRequest in = handlerInput(tool("composed", schema), new AgentCatOptions(),
                new CallToolRequest("composed", ordered("session_id", "s", "context", "c")));

        assertEquals("s", in.arguments().get("session_id"));
        assertEquals("c", in.arguments().get("context"));
    }

    @Test
    void contextOffKeepsTheToolsOwnSessionIdWhenItDeclaresIt() {
        McpSchema.Tool t = tool("lookup", objectSchema(Map.of("session_id", Map.of("type", "string"))));

        CallToolRequest in = handlerInput(t, new AgentCatOptions().setEnableToolCallContext(false),
                new CallToolRequest("lookup", ordered("session_id", "theirs")));

        assertEquals("theirs", in.arguments().get("session_id"));
    }

    @Test
    void injectedNamesAreStillStripped() {
        McpSchema.Tool t = tool("echo", objectSchema(Map.of("msg", Map.of("type", "string"))));

        CallToolRequest in = handlerInput(t, new AgentCatOptions(),
                new CallToolRequest("echo", ordered("msg", "hi", "session_id", "ses_x", "context", "why")));

        assertEquals(Map.of("msg", "hi"), in.arguments());
    }

    // ── _meta, order, mutability ────────────────────────────────────────────

    @Test
    void metaReachesTheHandlerWhenArgumentsAreStripped() {
        McpSchema.Tool t = tool("echo", objectSchema(Map.of("msg", Map.of("type", "string"))));
        Map<String, Object> meta = Map.of("progressToken", "tok-1");

        CallToolRequest in = handlerInput(t, new AgentCatOptions(),
                new CallToolRequest("echo", ordered("msg", "hi", "context", "why"), meta));

        assertEquals(meta, in.meta());
    }

    @Test
    void metaReachesTheHandlerWhenNothingIsStripped() {
        McpSchema.Tool t = tool("echo", objectSchema(Map.of("msg", Map.of("type", "string"))));
        Map<String, Object> meta = Map.of("progressToken", "tok-2");

        CallToolRequest in = handlerInput(t, new AgentCatOptions(),
                new CallToolRequest("echo", ordered("msg", "hi"), meta));

        assertEquals(meta, in.meta());
    }

    @Test
    void handlerCanChangeItsArguments() {
        McpSchema.Tool t = tool("echo", objectSchema(Map.of("msg", Map.of("type", "string"))));

        CallToolRequest in = handlerInput(t, new AgentCatOptions(),
                new CallToolRequest("echo", ordered("msg", "hi", "context", "why")));

        assertDoesNotThrow(() -> in.arguments().put("limit", 10));
    }

    @Test
    void argumentOrderIsKept() {
        McpSchema.Tool t = tool("echo", objectSchema(ordered(
                "z", Map.of("type", "string"), "a", Map.of("type", "string"), "m", Map.of("type", "string"))));

        CallToolRequest in = handlerInput(t, new AgentCatOptions(),
                new CallToolRequest("echo", ordered("z", 1, "context", "why", "a", 2, "m", 3)));

        assertEquals(List.of("z", "a", "m"), List.copyOf(in.arguments().keySet()));
    }

    // ── Tracing off (C# Q7) ─────────────────────────────────────────────────

    @Test
    void tracingOffStripsTheInjectedContext() {
        // C# Q7: injection does not depend on tracing (TS parity), so the handler must
        // never see the context argument that the schema asked for.
        McpSchema.Tool t = tool("echo", objectSchema(Map.of("msg", Map.of("type", "string"))));

        CallToolRequest in = handlerInput(t, new AgentCatOptions().setEnableTracing(false),
                new CallToolRequest("echo", ordered("msg", "hi", "context", "why")));

        assertEquals(Map.of("msg", "hi"), in.arguments());
    }

    @Test
    void tracingOffStillStripsAnythingAgentCatInjected() {
        // A stale client can still send context after tracing was turned off.
        McpSchema.Tool t = tool("echo", objectSchema(Map.of("msg", Map.of("type", "string"))));
        AgentCatOptions on = new AgentCatOptions();
        InjectedParamsRegistry registry = new InjectedParamsRegistry();
        DeclaredSessionParamsRegistry declared = new DeclaredSessionParamsRegistry();
        ParameterInjector.injectParams(t, on, registry, declared);

        CallToolRequest in = ToolCallWrapper.prepare(
                new CallToolRequest("echo", ordered("msg", "hi", "context", "why")),
                null, "echo", "srv", "1.0", new AgentCatOptions().setEnableTracing(false), registry, declared)
                .callArgs().stripped();

        assertEquals(Map.of("msg", "hi"), in.arguments());
    }

    @Test
    void degradedPathStripsInjectedArgumentsAndKeepsMeta() {
        // Used when prepare() fails: analytics is lost, the strip rule is not.
        McpSchema.Tool t = tool("echo", objectSchema(Map.of("msg", Map.of("type", "string"))));
        AgentCatOptions opts = new AgentCatOptions();
        InjectedParamsRegistry registry = new InjectedParamsRegistry();
        ParameterInjector.injectParams(t, opts, registry, new DeclaredSessionParamsRegistry());
        Map<String, Object> meta = Map.of("progressToken", "tok-3");

        CallToolRequest in = ToolCallWrapper.handlerRequestOrOriginal(
                new CallToolRequest("echo", ordered("msg", "hi", "session_id", "ses_x", "context", "why"), meta),
                "echo", opts, registry);

        assertEquals(Map.of("msg", "hi"), in.arguments());
        assertEquals(meta, in.meta());
    }
}
