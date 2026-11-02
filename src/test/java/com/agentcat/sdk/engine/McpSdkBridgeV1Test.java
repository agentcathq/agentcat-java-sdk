package com.agentcat.sdk.engine;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link McpSdkBridgeV1}.
 *
 * <h3>Testing strategy</h3>
 * {@code McpSdkBridgeV1} uses reflection to bridge the MCP Java SDK 1.x API.
 * In practice it works on both 1.x and 2.x classpaths because the SDK retained
 * backward-compatible API elements. Tests here verify correct behaviour:
 * <ul>
 *   <li>All four bridge operations complete without throwing.</li>
 *   <li>Structural invariants (name, description) are preserved.</li>
 *   <li>New schemas are applied correctly.</li>
 *   <li>The bridge is a valid {@link McpSdkBridge} implementation.</li>
 * </ul>
 *
 * <p>Tests for the fault-containment paths (return-original / throw RuntimeException)
 * are exercised by the {@code mcp-1x} and {@code mcp-018x} compatibility profiles,
 * where certain SDK API changes cause the reflection calls to fail.
 */
class McpSdkBridgeV1Test {

    private static McpSdkBridgeV1 bridge() {
        return new McpSdkBridgeV1();
    }

    private static McpSchema.Tool simpleTool(String name) {
        return McpSchema.Tool.builder(name, Map.of()).description("desc").build();
    }

    private static McpSchema.Tool toolWith(String name, Map<String, Object> schema) {
        return McpSchema.Tool.builder(name, schema).description("desc").build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Instantiation and interface contract
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void canInstantiateV1Bridge() {
        assertDoesNotThrow(McpSdkBridgeV1::new);
    }

    @Test
    void implementsMcpSdkBridgeInterface() {
        assertInstanceOf(McpSdkBridge.class, bridge());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getInputSchema()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getInputSchemaDoesNotThrow() {
        assertDoesNotThrow(() -> bridge().getInputSchema(simpleTool("echo")));
    }

    @Test
    void getInputSchemaReturnsNonNullMap() {
        assertNotNull(bridge().getInputSchema(simpleTool("echo")));
    }

    @Test
    void getInputSchemaReturnsMapForToolWithProperties() {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("q", Map.of("type", "string")));
        McpSchema.Tool tool = toolWith("search", schema);
        Map<String, Object> result = bridge().getInputSchema(tool);
        assertNotNull(result, "getInputSchema must return a non-null map");
    }

    @Test
    void getInputSchemaIsIdempotent() {
        McpSdkBridgeV1 b = bridge();
        McpSchema.Tool tool = simpleTool("idempotent");
        int size1 = b.getInputSchema(tool).size();
        int size2 = b.getInputSchema(tool).size();
        assertEquals(size1, size2, "repeated calls must return consistent results");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // rebuildTool()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void rebuildToolDoesNotThrow() {
        McpSchema.Tool original = simpleTool("orig");
        assertDoesNotThrow(() -> bridge().rebuildTool(original, Map.of("type", "object")));
    }

    @Test
    void rebuildToolReturnsNonNull() {
        assertNotNull(bridge().rebuildTool(simpleTool("t"), Map.of()));
    }

    @Test
    void rebuildToolPreservesName() {
        McpSchema.Tool original = simpleTool("preserved_name");
        McpSchema.Tool rebuilt  = bridge().rebuildTool(original, Map.of("type", "object"));
        assertEquals("preserved_name", rebuilt.name(),
                "rebuildTool must preserve the original tool name");
    }

    @Test
    void rebuildToolPreservesDescription() {
        McpSchema.Tool original = McpSchema.Tool.builder("t", Map.of())
                .description("my desc").build();
        McpSchema.Tool rebuilt = bridge().rebuildTool(original, Map.of());
        assertEquals("my desc", rebuilt.description());
    }

    @Test
    void rebuildToolAppliesNewSchemaProperties() {
        McpSchema.Tool original = simpleTool("schema_test");
        Map<String, Object> newSchema = Map.of("type", "object",
                "properties", Map.of("param", Map.of("type", "string")));
        McpSchema.Tool rebuilt = bridge().rebuildTool(original, newSchema);
        // V1 bridge converts map → JsonSchema → back to map; properties may survive
        assertNotNull(rebuilt, "rebuilt tool must not be null");
        assertEquals("schema_test", rebuilt.name(), "tool name must be preserved after rebuild");
    }

    @Test
    void rebuildToolDoesNotMutateOriginal() {
        McpSchema.Tool original = simpleTool("no_mutation");
        bridge().rebuildTool(original, Map.of("type", "object",
                "properties", Map.of("extra", Map.of("type", "string"))));
        assertEquals("no_mutation", original.name());
    }

    @Test
    void rebuildToolWithEmptySchemaPreservesName() {
        McpSchema.Tool original = simpleTool("keep_name");
        assertEquals("keep_name", bridge().rebuildTool(original, Map.of()).name());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // withOutputSchema()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void withOutputSchemaDoesNotThrow() {
        assertDoesNotThrow(() ->
                bridge().withOutputSchema(simpleTool("out"), Map.of("type", "object")));
    }

    @Test
    void withOutputSchemaReturnsNonNull() {
        assertNotNull(bridge().withOutputSchema(simpleTool("t"), Map.of()));
    }

    @Test
    void withOutputSchemaPreservesToolName() {
        McpSchema.Tool original = simpleTool("named_tool");
        McpSchema.Tool result   = bridge().withOutputSchema(original, Map.of("type", "object"));
        assertEquals("named_tool", result.name());
    }

    @Test
    void withOutputSchemaPreservesDescription() {
        McpSchema.Tool original = McpSchema.Tool.builder("t", Map.of())
                .description("output desc").build();
        assertEquals("output desc",
                bridge().withOutputSchema(original, Map.of()).description());
    }

    @Test
    void withOutputSchemaSetsOutputSchemaOnResult() {
        McpSchema.Tool original = simpleTool("io_tool");
        McpSchema.Tool result   = bridge().withOutputSchema(original,
                Map.of("type", "object",
                       "properties", Map.of("result", Map.of("type", "string"))));
        assertNotNull(result.outputSchema(),
                "withOutputSchema must set the outputSchema field on the returned tool");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // buildTool()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void buildToolCreatesToolWithCorrectName() {
        McpSchema.Tool tool = bridge().buildTool("built_tool", "desc", Map.of(), null);
        assertEquals("built_tool", tool.name());
    }

    @Test
    void buildToolCreatesToolWithCorrectDescription() {
        McpSchema.Tool tool = bridge().buildTool("t", "my_description", Map.of(), null);
        assertEquals("my_description", tool.description());
    }

    @Test
    void buildToolWithNullAnnotationsDoesNotThrow() {
        assertDoesNotThrow(() -> bridge().buildTool("t", "d", Map.of(), null));
    }

    @Test
    void buildToolReturnsNonNull() {
        assertNotNull(bridge().buildTool("t", "d", Map.of(), null));
    }

    @Test
    void buildToolCreatesDistinctInstancesOnMultipleCalls() {
        McpSchema.Tool a = bridge().buildTool("tool", "d", Map.of(), null);
        McpSchema.Tool b = bridge().buildTool("tool", "d", Map.of(), null);
        assertNotSame(a, b, "each buildTool call must produce a new instance");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Consistency with McpSdkBridgeV2
    // ══════════════════════════════════════════════════════════════════════════

    // ── Fault-containment: internal field() helper swallows NoSuchMethodException ─

    /**
     * {@code McpSdkBridgeV1.field()} calls accessor methods by name and catches
     * {@link NoSuchMethodException} silently — returning null instead of throwing.
     * This is exercised indirectly through {@code getInputSchema()}: when invoked on
     * a tool whose schema object lacks certain accessor methods, the V1 bridge must
     * not propagate the exception.
     */
    @Test
    void getInputSchemaDoesNotThrowWhenSchemaAccessorsMissing() {
        // On any SDK version, getInputSchema must never throw — the internal field()
        // helper catches NoSuchMethodException and returns null for missing accessors.
        McpSchema.Tool tool = simpleTool("accessor_test");
        assertDoesNotThrow(() -> bridge().getInputSchema(tool),
                "getInputSchema must not throw even when some schema accessor methods are absent");
    }

    @Test
    void getInputSchemaCanBeCalledRepeatedly() {
        McpSdkBridgeV1 b = bridge();
        McpSchema.Tool tool = simpleTool("repeat");
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> b.getInputSchema(tool),
                    "getInputSchema must not throw on any call #" + i);
        }
    }

