package com.agentcat.sdk.modules.handles;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.engine.DeclaredSessionParamsRegistry;
import com.agentcat.sdk.engine.InjectedParamsRegistry;
import com.agentcat.sdk.engine.McpSdkDetector;
import com.agentcat.sdk.engine.OutputInjectionRegistry;
import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;
import com.agentcat.sdk.tools.GetMoreTools;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Injects AgentCat handle parameters into a tool's JSON Schema input definition.
 *
 * <h2>Parameters injected</h2>
 * <ul>
 *   <li>{@code session_id} — always, when tracing is enabled. Carries a pattern
 *       constraint so agents know the accepted format.</li>
 *   <li>{@code agent_id} — only when {@link AgentCatOptions#enableAgentTracking} is set.</li>
 *   <li>{@code context} — only when {@link AgentCatOptions#enableToolCallContext} is set,
 *       and never for {@code get_more_tools} which already owns a {@code context} field.</li>
 * </ul>
 *
 * <h3>Safety rules</h3>
 * <ul>
 *   <li>Always returns a new {@link McpSchema.Tool} — the original is never mutated.</li>
 *   <li>Name collision: if the tool already declares a parameter with the same name,
 *       injection for that parameter is skipped and a warning is logged.</li>
 *   <li>{@code additionalProperties: false}: removed from the copy so injected parameters
 *       are not rejected by schema validators on the agent side.</li>
 * </ul>
 *
 * <p><b>Implementation note:</b> In MCP Java SDK 2.0.1, {@code McpSchema.Tool.inputSchema()}
 * returns {@code Map<String, Object>} (raw JSON schema). We manipulate it as a plain map
 * and rebuild the schema via {@link McpSchema.JsonSchema#builder()}.
 */
public final class ParameterInjector {

    // ── Parameter names ──────────────────────────────────────────────────────

    /** JSON Schema property name injected for session tracking; also used as the strip key in {@code ToolCallWrapper}. */
    public static final String SESSION_ID_PARAM = "session_id";
    /** JSON Schema property name injected for agent identity; also used as the strip key. */
    public static final String AGENT_ID_PARAM   = "agent_id";
    /** JSON Schema property name injected for intent capture; also used as the strip key. */
    public static final String CONTEXT_PARAM    = "context";

    // ── Validation pattern ───────────────────────────────────────────────────

    /**
     * JSON Schema pattern for {@code session_id}.
     * Accepts the sentinel {@code "start"} (mint a new session) or a valid
     * SDK-issued KSUID: {@code ses_} followed by exactly 27 base-62 characters.
     */
    public static final String SESSION_ID_PATTERN = "^(start|ses_[0-9A-Za-z]{27})$";

    // ── Descriptions (verbatim from TypeScript SDK constants.ts) ─────────────

    private static final String SESSION_ID_DESCRIPTION =
            "Session continuity handle, one of two values: the ses_ ID issued for the task " +
            "underway, or start. This server cannot link your calls between requests on its own, " +
            "so session continuity travels in this parameter instead. If you were handed a " +
            "session_id for this task \u2014 for example by the agent that spawned you \u2014 " +
            "send that exact value from your first call. Otherwise send start on your first call; " +
            "the server will issue an opaque correlation ID in the mcp_session field of the result, " +
            "or in a text block at the start of the result beginning [session_id issued. Then send " +
            "that exact ses_ value on every later call and hand it to any subagents working the " +
            "same task. start always begins a new, unrelated task \u2014 never send it mid-task. " +
            "If you send a value this server does not recognize, the result reports it: " +
            "mcp_session.status of unrecognized, or a text block beginning [session_id " +
            "unrecognized; re-send the ID issued for this task, or start if none was issued yet. " +
            "Never invent a ses_ value.";

    private static final String AGENT_ID_DESCRIPTION =
            "Agent identity handle, required on every call including your first. This server " +
            "cannot tell concurrent agents apart on its own; agent_id is how your calls are " +
            "attributed to you. It is a self-chosen identifier in the spirit of a User-Agent " +
            "string \u2014 join your model version, your harness name, and a short suffix of " +
            "4-6 letters or digits, with '|'. Example: opus-4.80-1m|claude-code|k3n9x. Choose " +
            "the suffix once at the start of your task and send that same exact value on every " +
            "call for the entire task; never change it mid-task, and a new task gets a fresh " +
            "suffix. agent_id identifies exactly one agent and is never inherited: a subagent " +
            "you spawn generates a new one rather than carrying yours, and if you were spawned " +
            "by another agent, generate your own rather than reusing your parent's. A call " +
            "without agent_id cannot be attributed to you.";

    private static final String CONTEXT_DESCRIPTION =
            "Explain why you are calling this tool and how it fits into the user's overall goal. " +
            "This parameter is used for analytics and user intent tracking. YOU MUST provide " +
            "15-25 words (count carefully). NEVER use first person ('I', 'we', 'you') - maintain " +
            "third-person perspective. NEVER include sensitive information such as credentials, " +
            "passwords, or personal data. Example (20 words): \"Searching across the " +
            "organization's repositories to find all open issues related to performance complaints " +
            "and latency issues for team prioritization.\"";

    private ParameterInjector() {}

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns a copy of {@code tool} with handle parameters injected into its input schema
     * according to the supplied options. The original tool is never modified.
     *
     * @param tool    the original tool specification
     * @param opts    AgentCat runtime options controlling which parameters are injected
     * @return modified tool, or the original if tracing is disabled or nothing to inject
     */
    public static McpSchema.Tool injectParams(McpSchema.Tool tool, AgentCatOptions opts) {
        return injectParams(tool, opts, null, null);
    }

    /**
     * Returns a copy of {@code tool} with handle parameters injected into its input schema.
     * Successfully injected parameter names are recorded in {@code registry} (if non-null)
     * so that {@code ToolCallWrapper.stripHandleArgs} only strips what was actually injected.
     *
     * @param tool     the original tool specification
     * @param opts     AgentCat runtime options controlling which parameters are injected
     * @param registry optional registry to record successful injections; may be {@code null}
     * @return modified tool, or the original if tracing is disabled or nothing to inject
     */
    public static McpSchema.Tool injectParams(McpSchema.Tool tool, AgentCatOptions opts,
                                               InjectedParamsRegistry registry) {
        return injectParams(tool, opts, registry, null);
    }

    /**
     * Returns a copy of {@code tool} with handle parameters injected into its input schema.
     *
     * @param tool                  the original tool specification
     * @param opts                  AgentCat runtime options controlling which parameters are injected
     * @param registry              optional registry to record successful injections; may be {@code null}
     * @param declaredSessionParams optional registry to record tools that declare their own {@code session_id};
     *                              may be {@code null}
     * @return modified tool, or the original if tracing is disabled or nothing to inject
     */
    public static McpSchema.Tool injectParams(McpSchema.Tool tool, AgentCatOptions opts,
                                               InjectedParamsRegistry registry,
                                               DeclaredSessionParamsRegistry declaredSessionParams) {
        if (registry != null) {
            registry.markRegistered(tool.name());
        }

        // enableTracing gates only handle injection (session_id, agent_id).
        // Context injection is controlled independently by enableToolCallContext —
        // matches TypeScript SDK behaviour in listWrap.ts.
        boolean tracingEnabled = opts.isEnableTracing();

        // In hook mode the customer owns session management — don't inject session_id
        boolean injectSessionId = tracingEnabled && opts.getResolveSessionId() == null;
        boolean injectAgentId   = tracingEnabled && opts.isEnableAgentTracking();
        // get_more_tools already owns its context field — never inject on top of it
        boolean injectContext   = opts.isEnableToolCallContext()
                && !GetMoreTools.GET_MORE_TOOLS_NAME.equals(tool.name());

        if (!injectSessionId && !injectAgentId && !injectContext) {
            return tool;
        }

        String contextDesc = opts.getCustomContextDescription() != null
                ? opts.getCustomContextDescription() : CONTEXT_DESCRIPTION;

        return applyInjection(tool, injectSessionId, injectAgentId, injectContext, contextDesc, registry, declaredSessionParams);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private record SchemaState(
            String type,
            Map<String, Object> props,
            List<String> required,
            boolean hadAdditionalPropertiesFalse) {}

    private static McpSchema.Tool applyInjection(
            McpSchema.Tool tool,
            boolean injectSessionId,
            boolean injectAgentId,
            boolean injectContext,
            String contextDesc,
            InjectedParamsRegistry registry,
            DeclaredSessionParamsRegistry declaredSessionParams) {

        Map<String, Object> rawSchema = McpSdkDetector.bridge().getInputSchema(tool);

        SchemaState state = readSchemaState(rawSchema, tool.name());
        if (state == null) {
            // Complex schema (oneOf/allOf/anyOf) — injection is skipped, but ownership still
            // has to be recorded: a schema that composes AND declares session_id at its root is
            // the customer's parameter, not ours to read at call time.
            if (injectSessionId && rawSchema != null) {
                Object props = rawSchema.get("properties");
                if (props instanceof Map<?, ?> propsMap && propsMap.containsKey(SESSION_ID_PARAM)) {
                    if (declaredSessionParams != null) declaredSessionParams.add(tool.name());
                }
            }
            return tool;
        }

        Map<String, Object> newProps    = new LinkedHashMap<>(state.props());
        List<String>        newRequired = new ArrayList<>(state.required());

        if (injectSessionId) injectOne(tool.name(), newProps, newRequired, SESSION_ID_PARAM,
                Map.of("type", "string", "description", SESSION_ID_DESCRIPTION, "pattern", SESSION_ID_PATTERN),
                registry, declaredSessionParams);
        if (injectAgentId)   injectOne(tool.name(), newProps, newRequired, AGENT_ID_PARAM,
                Map.of("type", "string", "description", AGENT_ID_DESCRIPTION),
                registry, null);
        if (injectContext)   injectOne(tool.name(), newProps, newRequired, CONTEXT_PARAM,
                Map.of("type", "string", "description", contextDesc),
                registry, null);

        if (newProps.equals(state.props()) && newRequired.equals(state.required())) {
            return tool; // every injection was a collision — nothing changed
        }

        Map<String, Object> newSchemaMap = buildSchemaMap(rawSchema, state.type(), newProps, newRequired);

        return McpSdkDetector.bridge().rebuildTool(tool, newSchemaMap);
    }

    @SuppressWarnings("unchecked")
    private static SchemaState readSchemaState(Map<String, Object> rawSchema, String toolName) {
        if (rawSchema == null) {
            return new SchemaState("object", Map.of(), List.of(), false);
        }
        if (rawSchema.containsKey("oneOf") || rawSchema.containsKey("allOf") || rawSchema.containsKey("anyOf")) {
            writeToLog("WARN: Tool \"" + toolName + "\" has complex schema (oneOf/allOf/anyOf). Skipping handle injection.");
            return null;
        }
        String              type     = rawSchema.get("type") instanceof String s     ? s                      : "object";
        Map<String, Object> props    = rawSchema.get("properties") instanceof Map<?,?> m ? (Map<String, Object>) m : Map.of();
        List<String>        required = rawSchema.get("required") instanceof List<?> l    ? (List<String>) l        : List.of();
        boolean             noExtra  = Boolean.FALSE.equals(rawSchema.get("additionalProperties"));
        return new SchemaState(type, props, required, noExtra);
    }

    private static Map<String, Object> buildSchemaMap(
            Map<String, Object> rawSchema,
            String type,
            Map<String, Object> props,
            List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        schema.put("properties", props);
        if (!required.isEmpty()) schema.put("required", required);
        // Preserve any other top-level keys (e.g. $defs, definitions) except the ones we own
        if (rawSchema != null) {
            for (Map.Entry<?, ?> entry : rawSchema.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!key.equals("type") && !key.equals("properties")
                        && !key.equals("required") && !key.equals("additionalProperties")) {
                    schema.put(key, entry.getValue());
                }
            }
        }
        return schema;
    }

    /**
     * Returns a copy of {@code tool} with an optional {@code mcp_session} property injected
     * into its declared {@code outputSchema}, so agents can read the session handle from the
     * structured output without parsing text content.
     *
     * <p>Injection is skipped when:
     * <ul>
     *   <li>The tool has no {@code outputSchema}.</li>
     *   <li>The output schema uses composition keywords ({@code oneOf}, {@code allOf},
     *       {@code anyOf}) — these schemas cannot be safely extended.</li>
     *   <li>The output schema already declares {@code mcp_session} (collision guard).</li>
     * </ul>
     *
     * <p>The injected property is optional (not added to {@code required}).
     */
    @SuppressWarnings("unchecked")
    public static McpSchema.Tool addMcpSessionToOutputSchema(McpSchema.Tool tool, AgentCatOptions opts) {
        return addMcpSessionToOutputSchema(tool, opts, null);
    }

    /**
     * Returns a copy of {@code tool} with an optional {@code mcp_session} property injected
     * into its declared {@code outputSchema}. Records the tool in {@code outputRegistry}
     * (if non-null) so call-time structured mint-back is gated correctly.
     */
    @SuppressWarnings("unchecked")
    public static McpSchema.Tool addMcpSessionToOutputSchema(McpSchema.Tool tool, AgentCatOptions opts,
                                                               OutputInjectionRegistry outputRegistry) {
        Map<String, Object> outputSchema = tool.outputSchema();
        if (outputSchema == null) {
            return tool;
        }

        // Skip schemas that use composition keywords — cannot be safely extended
        if (outputSchema.containsKey("oneOf") || outputSchema.containsKey("allOf") || outputSchema.containsKey("anyOf")) {
            writeToLog("WARN: Tool \"" + tool.name() + "\" has complex outputSchema (oneOf/allOf/anyOf). Skipping mcp_session injection; mint-back stays content-only for this tool.");
            return tool;
        }

        Object propsRaw = outputSchema.get("properties");
        Map<String, Object> existingProps = propsRaw instanceof Map<?, ?>
                ? (Map<String, Object>) propsRaw : Map.of();

        // Collision guard
        if (existingProps.containsKey("mcp_session")) {
            writeToLog("WARN: Tool \"" + tool.name() + "\" already declares 'mcp_session' in outputSchema. Skipping injection.");
            return tool;
        }

        boolean injectSessionId = opts.getResolveSessionId() == null;
        boolean injectAgentId   = opts.isEnableAgentTracking();

        if (!injectSessionId && !injectAgentId) {
            return tool;
        }

        // Build mcp_session sub-properties — order mirrors TypeScript: session_id, agent_id, status
        Map<String, Object> subProperties = new LinkedHashMap<>();
        if (injectSessionId) {
            subProperties.put(SESSION_ID_PARAM, Map.of("type", "string", "description", SESSION_ID_DESCRIPTION));
        }
        if (injectAgentId) {
            subProperties.put(AGENT_ID_PARAM, Map.of("type", "string", "description", AGENT_ID_DESCRIPTION));
        }
        if (injectSessionId) {
            subProperties.put("status", Map.of(
                    "type", "string",
                    "enum", List.of("issued", "active", "unrecognized")));
        }

        Map<String, Object> mcpSessionProp = new LinkedHashMap<>();
        mcpSessionProp.put("type", "object");
        mcpSessionProp.put("properties", subProperties);

        Map<String, Object> newOutputSchema = new LinkedHashMap<>(outputSchema);
        Map<String, Object> newProps = new LinkedHashMap<>(existingProps);
        newProps.put("mcp_session", mcpSessionProp);
        newOutputSchema.put("properties", newProps);

        if (outputRegistry != null) {
            outputRegistry.add(tool.name());
        }

        return McpSdkDetector.bridge().withOutputSchema(tool, newOutputSchema);
    }

    /**
     * Adds one parameter to the schema's {@code properties} and {@code required} array.
     * Logs a warning and skips on collision. Records successful injections in {@code registry}
     * (if non-null) so that stripping at call time only removes what was actually injected.
     * When collision is on {@code session_id}, records the tool in {@code declaredSessionParams}
     * so that call-time resolution never reads the customer's value as an AgentCat handle.
     */
    private static void injectOne(
            String toolName,
            Map<String, Object> props,
            List<String> required,
            String paramName,
            Map<String, Object> paramSchema,
            InjectedParamsRegistry registry,
            DeclaredSessionParamsRegistry declaredSessionParams) {

        if (props.containsKey(paramName)) {
            // The customer owns this name on this tool: record it so call-time
            // resolution never reads their value as an AgentCat handle.
            if (SESSION_ID_PARAM.equals(paramName) && declaredSessionParams != null) {
                declaredSessionParams.add(toolName);
            }
            writeToLog(
                    "WARN: tool \"" + toolName + "\" already declares \"" + paramName
                    + "\" — skipping AgentCat injection for this parameter");
            return;
        }

        props.put(paramName, paramSchema);
        if (!required.contains(paramName)) {
            required.add(paramName);
        }
        if (registry != null) {
            registry.record(toolName, paramName);
        }
    }
}
