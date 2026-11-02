package com.agentcat.sdk.engine;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * Wraps tool specifications registered on a stateful MCP server
 * ({@code McpSyncServer} / {@code McpAsyncServer}).
 *
 * <p>Create one instance per {@code track()} call and reuse it to wrap all tools on that server:
 * <pre>{@code
 * StatefulToolCallWrapper wrapper = new StatefulToolCallWrapper(projectId, serverName, serverVersion, opts);
 * AsyncToolSpecification wrapped = wrapper.wrap(original);
 * }</pre>
 *
 * @see StatelessToolCallWrapper for stateless servers
 */
public final class StatefulToolCallWrapper extends ToolCallWrapper {

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
    public StatefulToolCallWrapper(String projectId, String serverName,
                                   String serverVersion, AgentCatOptions opts) {
        super(projectId, serverName, serverVersion, opts);
    }

    /**
     * Returns a new {@link AsyncToolSpecification} that wraps {@code spec} with AgentCat
     * analytics: injects handle parameters into the tool schema and installs a
     * {@link StatefulCallHandler} as the call handler.
     */
    public AsyncToolSpecification wrap(AsyncToolSpecification spec) {
        McpSchema.Tool injectedTool = ParameterInjector.injectParams(spec.tool(), opts, registry, declaredSessionParams);
        injectedTool = ParameterInjector.addMcpSessionToOutputSchema(injectedTool, opts, outputRegistry);
        StatefulCallHandler handler = new StatefulCallHandler(spec, projectId, serverName, serverVersion, opts, registry, declaredSessionParams, outputRegistry);
        return buildSpec(injectedTool, handler);
    }

    /**
     * Delegates to {@link AsyncToolSpecFactory#buildSpec} to ensure {@code handler} is placed
     * in the {@code callHandler} slot across all SDK versions (0.18.x, 1.x, 2.x).
     */
    private static AsyncToolSpecification buildSpec(McpSchema.Tool tool,
                                                     StatefulCallHandler handler) {
        return AsyncToolSpecFactory.buildSpec(tool, handler);
    }

    /**
     * Returns {@code true} if {@code spec} has already been wrapped by this class.
     * Used by {@link StatefulToolList} to prevent double-wrapping.
     */
    public static boolean isWrapped(AsyncToolSpecification spec) {
        return spec.callHandler() instanceof StatefulCallHandler;
    }

    /**
     * Creates a {@link StatefulToolList} pre-populated with {@code tools}.
     * Any tool added to the list afterwards is automatically wrapped.
     */
    public StatefulToolList newToolList(List<AsyncToolSpecification> tools) {
        return new StatefulToolList(tools, this);
    }
}
