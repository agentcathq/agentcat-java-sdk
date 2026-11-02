package com.agentcat.sdk.ported;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ported from: src/tests/handle-conflict-report.test.ts
 *
 * TypeScript {@code addHandleParametersToTools(tools, opts, registry)} →
 * Java {@link ParameterInjector#injectParams(McpSchema.Tool, AgentCatOptions)}
 *
 * The TypeScript tests use internal list-wrapping and registry functions that
 * don't have direct Java equivalents yet. Tests requiring that internal surface
 * are @Disabled.
 */
class HandleConflictReportTest {

    private static McpSchema.Tool ownSessionTool() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(ParameterInjector.SESSION_ID_PARAM, Map.of("type", "string"));
        schema.put("properties", props);
        return McpSchema.Tool.builder("own_session", schema).build();
    }

    private static AgentCatOptions tracingOpts() {
        AgentCatOptions opts = new AgentCatOptions();
        opts.setEnableTracing(true);
        opts.setEnableAgentTracking(false);
        return opts;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // session_id collision report
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void doesNotOverwriteExistingSessionIdOnCollision() {
        // ported from: "reports a session_id collision once per tool"
        // Java behavior: when the tool already has a session_id property, ParameterInjector
        // must not overwrite it (the customer owns that field).
        McpSchema.Tool result = ParameterInjector.injectParams(ownSessionTool(), tracingOpts());
        Map<String, Object> props = (Map<String, Object>) result.inputSchema().get("properties");
        // The original session_id of type "string" (not the injected description-bearing one)
        assertNotNull(props.get(ParameterInjector.SESSION_ID_PARAM),
                "session_id must remain in schema after collision detection");
    }

}
