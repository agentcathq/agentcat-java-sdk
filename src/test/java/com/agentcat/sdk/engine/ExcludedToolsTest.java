package com.agentcat.sdk.engine;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.integration.EventCapture;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ExcludedToolsTest {
    private static McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder(name, Map.of("type", "object", "properties", Map.of(),
                        "additionalProperties", false))
                .outputSchema(Map.of("type", "object", "properties",
                        Map.of("name", Map.of("type", "string")), "required", List.of("name"),
                        "additionalProperties", false))
                .build();
    }

    @Test
    void exclusionsDefaultToEmptyAndCopyTheConfiguredSet() {
        AgentCatOptions opts = new AgentCatOptions();
        assertTrue(opts.getExcludedTools().isEmpty());
        Set<String> names = new HashSet<>(Set.of("get_my_account"));
        assertSame(opts, opts.setExcludedTools(names));
        names.clear();
        assertEquals(Set.of("get_my_account"), opts.getExcludedTools());
        assertThrows(UnsupportedOperationException.class, () -> opts.getExcludedTools().clear());
        assertTrue(opts.setExcludedTools(null).getExcludedTools().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void excludedToolPreservesSchemasArgumentsAndResponsesIncludingLateRegistration(boolean stateless)
            throws Exception {
        AtomicInteger hooks = new AtomicInteger();
        AgentCatOptions opts = new AgentCatOptions().setEnableAgentTracking(true)
                .setExcludedTools(Set.of("get_my_account"))
                .setIdentify((req, ctx) -> {
                    hooks.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                });
        McpSchema.Tool originalTool = tool("get_my_account");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get_my_account", Map.of());
        McpSchema.CallToolResult response = McpSchema.CallToolResult.builder()
                .addTextContent("account").structuredContent(Map.of("name", "Alice")).build();
        Function<McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> handler = req -> {
            assertSame(request, req);
            assertTrue(req.arguments().isEmpty());
            return Mono.just(response);
        };
        EventCapture capture = new EventCapture();
        capture.start();
        try {
            if (stateless) {
                var wrapper = new StatelessToolCallWrapper("proj", "srv", "1", opts);
                var original = new McpStatelessServerFeatures.AsyncToolSpecification(originalTool,
                        (ctx, req) -> handler.apply(req));
                assertSame(original, wrapper.wrap(original));
                var list = wrapper.newToolList(List.of());
                list.add(original);
                assertSame(original, list.get(0));
                assertSame(response, list.get(0).callHandler().apply(null, request).block());
            } else {
                var wrapper = new StatefulToolCallWrapper("proj", "srv", "1", opts);
                var original = AsyncToolSpecFactory.buildSpec(originalTool,
                        (ctx, req) -> handler.apply(req));
                assertSame(original, wrapper.wrap(original));
                var list = wrapper.newToolList(List.of());
                list.add(original);
                assertSame(original, list.get(0));
                assertSame(response, list.get(0).callHandler().apply(null, request).block());
            }
            assertEquals(0, hooks.get());
            assertTrue(capture.getEvents().isEmpty());
        } finally {
            capture.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void otherToolsKeepSchemasSessionMetadataAndAnalytics(boolean stateless) throws Exception {
        AgentCatOptions opts = new AgentCatOptions().setExcludedTools(Set.of("get_my_account"));
        McpSchema.Tool original = tool("echo");
        var response = McpSchema.CallToolResult.builder().addTextContent("ok")
                .structuredContent(Map.of("name", "Alice")).build();
        var request = new McpSchema.CallToolRequest("echo", Map.of("session_id", "start", "context", "test"));
        McpSchema.Tool wrappedTool;
        McpSchema.CallToolResult result;
        EventCapture capture = new EventCapture();
        capture.start();
        try {
            if (stateless) {
                var spec = new McpStatelessServerFeatures.AsyncToolSpecification(original, (ctx, req) -> {
                    assertTrue(req.arguments().isEmpty());
                    return Mono.just(response);
                });
                var wrapped = new StatelessToolCallWrapper("proj", "srv", "1", opts).wrap(spec);
                wrappedTool = wrapped.tool();
                result = wrapped.callHandler().apply(null, request).block();
            } else {
                McpServerFeatures.AsyncToolSpecification spec = AsyncToolSpecFactory.buildSpec(original, (ctx, req) -> {
                    assertTrue(req.arguments().isEmpty());
                    return Mono.just(response);
                });
                var wrapped = new StatefulToolCallWrapper("proj", "srv", "1", opts).wrap(spec);
                wrappedTool = wrapped.tool();
                result = wrapped.callHandler().apply(null, request).block();
            }
            var props = (Map<String, Object>) ((Map<String, Object>) wrappedTool.inputSchema()).get("properties");
            assertTrue(props.keySet().containsAll(Set.of("session_id", "context")));
            assertTrue(((Map<?, ?>) wrappedTool.outputSchema().get("properties")).containsKey("mcp_session"));
            assertNotNull(result);
            assertTrue(((Map<?, ?>) result.structuredContent()).containsKey("mcp_session"));
            capture.flush();
            assertNotNull(capture.findByTool("echo"));
        } finally {
            capture.stop();
        }
    }
}
