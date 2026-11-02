package com.agentcat.sdk.modules.handles;

import com.agentcat.sdk.config.AgentCatConstants;
import com.agentcat.sdk.model.HandleResolution;
import com.agentcat.sdk.model.SessionSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the set of SDK-internal event tags derived from resolved handle parameters.
 *
 * <p>These tags are stamped on every analytics event and bypass the 50-entry customer
 * tag limit. They are always added last, overriding any customer tag with the same key.
 *
 * <h2>Tags produced</h2>
 * <ul>
 *   <li>{@code agentcat_session_id_source} — how the session was obtained:
 *       {@code "minted"}, {@code "supplied"}, {@code "hook"}, or {@code "invalid"}.</li>
 *   <li>{@code agentcat_agent_id} — the agent ID string, when the agent supplied one.</li>
 *   <li>{@code agentcat_agent_id_source} — {@code "supplied"} when the agent provided an ID.</li>
 *   <li>{@code agentcat_protocol_version} — SEP version string (e.g. {@code "SEP-2567"}).</li>
 * </ul>
 */
public final class HandleTags {

    /** How the session was obtained: {@code "minted"}, {@code "supplied"}, {@code "hook"}, {@code "invalid"}, or {@code "foreign"}. */
    public static final String SESSION_ID_SOURCE_TAG = "agentcat_session_id_source";
    /** The agent-supplied {@code agent_id} value, present only when the agent provided one. */
    public static final String AGENT_ID_TAG          = "agentcat_agent_id";
    /** {@code "supplied"} when the agent provided an agent ID; absent otherwise. */
    public static final String AGENT_ID_SOURCE_TAG   = "agentcat_agent_id_source";
    /** SEP version string (e.g. {@code "SEP-2567"}) stamped on every event. */
    public static final String PROTOCOL_VERSION_TAG  = "agentcat_protocol_version";

    private HandleTags() {}

    /**
     * Returns a map of SDK handle tags for {@code handles}.
     * The returned map is mutable and non-null.
     */
    public static Map<String, String> buildHandleTags(HandleResolution handles) {
        Map<String, String> tags = new LinkedHashMap<>();

        // Session ID source
        String sessionSource = switch (handles.getSessionSource()) {
            case MINTED    -> "minted";
            case SUPPLIED  -> "supplied";
            case HOOK_MODE -> "hook";
            case INVALID   -> "invalid";
            case FOREIGN   -> "foreign";
        };
        tags.put(SESSION_ID_SOURCE_TAG, sessionSource);

        // Agent ID (when supplied)
        if (handles.getAgentId() != null) {
            tags.put(AGENT_ID_TAG, handles.getAgentId());
        }
        if (handles.getAgentSource() != null) {
            tags.put(AGENT_ID_SOURCE_TAG, handles.getAgentSource());
        }

        // Protocol version
        tags.put(PROTOCOL_VERSION_TAG, AgentCatConstants.PROTOCOL_VERSION);

        return tags;
    }
}