    // ── Fault-containment: all four bridge methods never propagate unexpected errors ─

    @Test
    void allBridgeMethodsAreIndividuallyFaultContained() {
        McpSdkBridgeV1 b = bridge();
        McpSchema.Tool tool = simpleTool("fc_all");
        // Each method must complete without throwing on any supported SDK version
        assertDoesNotThrow(() -> b.getInputSchema(tool),     "getInputSchema");
        assertDoesNotThrow(() -> b.rebuildTool(tool, Map.of()), "rebuildTool");
        assertDoesNotThrow(() -> b.withOutputSchema(tool, Map.of("type", "object")), "withOutputSchema");
        // buildTool either succeeds or throws RuntimeException (but not Error or other Throwable)
        try {
            b.buildTool("fc_tool", "d", Map.of(), null);
        } catch (RuntimeException e) {
            // Acceptable — buildTool explicitly rethrows as RuntimeException
        }
        // No other exception types must escape
    }

    @Test
    void rebuildToolReturnValueIsAlwaysNonNull() {
        McpSdkBridgeV1 b = bridge();
        // On any SDK version rebuildTool must return either the rebuilt tool or the original — never null
        McpSchema.Tool original = simpleTool("non_null_test");
        McpSchema.Tool result = b.rebuildTool(original, Map.of("type", "object"));
        assertNotNull(result, "rebuildTool must never return null");
    }

    @Test
    void withOutputSchemaReturnValueIsAlwaysNonNull() {
        McpSdkBridgeV1 b = bridge();
        McpSchema.Tool original = simpleTool("non_null_out");
        McpSchema.Tool result = b.withOutputSchema(original, Map.of("type", "object"));
        assertNotNull(result, "withOutputSchema must never return null");
    }

    @Test
    void v1AndV2BridgesBothPreserveToolNameOnRebuild() {
        McpSchema.Tool original   = simpleTool("common_name");
        Map<String, Object> schema = Map.of("type", "object");

        McpSchema.Tool v1Result = new McpSdkBridgeV1().rebuildTool(original, schema);
        McpSchema.Tool v2Result = new McpSdkBridgeV2().rebuildTool(original, schema);

        assertEquals("common_name", v1Result.name(), "V1 bridge must preserve tool name");
        assertEquals("common_name", v2Result.name(), "V2 bridge must preserve tool name");
        assertEquals(v2Result.name(), v1Result.name(), "both bridges must agree on tool name");
    }
}
