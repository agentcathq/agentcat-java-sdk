package com.agentcat.sdk.engine;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * Wraps tool specifications registered on a stateless MCP server
 * ({@code McpStatelessSyncServer} / {@code McpStatelessAsyncServer}).
 *
 * <p>Create one instance per {@code track()} call and reuse it to wrap all tools on that server:
 * <pre>{@code
 * StatelessToolCallWrapper wrapper = new StatelessToolCallWrapper(projectId, serverName, serverVersion, opts);
 * AsyncToolSpecification wrapped = wrapper.wrap(original);
 * }</pre>
 *
 * @see StatefulToolCallWrapper for stateful servers
 */
public final class StatelessToolCallWrapper extends ToolCallWrapper {

    /** Tracks which AgentCat parameters were injected per tool — shared across all wraps on this server. */
    private final InjectedParamsRegistry registry = new InjectedParamsRegistry();
    /** Tracks tools whose schema declares its own {@code session_id} (customer's, not ours). */
    private final DeclaredSessionParamsRegistry declaredSessionParams = new DeclaredSessionParamsRegistry();
    /** Tracks tools whose outputSchema was extended with mcp_session. */
    private final OutputInjectionRegistry outputRegistry = new OutputInjectionRegistry();

    /**
     * @param projectId     AgentCat project identifier
     * @param serverName    MCP server name (reported in analytics events)
     * @param serverVersion MCP server version (reported in analytics events)
     * @param opts          runtime SDK options
     */
    public StatelessToolCallWrapper(String projectId, String serverName,
                                    String serverVersion, AgentCatOptions opts) {
        super(projectId, serverName, serverVersion, opts);
    }

    /**
     * Returns a new {@link AsyncToolSpecification} that wraps {@code spec} with AgentCat
     * analytics: injects handle parameters into the tool schema and installs a
     * {@link StatelessCallHandler} as the call handler.
     */
    public AsyncToolSpecification wrap(AsyncToolSpecification spec) {
        McpSchema.Tool injectedTool = ParameterInjector.injectParams(spec.tool(), opts, registry, declaredSessionParams);
        injectedTool = ParameterInjector.addMcpSessionToOutputSchema(injectedTool, opts, outputRegistry);
        return new AsyncToolSpecification(injectedTool,
                new StatelessCallHandler(spec, projectId, serverName, serverVersion, opts, registry, declaredSessionParams, outputRegistry));
    }

    /**
     * Returns {@code true} if {@code spec} has already been wrapped by this class.
     * Used by {@link StatelessToolList} to prevent double-wrapping.
     */
    public static boolean isWrapped(AsyncToolSpecification spec) {
        return spec.callHandler() instanceof StatelessCallHandler;
    }

    /**
     * Creates a {@link StatelessToolList} pre-populated with {@code tools}.
     * Any tool added to the list afterwards is automatically wrapped.
     */
    public StatelessToolList newToolList(List<AsyncToolSpecification> tools) {
        return new StatelessToolList(tools, this);
    }
}
