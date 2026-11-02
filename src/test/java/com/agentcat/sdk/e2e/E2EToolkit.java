package com.agentcat.sdk.e2e;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link McpSyncServer} with five standard tools, mirroring the
 * TypeScript e2e-http toolkit exactly.
 *
 * <h3>Tools</h3>
 * <ul>
 *   <li><b>echo</b> — {@code {msg: string}} → returns msg text; happy path.</li>
 *   <li><b>loose</b> — open schema (no {@code additionalProperties: false}); unknown
 *       keys reach the handler; returns all args serialised to text.</li>
 *   <li><b>strict</b> — schema with {@code additionalProperties: false} (AgentCat
 *       removes it so injected handles ride along; customer handler still only sees
 *       the declared field).</li>
 *   <li><b>structured</b> — declares an {@code outputSchema}; returns
 *       {@code structuredContent} so the structured mint-back mirror applies.</li>
 *   <li><b>boom</b> — always throws {@link RuntimeException}("boom"); error path.</li>
 * </ul>
 *
 * <p>Every handler pushes {@code (tool, args)} into the provided {@link ToolSink}
 * after AgentCat's strip runs — sink entries are the strip-proof channel.
 */
public final class E2EToolkit {

    private E2EToolkit() {}

    /** Project ID used by all e2e lanes. */
    public static final String E2E_PROJECT_ID = "proj_test";

    /**
     * Builds the server with all five tools wired to {@code sink}.
     *
     * @param transport the already-constructed transport provider to bind to
     * @param sink      the sink that tool handlers write into
     * @return a fully-built (but not yet tracked) {@link McpSyncServer}
     */
    public static McpSyncServer buildSync(
            HttpServletStreamableServerTransportProvider transport,
            ToolSink sink) {

        return McpServer.sync(transport)
                .serverInfo("e2e-toolkit", "1.0.0")
                .validateToolInputs(false)
                // ── echo ──────────────────────────────────────────────────────
                .toolCall(
                        McpSchema.Tool.builder("echo", echoSchema())
                                .description("Echo a message back")
                                .build(),
                        (exchange, req) -> {
                            sink.record("echo", req.arguments());
                            String msg = String.valueOf(req.arguments().get("msg"));
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent(msg)
                                    .build();
                        })
                // ── loose ─────────────────────────────────────────────────────
                .toolCall(
                        McpSchema.Tool.builder("loose", looseSchema())
                                .description("Loose schema: unknown keys pass through to the handler")
                                .build(),
                        (exchange, req) -> {
                            sink.record("loose", req.arguments());
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent(req.arguments().toString())
                                    .build();
                        })
                // ── strict ────────────────────────────────────────────────────
                .toolCall(
                        McpSchema.Tool.builder("strict", strictSchema())
                                .description("Strict schema: additionalProperties are rejected")
                                .build(),
                        (exchange, req) -> {
                            sink.record("strict", req.arguments());
                            String msg = String.valueOf(req.arguments().get("msg"));
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent(msg)
                                    .build();
                        })
                // ── structured ────────────────────────────────────────────────
                .toolCall(
                        McpSchema.Tool.builder("structured", structuredInputSchema())
                                .description("Returns structuredContent per its outputSchema")
                                .outputSchema(structuredOutputSchema())
                                .build(),
                        (exchange, req) -> {
                            sink.record("structured", req.arguments());
                            String msg = String.valueOf(req.arguments().get("msg"));
                            Map<String, Object> sc = new LinkedHashMap<>();
                            sc.put("echoed", msg);
                            return McpSchema.CallToolResult.builder()
                                    .addTextContent(msg)
                                    .structuredContent(sc)
                                    .build();
                        })
                // ── boom ──────────────────────────────────────────────────────
                .toolCall(
                        McpSchema.Tool.builder("boom", openSchema())
                                .description("Always throws")
                                .build(),
                        (exchange, req) -> {
                            sink.record("boom", req.arguments());
                            throw new RuntimeException("boom");
                        })
                .build();
    }

    // ── Schema helpers ────────────────────────────────────────────────────────

    private static Map<String, Object> echoSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("msg", Map.of("type", "string", "description", "Message to echo"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("msg"));
        return schema;
    }

    /** Open schema — NO {@code additionalProperties: false}; unknown keys survive. */
    private static Map<String, Object> looseSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("msg", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        return schema;
    }

    /** Strict schema — {@code additionalProperties: false}; AgentCat removes it before injection. */
    private static Map<String, Object> strictSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("msg", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("msg"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> structuredInputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("msg", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("msg"));
        return schema;
    }

    private static Map<String, Object> structuredOutputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("echoed", Map.of("type", "string", "description", "The echoed message"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("echoed"));
        return schema;
    }

    /** Fully open schema — accepts any keys. Used by boom. */
    private static Map<String, Object> openSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        return schema;
    }
}
