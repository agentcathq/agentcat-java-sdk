package com.agentcat.sdk.model;


/**
 * Result of resolving the AgentCat handle parameters for a single tool call.
 */
public class HandleResolution {

    private String sessionId;
    private SessionSource sessionSource;
    private String agentId;
    /** {@code "supplied"} when the agent provided an {@code agent_id}; {@code null} otherwise. */
    private String agentSource;

    public HandleResolution(String sessionId, SessionSource sessionSource,
                            String agentId, String agentSource) {
        this.sessionId     = sessionId;
        this.sessionSource = sessionSource;
        this.agentId       = agentId;
        this.agentSource   = agentSource;
    }

    /** Convenience constructor — no agent-source information. */
    public HandleResolution(String sessionId, SessionSource sessionSource, String agentId) {
        this(sessionId, sessionSource, agentId, agentId != null ? "supplied" : null);
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public SessionSource getSessionSource() {
        return sessionSource;
    }

    public void setSessionSource(SessionSource sessionSource) {
        this.sessionSource = sessionSource;
    }

    public String getAgentId() {
        return agentId;
    }

    /** Sets the agent ID and automatically updates {@code agentSource} to {@code "supplied"} (or {@code null}). */
    public void setAgentId(String agentId) {
        this.agentId = agentId;
        this.agentSource = agentId != null ? "supplied" : null;
    }

    public String getAgentSource() {
        return agentSource;
    }

    public void setAgentSource(String agentSource) {
        this.agentSource = agentSource;
    }
}
