package com.agentcat.sdk.engine;

import io.modelcontextprotocol.spec.McpSchema;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * {@link McpSdkBridge} implementation for MCP Java SDK 1.x.
 *
 * <p>In 1.x, {@code Tool.inputSchema()} returns {@code McpSchema.JsonSchema}
 * (a typed record), and the builder factory is {@code Tool.builder()} (no-arg),
 * followed by {@code .name(...).description(...).inputSchema(JsonSchema)}.
 *
 * <p>All 1.x-specific API calls go through reflection so this class compiles
 * and ships against the 2.x dependency but runs correctly when the user's
 * classpath contains a 1.x SDK.
 */
final class McpSdkBridgeV1 implements McpSdkBridge {

    // Lazily resolved — loaded once, then reused for every tool operation.
    private volatile Class<?> jsonSchemaClass;

    // ── McpSdkBridge ─────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> getInputSchema(McpSchema.Tool tool) {
        try {
            Method m = tool.getClass().getMethod("inputSchema");
            Object jsonSchema = m.invoke(tool);
            if (jsonSchema == null) return new LinkedHashMap<>();
            return jsonSchemaToMap(jsonSchema);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @Override
    public McpSchema.Tool rebuildTool(McpSchema.Tool original, Map<String, Object> newInputSchema) {
        try {
            Object jsonSchema = mapToJsonSchema(newInputSchema);
            Object builder = invokeStatic(McpSchema.Tool.class, "builder");

            builder = invoke(builder, "name",        String.class,  original.name());
            builder = invoke(builder, "description", String.class,  original.description());
            builder = invoke(builder, "inputSchema", jsonSchemaClass(), jsonSchema);

            if (original.title() != null)
                builder = invoke(builder, "title", String.class, original.title());
            if (original.outputSchema() != null)
                builder = invoke(builder, "outputSchema", Map.class, original.outputSchema());
            if (original.annotations() != null)
                builder = invoke(builder, "annotations", McpSchema.ToolAnnotations.class, original.annotations());
            if (original.meta() != null)
                builder = invoke(builder, "meta", Map.class, original.meta());

            return (McpSchema.Tool) builder.getClass().getMethod("build").invoke(builder);
        } catch (Throwable e) {
            return original; // fault containment — return original unchanged
        }
    }

    @Override
    public McpSchema.Tool withOutputSchema(McpSchema.Tool original, Map<String, Object> newOutputSchema) {
        try {
            Object jsonSchema = mapToJsonSchema(getInputSchema(original));
            Object builder = invokeStatic(McpSchema.Tool.class, "builder");

            builder = invoke(builder, "name",         String.class,  original.name());
            builder = invoke(builder, "description",  String.class,  original.description());
            builder = invoke(builder, "inputSchema",  jsonSchemaClass(), jsonSchema);
            builder = invoke(builder, "outputSchema", Map.class, newOutputSchema);

            if (original.title() != null)
                builder = invoke(builder, "title", String.class, original.title());
            if (original.annotations() != null)
                builder = invoke(builder, "annotations", McpSchema.ToolAnnotations.class, original.annotations());
            if (original.meta() != null)
                builder = invoke(builder, "meta", Map.class, original.meta());

            return (McpSchema.Tool) builder.getClass().getMethod("build").invoke(builder);
        } catch (Throwable e) {
            return original;
        }
    }

    @Override
    public McpSchema.Tool buildTool(String name, String description,
                                     Map<String, Object> inputSchema,
                                     McpSchema.ToolAnnotations annotations) {
        try {
            Object jsonSchema = mapToJsonSchema(inputSchema);
            Object builder = invokeStatic(McpSchema.Tool.class, "builder");

            builder = invoke(builder, "name",        String.class,  name);
            builder = invoke(builder, "description", String.class,  description);
            builder = invoke(builder, "inputSchema", jsonSchemaClass(), jsonSchema);

            if (annotations != null)
                builder = invoke(builder, "annotations", McpSchema.ToolAnnotations.class, annotations);

            return (McpSchema.Tool) builder.getClass().getMethod("build").invoke(builder);
        } catch (Throwable e) {
            // Re-wrap so callers with 'throws Exception' can handle it without losing the Error.
            throw new RuntimeException("McpSdkBridgeV1.buildTool failed for '" + name + "'", e);
        }
    }

    // ── Conversion helpers ────────────────────────────────────────────────────

    /**
     * Converts a 1.x {@code McpSchema.JsonSchema} record to a plain map.
     * Fields: type, properties, required, additionalProperties, $defs (stored as "defs"), definitions.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonSchemaToMap(Object jsonSchema) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        putIfNotNull(result, "type",                 field(jsonSchema, "type"));
        putIfNotNull(result, "properties",           field(jsonSchema, "properties"));
        putIfNotNull(result, "required",             field(jsonSchema, "required"));
        putIfNotNull(result, "additionalProperties", field(jsonSchema, "additionalProperties"));
        putIfNotNull(result, "$defs",                field(jsonSchema, "defs"));        // record field "defs" → JSON key "$defs"
        putIfNotNull(result, "definitions",          field(jsonSchema, "definitions"));
        return result;
    }

    /**
     * Converts a plain map to a 1.x {@code McpSchema.JsonSchema} record via its
     * canonical constructor: {@code JsonSchema(type, properties, required,
     * additionalProperties, defs, definitions)}.
     */
    @SuppressWarnings("unchecked")
    private Object mapToJsonSchema(Map<String, Object> map) throws Exception {
        Class<?> cls = jsonSchemaClass();
        Constructor<?> ctor = cls.getDeclaredConstructor(
                String.class,   // type
                Map.class,      // properties
                List.class,     // required
                Boolean.class,  // additionalProperties
                Map.class,      // defs  ($defs)
                Map.class       // definitions
        );
        return ctor.newInstance(
                map.get("type"),
                map.get("properties"),
                map.get("required"),
                (Boolean) map.get("additionalProperties"),
                map.get("$defs"),         // JSON "$defs" → constructor param "defs"
                map.get("definitions")
        );
    }

    // ── Reflection utilities ──────────────────────────────────────────────────

    private Class<?> jsonSchemaClass() throws ClassNotFoundException {
        if (jsonSchemaClass == null) {
            jsonSchemaClass = Class.forName("io.modelcontextprotocol.spec.McpSchema$JsonSchema");
        }
        return jsonSchemaClass;
    }

    /** Calls a no-arg static method. */
    private static Object invokeStatic(Class<?> cls, String methodName) throws Exception {
        return cls.getMethod(methodName).invoke(null);
    }

    /** Calls a single-arg instance method. */
    private static Object invoke(Object target, String methodName,
                                  Class<?> paramType, Object arg) throws Exception {
        return target.getClass().getMethod(methodName, paramType).invoke(target, arg);
    }

    /** Reads a record component by name via its accessor method. */
    private static Object field(Object record, String name) throws Exception {
        try {
            return record.getClass().getMethod(name).invoke(record);
        } catch (NoSuchMethodException e) {
            return null; // field absent in this SDK version — skip
        }
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }
}
