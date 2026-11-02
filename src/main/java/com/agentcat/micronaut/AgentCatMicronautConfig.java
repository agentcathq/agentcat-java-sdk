package com.agentcat.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.enterprise.inject.Vetoed;

/**
 * Micronaut configuration properties for AgentCat, bound from
 * {@code agentcat.*} in {@code application.yml} / {@code application.properties}.
 *
 * <p>Example:
 * <pre>{@code
 * agentcat:
 *   project-id: proj_xxx
 *   enable-agent-tracking: false
 *   enable-tool-call-context: true
 * }</pre>
 */
@Vetoed // Prevent CDI containers (Quarkus) from processing this Micronaut bean
@ConfigurationProperties("agentcat")
public class AgentCatMicronautConfig {

    private String projectId;
    private boolean enableAgentTracking = false;
    private boolean enableToolCallContext = true;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public boolean isEnableAgentTracking() { return enableAgentTracking; }
    public void setEnableAgentTracking(boolean v) { this.enableAgentTracking = v; }

    public boolean isEnableToolCallContext() { return enableToolCallContext; }
    public void setEnableToolCallContext(boolean v) { this.enableToolCallContext = v; }
}
