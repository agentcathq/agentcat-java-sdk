package com.agentcat.sdk.modules.handles;

import com.agentcat.sdk.AgentCatOptions;
import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;
import com.agentcat.sdk.model.HandleResolution;
import com.agentcat.sdk.model.SessionSource;

import java.util.Map;

/**
 * Resolves AgentCat handle parameters ({@code session_id}, {@code agent_id}) from the raw
 * arguments of an incoming tool call.
 *
 * <h3>session_id resolution scenarios</h3>
 * <ol>
 *   <li>Absent or {@code "start"} (case-insensitive) → {@link SessionSource#MINTED}: fresh KSUID.</li>
 *   <li>Valid {@code ses_XXXXX} → {@link SessionSource#SUPPLIED}: echoed back as-is.</li>
 *   <li>Anything else → {@link SessionSource#INVALID}: empty string (sessionless event), warning logged.</li>
 *   <li>{@code sessionParamIsOurs=false} → {@link SessionSource#FOREIGN}: tool declares its own {@code session_id}.</li>
 * </ol>
 */
public final class SessionManager {

    private SessionManager() {}

    /**
     * Resolves handle parameters from a tool call's raw argument map.
     * Assumes the session_id parameter is ours (not declared by the customer's tool).
     *
     * @param args     raw arguments received from the agent (may be null or empty)
     * @param toolName tool name, used only for log messages
     * @param opts     AgentCat runtime options
     * @return a {@link HandleResolution} with a non-null, always-valid {@code sessionId}
     */
    public static HandleResolution resolveHandles(Map<String, Object> args,
                                                   String toolName,
                                                   AgentCatOptions opts) {
        return resolveHandles(args, toolName, opts, true);
    }

    /**
     * Resolves handle parameters from a tool call's raw argument map.
     *
     * @param args              raw arguments received from the agent (may be null or empty)
     * @param toolName          tool name, used only for log messages
     * @param opts              AgentCat runtime options
     * @param sessionParamIsOurs {@code false} when the tool declares its own {@code session_id}
     *                           (foreign tool — AgentCat never injected one)
     * @return a {@link HandleResolution} with a non-null, always-valid {@code sessionId}
     */
    public static HandleResolution resolveHandles(Map<String, Object> args,
                                                   String toolName,
                                                   AgentCatOptions opts,
                                                   boolean sessionParamIsOurs) {
        String rawSessionId = null;
        String agentId      = null;

        if (args != null) {
            if (args.get(ParameterInjector.SESSION_ID_PARAM) instanceof String s) {
                rawSessionId = s;
            }
            if (opts.isEnableAgentTracking()
                    && args.get(ParameterInjector.AGENT_ID_PARAM) instanceof String a) {
                // Trim whitespace
                String trimmed = a.strip();
                agentId = trimmed.isEmpty() ? null : trimmed;
            }
        }

        // ── Resolve session_id ───────────────────────────────────────────────

        String        sessionId;
        SessionSource source;

        // Hook mode: resolveSessionId hook owns session management.
        // session_id was not injected into the schema, so the agent never sends one.
        // The actual session ID will be resolved asynchronously in the event queue Stage 0.
        if (opts.getResolveSessionId() != null) {
            return new HandleResolution("", SessionSource.HOOK_MODE, agentId);
        }

        // Foreign: the tool declares its own session_id. AgentCat never injected one here,
        // so nothing in the arguments is ours to read. Sessionless until the customer adopts
        // resolveSessionId. Mirrors TypeScript SDK handles.ts.
        if (!sessionParamIsOurs) {
            return new HandleResolution("", SessionSource.FOREIGN, agentId);
        }

        if (rawSessionId == null
                || rawSessionId.equalsIgnoreCase(SessionIdProvider.START_SENTINEL)) {
            sessionId = SessionIdProvider.newSessionId();
            source    = SessionSource.MINTED;

        } else if (SessionIdProvider.isValid(rawSessionId)) {
            sessionId = rawSessionId;
            source    = SessionSource.SUPPLIED;

        } else {
            writeToLog(
                    "WARN: tool \"" + toolName + "\" received invalid session_id \""
                    + rawSessionId + "\" — publishing sessionless");
            // Mirror TypeScript: publish sessionless rather than adopt an unknown ID.
            // Event.sessionId is exempt from redaction hooks, so we must not mint a
            // surrogate session that the caller never issued.
            sessionId = "";
            source    = SessionSource.INVALID;
        }

        return new HandleResolution(sessionId, source, agentId);
    }
}
