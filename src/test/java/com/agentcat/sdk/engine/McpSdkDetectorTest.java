package com.agentcat.sdk.engine;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link McpSdkDetector}.
 *
 * <p>The detector inspects {@code McpSchema.Tool.inputSchema()} return type at class-load
 * time and caches the matching {@link McpSdkBridge} singleton for the JVM lifetime.
 *
 * <h3>What we verify</h3>
 * <ol>
 *   <li>{@link McpSdkDetector#bridge()} is never null.</li>
 *   <li>The same instance is returned on every call (singleton).</li>
 *   <li>The bridge implementation matches the SDK version on the current classpath.</li>
 *   <li>The bridge is functional — all four operations work end-to-end.</li>
 * </ol>
 */
class McpSdkDetectorTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static McpSchema.Tool simpleTool(String name) {
        return McpSchema.Tool.builder(name, Map.of()).description("test").build();
    }

    private static McpSchema.Tool toolWithSchema(String name, Map<String, Object> schema) {
        return McpSchema.Tool.builder(name, schema).description("desc").build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // bridge() contract
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void bridgeIsNeverNull() {
        assertNotNull(McpSdkDetector.bridge(), "bridge() must never return null");
    }

    @Test
    void bridgeIsSingleton() {
        McpSdkBridge first  = McpSdkDetector.bridge();
        McpSdkBridge second = McpSdkDetector.bridge();
        assertSame(first, second,
                "bridge() must return the same cached instance on every call");
    }

    @Test
    void bridgeIsSingletonAcrossMultipleCalls() {
        McpSdkBridge ref = McpSdkDetector.bridge();
        for (int i = 0; i < 10; i++) {
            assertSame(ref, McpSdkDetector.bridge(),
                    "bridge() must return the identical singleton on call #" + (i + 1));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Version detection — bridge type matches classpath
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void bridgeTypeMatchesInputSchemaReturnType() throws Exception {
        // The detector selects McpSdkBridgeV2 when inputSchema() returns Map,
        // and McpSdkBridgeV1 otherwise. Verify the selection is coherent.
        Method inputSchema = McpSchema.Tool.class.getMethod("inputSchema");
        McpSdkBridge bridge = McpSdkDetector.bridge();

        if (inputSchema.getReturnType() == Map.class) {
            assertInstanceOf(McpSdkBridgeV2.class, bridge,
                    "SDK 2.x detected (inputSchema → Map): bridge must be McpSdkBridgeV2");
        } else {
            assertInstanceOf(McpSdkBridgeV1.class, bridge,
                    "SDK 1.x detected (inputSchema → JsonSchema): bridge must be McpSdkBridgeV1");
        }
    }

    @Test
    void detectsCurrentSdkVersionWithoutThrowing() {
        assertDoesNotThrow(McpSdkDetector::bridge,
                "bridge() must not throw under any supported SDK version");
    }

    @Test
    void bridgeIsNeverNullRegardlessOfSdkVersion() {
        // resolve() has a catch(Exception e) → defaults to McpSdkBridgeV2.
        // Regardless of whether that path fires, bridge() must always return non-null.
        for (int i = 0; i < 3; i++) {
            assertNotNull(McpSdkDetector.bridge(),
                    "bridge() must not return null (fault-containment default is V2)");
        }
    }

    @Test
    void bridgeFaultContainmentDefaultIsV2CompatibleImplementation() {
        // Even if detection fails internally (catch path), the returned bridge must
        // implement McpSdkBridge and be functionally usable.
        McpSdkBridge bridge = McpSdkDetector.bridge();
        assertInstanceOf(McpSdkBridge.class, bridge);
        // The default (V2) bridge must be able to operate on a real tool without throwing
        McpSchema.Tool tool = McpSchema.Tool.builder("fc_tool", Map.of()).description("d").build();
        assertDoesNotThrow(() -> bridge.getInputSchema(tool),
                "fault-containment bridge must handle getInputSchema without throwing");
        assertDoesNotThrow(() -> bridge.rebuildTool(tool, Map.of()),
                "fault-containment bridge must handle rebuildTool without throwing");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Bridge functional — getInputSchema()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getInputSchemaReturnsNonNullMap() {
        assertNotNull(McpSdkDetector.bridge().getInputSchema(simpleTool("echo")));
    }

    @Test
    void getInputSchemaReturnsEmptyPropertiesForToolWithNoProperties() {
        McpSchema.Tool tool = simpleTool("no_props");
        Map<String, Object> schema = McpSdkDetector.bridge().getInputSchema(tool);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        assertTrue(props.isEmpty(), "tool with empty schema must have no properties");
    }

    @Test
    void getInputSchemaReturnsPropertiesFromSchema() {
        Map<String, Object> schema = new HashMap<>();
        Map<String, Object> props  = new HashMap<>();
        props.put("query", Map.of("type", "string"));
        schema.put("type", "object");
        schema.put("properties", props);
        McpSchema.Tool tool = toolWithSchema("search", schema);

        @SuppressWarnings("unchecked")
        Map<String, Object> returned = McpSdkDetector.bridge().getInputSchema(tool);
        @SuppressWarnings("unchecked")
        Map<String, Object> returnedProps = (Map<String, Object>) returned.get("properties");
        assertNotNull(returnedProps);
        assertTrue(returnedProps.containsKey("query"),
                "getInputSchema must return the properties passed at construction");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Bridge functional — rebuildTool()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void rebuildToolPreservesName() {
        McpSchema.Tool rebuilt = McpSdkDetector.bridge()
                .rebuildTool(simpleTool("original_name"), Map.of("type", "object"));
        assertEquals("original_name", rebuilt.name());
    }

    @Test
    void rebuildToolPreservesDescription() {
        McpSchema.Tool original = McpSchema.Tool.builder("t", Map.of())
                .description("my description").build();
        McpSchema.Tool rebuilt = McpSdkDetector.bridge()
                .rebuildTool(original, Map.of("type", "object"));
        assertEquals("my description", rebuilt.description());
    }

    @Test
    void rebuildToolAppliesNewSchema() {
        McpSchema.Tool original = simpleTool("schema_test");
        Map<String, Object> newSchema = Map.of("type", "object",
                "properties", Map.of("input", Map.of("type", "string")));

        McpSchema.Tool rebuilt = McpSdkDetector.bridge().rebuildTool(original, newSchema);
        @SuppressWarnings("unchecked")
        Map<String, Object> returned = McpSdkDetector.bridge().getInputSchema(rebuilt);
        @SuppressWarnings("unchecked")
        Map<String, Object> returnedProps = (Map<String, Object>) returned.get("properties");
        assertNotNull(returnedProps);
        assertTrue(returnedProps.containsKey("input"),
                "rebuilt tool must carry the new input schema");
    }

    @Test
    void rebuildToolDoesNotMutateOriginal() {
        McpSchema.Tool original = simpleTool("immutable");
        String nameBefore = original.name();
        McpSdkDetector.bridge().rebuildTool(original,
                Map.of("type", "object", "properties",
                        Map.of("new_param", Map.of("type", "string"))));
        assertEquals(nameBefore, original.name(), "rebuildTool must not mutate the original tool");
    }

    @Test
    void rebuildToolWithEmptySchemaPreservesName() {
        McpSchema.Tool original = simpleTool("keep_name");
        McpSchema.Tool rebuilt  = McpSdkDetector.bridge().rebuildTool(original, Map.of());
        assertEquals("keep_name", rebuilt.name());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Bridge functional — buildTool()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void buildToolCreatesToolWithCorrectName() {
        McpSchema.Tool tool = McpSdkDetector.bridge()
                .buildTool("built_tool", "desc", Map.of("type", "object"), null);
        assertEquals("built_tool", tool.name());
    }

    @Test
    void buildToolCreatesToolWithCorrectDescription() {
        McpSchema.Tool tool = McpSdkDetector.bridge()
                .buildTool("t", "my desc", Map.of(), null);
        assertEquals("my desc", tool.description());
    }

    @Test
    void buildToolCreatesToolWithProperties() {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("x", Map.of("type", "integer")));
        McpSchema.Tool tool = McpSdkDetector.bridge().buildTool("param_tool", "d", schema, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> returned = McpSdkDetector.bridge().getInputSchema(tool);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) returned.get("properties");
        assertNotNull(props);
        assertTrue(props.containsKey("x"));
    }

    @Test
    void buildToolWithNullAnnotationsDoesNotThrow() {
        assertDoesNotThrow(() ->
                McpSdkDetector.bridge().buildTool("t", "d", Map.of(), null));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Bridge functional — withOutputSchema()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void withOutputSchemaPreservesToolName() {
        McpSchema.Tool with = McpSdkDetector.bridge()
                .withOutputSchema(simpleTool("output_tool"), Map.of("type", "object"));
        assertEquals("output_tool", with.name());
    }

    @Test
    void withOutputSchemaPreservesInputSchema() {
        Map<String, Object> inputSchema = Map.of("type", "object",
                "properties", Map.of("q", Map.of("type", "string")));
        McpSchema.Tool original = toolWithSchema("io_tool", inputSchema);
        McpSchema.Tool with = McpSdkDetector.bridge()
                .withOutputSchema(original, Map.of("type", "object"));

        @SuppressWarnings("unchecked")
        Map<String, Object> returned = McpSdkDetector.bridge().getInputSchema(with);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) returned.get("properties");
        assertNotNull(props);
        assertTrue(props.containsKey("q"),
                "withOutputSchema must preserve the original input schema");
    }

    @Test
    void withOutputSchemaSetsOutputSchema() {
        McpSchema.Tool with = McpSdkDetector.bridge().withOutputSchema(
                simpleTool("out_test"),
                Map.of("type", "object", "properties", Map.of("result", Map.of("type", "string"))));
        assertNotNull(with.outputSchema(), "outputSchema must be set after withOutputSchema()");
    }
}
