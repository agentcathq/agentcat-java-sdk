package com.agentcat.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AgentCat, bound from {@code agentcat.*} in
 * {@code application.yml} / {@code application.properties}.
 *
 * <p>Minimal example:
 * <pre>{@code
 * agentcat:
 *   project-id: proj_xxx
 * }</pre>
 *
 * <p>Full example:
 * <pre>{@code
 * agentcat:
 *   project-id: proj_xxx
 *   enable-agent-tracking: true
 *   enable-tool-call-context: true
 * }</pre>
 */
@ConfigurationProperties(prefix = "agentcat")
public class AgentCatProperties {

    /**
     * AgentCat project ID (required). Found in the AgentCat dashboard.
     * When absent, auto-configuration is skipped.
     */
    private String projectId;

    /**
     * When {@code true}, an {@code agent_id} parameter is injected into every
     * tool's input schema. Default: {@code false}.
     */
    private boolean enableAgentTracking = false;

    /**
     * When {@code true}, a {@code context} parameter is injected into every
     * tool's input schema to capture agent intent. Default: {@code true}.
     */
    private boolean enableToolCallContext = true;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public boolean isEnableAgentTracking() { return enableAgentTracking; }
    public void setEnableAgentTracking(boolean enableAgentTracking) {
        this.enableAgentTracking = enableAgentTracking;
    }

    public boolean isEnableToolCallContext() { return enableToolCallContext; }
    public void setEnableToolCallContext(boolean enableToolCallContext) {
        this.enableToolCallContext = enableToolCallContext;
    }
}
