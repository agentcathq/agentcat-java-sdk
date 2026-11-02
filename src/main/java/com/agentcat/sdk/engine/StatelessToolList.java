package com.agentcat.sdk.engine;

import com.agentcat.sdk.AgentCatOptions;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link CopyOnWriteArrayList} of stateless tool specifications that automatically wraps
 * any tool added after {@code track()} has been called.
 *
 * <p>Installed via reflection on the MCP server's internal tools list so that calls to
 * {@code server.addTool()} made after {@code AgentCat.track()} still receive AgentCat
 * instrumentation without requiring any extra setup from the user.
 */
public class StatelessToolList extends CopyOnWriteArrayList<AsyncToolSpecification> {

    private final StatelessToolCallWrapper wrapper;

    StatelessToolList(List<AsyncToolSpecification> tools, StatelessToolCallWrapper wrapper) {
        super(tools);
        this.wrapper = wrapper;
    }

    /** Returns the {@link com.agentcat.sdk.AgentCatOptions} this list was configured with. */
    public AgentCatOptions getOptions() { return wrapper.getOptions(); }

    /** Wraps {@code spec} before adding it, unless it is already wrapped. */
    @Override
    public boolean add(AsyncToolSpecification spec) {
        if (!StatelessToolCallWrapper.isWrapped(spec)) {
            spec = wrapper.wrap(spec);
        }
        return super.add(spec);
    }
}
