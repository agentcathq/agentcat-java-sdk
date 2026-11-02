package com.agentcat.sdk;

import com.agentcat.sdk.engine.ReflectionUtils;
import com.agentcat.sdk.engine.StatefulToolCallWrapper;
import com.agentcat.sdk.engine.StatefulToolList;
import com.agentcat.sdk.engine.StatelessToolCallWrapper;
import com.agentcat.sdk.engine.StatelessToolList;

import static com.agentcat.sdk.engine.ReflectionUtils.writeField;
import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;
import com.agentcat.sdk.model.EventData;
import com.agentcat.sdk.model.PendingHooks;
import com.agentcat.sdk.model.QueuedEvent;
import com.agentcat.sdk.model.ServerContext;
import com.agentcat.sdk.modules.events.EventQueue;
import com.agentcat.sdk.modules.events.TagValidator;
import com.agentcat.sdk.tools.GetMoreTools;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentCat Java SDK — entry point.
 */
public final class AgentCat {

    /** Default resource name used by {@link #publishCustomEvent} when {@code eventData.resourceName} is absent. */
    private static final String DEFAULT_RESOURCE_NAME = "custom";

    private AgentCat() {}

    // ── Sync server ─────────────────────────────────────────────────────────

    /**
     * Attaches AgentCat analytics to a synchronous MCP server with default options.
     */
    public static McpSyncServer track(McpSyncServer server, String projectId) {
        return track(server, projectId, new AgentCatOptions());
    }

    /**
     * Attaches AgentCat analytics to a synchronous MCP server.
     *
     * @param server    a built {@link McpSyncServer}
     * @param projectId project ID from the AgentCat dashboard
     * @param opts      runtime options (injection flags, hooks, etc.)
     * @return the same server (for fluent chaining)
     */
    public static McpSyncServer track(McpSyncServer server, String projectId, AgentCatOptions opts) {
        try {
            McpSchema.Implementation info = server.getServerInfo();
            McpAsyncServer asyncServer = server.getAsyncServer();
            trackAsyncServer(
                    asyncServer, projectId,
                    name(info),
                    version(info),
                    opts
            );
        } catch (Throwable t) {
            writeToLog("Warning: Failed to track server - " + t);
        }
        return server;
    }

    // ── Async server ────────────────────────────────────────────────────────

    /**
     * Attaches AgentCat analytics to an asynchronous MCP server with default options.
     */
    public static McpAsyncServer track(McpAsyncServer server, String projectId) {
        return track(server, projectId, new AgentCatOptions());
    }

    /**
     * Attaches AgentCat analytics to an asynchronous MCP server.
     *
     * @param server    a built {@link McpAsyncServer}
     * @param projectId project ID from the AgentCat dashboard
     * @param opts      runtime options
     * @return the same server (for fluent chaining)
     */
    public static McpAsyncServer track(McpAsyncServer server, String projectId, AgentCatOptions opts) {
        try {
            McpSchema.Implementation info = server.getServerInfo();
            trackAsyncServer(
                    server,
                    projectId,
                    name(info),
                    version(info),
                    opts
            );
        } catch (Throwable t) {
            writeToLog("Warning: Failed to track server - " + t);
        }
        return server;
    }

    // ── Stateless sync server ───────────────────────────────────────────────

    /**
     * Attaches AgentCat analytics to a stateless synchronous MCP server with default options.
     */
    public static McpStatelessSyncServer track(McpStatelessSyncServer server, String projectId) {
        return track(server, projectId, new AgentCatOptions());
    }

    /**
     * Attaches AgentCat analytics to a stateless synchronous MCP server.
     *
     * @param server    a built {@link McpStatelessSyncServer}
     * @param projectId project ID from the AgentCat dashboard
     * @param opts      runtime options
     * @return the same server (for fluent chaining)
     */
    public static McpStatelessSyncServer track(McpStatelessSyncServer server, String projectId, AgentCatOptions opts) {
        try {
            McpSchema.Implementation info = server.getServerInfo();
            McpStatelessAsyncServer asyncServer = ReflectionUtils.readField(server, "asyncServer");
            trackStatelessAsyncServer(
                    asyncServer,
                    projectId,
                    name(info),
                    version(info),
                    opts
            );
        } catch (Throwable t) {
            writeToLog("Warning: Failed to track server - " + t);
        }
        return server;
    }

    // ── Stateless async server ──────────────────────────────────────────────

    /**
     * Attaches AgentCat analytics to a stateless asynchronous MCP server with default options.
     */
    public static McpStatelessAsyncServer track(McpStatelessAsyncServer server, String projectId) {
        return track(server, projectId, new AgentCatOptions());
    }

    /**
     * Attaches AgentCat analytics to a stateless asynchronous MCP server.
     *
     * @param server    a built {@link McpStatelessAsyncServer}
     * @param projectId project ID from the AgentCat dashboard
     * @param opts      runtime options
     * @return the same server (for fluent chaining)
     */
    public static McpStatelessAsyncServer track(McpStatelessAsyncServer server, String projectId, AgentCatOptions opts) {
        try {
            McpSchema.Implementation info = server.getServerInfo();
            trackStatelessAsyncServer(
                    server,
                    projectId,
                    name(info),
                    version(info),
                    opts
            );
        } catch (Throwable t) {
            writeToLog("Warning: Failed to track server - " + t);
        }
        return server;
    }

    // ── Custom event ────────────────────────────────────────────────────────

    /**
     * Publishes a custom analytics event attributed to the given tracked server.
     *
     * <p>Mirrors TypeScript {@code publishCustomEvent(server, projectId, eventData)}.
     * The event bypasses the tool-call pipeline and is submitted directly to the
     * event queue. Tags are validated; an empty {@code properties} map is treated
     * as omission.
     *
     * <p><b>Fault containment:</b> any exception is swallowed and written to the
     * log — it must never propagate to the caller.
     *
     * @param server    a server previously wrapped by {@link #track}
     * @param projectId project ID from the AgentCat dashboard
     * @param eventData optional event payload; {@code null} is accepted
     */
    public static void publishCustomEvent(McpSyncServer server, String projectId,
                                          CustomEventData eventData) {
        AgentCatOptions opts = server != null ? extractOptions(server.getAsyncServer()) : null;
        publishCustomEventCore(server != null ? server.getServerInfo() : null, projectId, eventData, opts);
    }

    /** @see #publishCustomEvent(McpSyncServer, String, CustomEventData) */
    public static void publishCustomEvent(McpAsyncServer server, String projectId,
                                          CustomEventData eventData) {
        AgentCatOptions opts = server != null ? extractOptions(server) : null;
        publishCustomEventCore(server != null ? server.getServerInfo() : null, projectId, eventData, opts);
    }

    /** @see #publishCustomEvent(McpSyncServer, String, CustomEventData) */
    public static void publishCustomEvent(McpStatelessSyncServer server, String projectId,
                                          CustomEventData eventData) {
        AgentCatOptions opts = null;
        if (server != null) {
            try {
                McpStatelessAsyncServer async = ReflectionUtils.readField(server, "asyncServer");
                opts = extractStatelessOptions(async);
            } catch (Exception ignored) {}
        }
        publishCustomEventCore(server != null ? server.getServerInfo() : null, projectId, eventData, opts);
    }

    /** @see #publishCustomEvent(McpSyncServer, String, CustomEventData) */
    public static void publishCustomEvent(McpStatelessAsyncServer server, String projectId,
                                          CustomEventData eventData) {
        AgentCatOptions opts = server != null ? extractStatelessOptions(server) : null;
        publishCustomEventCore(server != null ? server.getServerInfo() : null, projectId, eventData, opts);
    }

    /**
     * Publishes a custom analytics event attributed to a known session ID string.
     *
     * <p>Mirrors TypeScript {@code publishCustomEvent(sessionId, projectId, eventData)}.
     * No {@code redactEvent} hook is applied — there is no tracked server to source one from.
     *
     * @param sessionId session ID to attribute this event to (e.g. {@code "ses_..."})
     * @param projectId project ID from the AgentCat dashboard
     * @param eventData optional event payload; {@code null} is accepted
     */
    public static void publishCustomEvent(String sessionId, String projectId,
                                          CustomEventData eventData) {
        // Mirror TypeScript: eventData.sessionId takes priority over the string parameter.
        String resolvedSessionId = (eventData != null && eventData.sessionId != null)
                ? eventData.sessionId : sessionId;
        CustomEventData data = eventData != null ? eventData : new CustomEventData();
        data.sessionId = resolvedSessionId;
        publishCustomEventCore(null, projectId, data, null);
    }

    private static void publishCustomEventCore(McpSchema.Implementation info, String projectId,
                                               CustomEventData eventData, AgentCatOptions opts) {
        if (projectId == null || projectId.isBlank()) {
            writeToLog("publishCustomEvent: projectId is required — event dropped");
            return;
        }
        try {
            if (eventData == null) eventData = new CustomEventData();

            String sessionId    = eventData.sessionId  != null ? eventData.sessionId  : "";
            String resourceName = eventData.resourceName != null ? eventData.resourceName : DEFAULT_RESOURCE_NAME;
            String userIntent   = eventData.message;
            long   durationMs   = eventData.duration != null ? eventData.duration : 0L;
            boolean isError     = Boolean.TRUE.equals(eventData.isError);

            // parameters / response — normalise to Map<String,Object> or null
            Map<String, Object> params   = toMap(eventData.parameters);
            Map<String, Object> response = toMap(eventData.response);

            // Mirror TypeScript: when a tracked server is present, apply its redactEvent and
            // redactSensitiveInformation hooks. Session-ID-string path uses empty hooks.
            PendingHooks hooks = opts != null
                    ? new PendingHooks(opts.getRedactSensitiveInformation(), opts.getRedactEvent(),
                                       null, null, null, null)
                    : PendingHooks.empty();

            QueuedEvent event = new QueuedEvent(
                    new EventData(projectId, sessionId, resourceName,
                                  params, response, durationMs, isError, null, userIntent, null,
                                  OffsetDateTime.now(ZoneOffset.UTC)),
                    new ServerContext(name(info), version(info), null, null),
                    hooks
            );

            event.setCustomEvent(true);

            if (eventData.tags != null) {
                event.setTags(TagValidator.validate(eventData.tags));
            }
            if (eventData.properties != null && !eventData.properties.isEmpty()) {
                event.setProperties(eventData.properties);
            }

            EventQueue.getInstance().submit(event);

            writeToLog("publishCustomEvent: submitted"
                    + (sessionId.isEmpty() ? " (no session)" : " | session=" + sessionId)
                    + " | project=" + projectId
                    + " | resource=" + resourceName);

        } catch (Throwable t) {
            writeToLog("publishCustomEvent failed: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private static AgentCatOptions extractOptions(McpAsyncServer asyncServer) {
        try {
            Object tools = ReflectionUtils.readField(asyncServer, "tools");
            if (tools instanceof StatefulToolList list) return list.getOptions();
        } catch (Exception ignored) {}
        return null;
    }

    private static AgentCatOptions extractStatelessOptions(McpStatelessAsyncServer asyncServer) {
        try {
            Object tools = ReflectionUtils.readField(asyncServer, "tools");
            if (tools instanceof StatelessToolList list) return list.getOptions();
        } catch (Exception ignored) {}
        return null;
    }

    private static void ensureToolCapabilities(McpAsyncServer asyncServer) {
        ReflectionUtils.ensureToolCapabilities(asyncServer);
    }

    private static String name(McpSchema.Implementation info) {
        return info != null ? info.name() : null;
    }

    private static String version(McpSchema.Implementation info) {
        return info != null ? info.version() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?>) return (Map<String, Object>) value;
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put("value", value);
        return wrapper;
    }

    // ── Core logic ──────────────────────────────────────────────────────────

    /**
     * Core logic shared by both sync and async track() overloads.
     *
     * <p>McpSyncServer is a facade over McpAsyncServer — tools are always stored
     * on the async server. Both overloads resolve to the same McpAsyncServer and
     * call this method.
     */
    private static void trackAsyncServer(McpAsyncServer asyncServer, String projectId,
                                          String serverName, String serverVersion,
                                          AgentCatOptions opts) throws Exception {
        if (projectId == null || projectId.isBlank()) {
            writeToLog("Warning: No projectId provided. Events will not be sent anywhere.");
        }

        writeToLog("AgentCat setup started | project=" + projectId
                + " | server=" + serverName + "@" + serverVersion);

        // Ensure tool capabilities are declared so addTool() never throws
        // "Server must be configured with tool capabilities" when the server
        // was built without any tools and tools are added after track().
        ensureToolCapabilities(asyncServer);

        List<McpServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(asyncServer, "tools");

        // Guard: if the tools list is already a StatefulToolList, AgentCat was already installed.
        // Second track() on the same server is a no-op
        if (tools instanceof StatefulToolList) {
            writeToLog("AgentCat already installed on this server — second track() is a no-op");
            return;
        }

        StatefulToolCallWrapper wrapper = new StatefulToolCallWrapper(projectId, serverName, serverVersion, opts);

        List<McpServerFeatures.AsyncToolSpecification> wrapped = new ArrayList<>();
        for (McpServerFeatures.AsyncToolSpecification spec : new ArrayList<>(tools)) {
            wrapped.add(wrapper.wrap(spec));
        }

        // Never add a second get_more_tools: duplicate names break some clients.
        if (opts.isEnableReportMissing()
                && wrapped.stream().noneMatch(t -> GetMoreTools.GET_MORE_TOOLS_NAME.equals(t.tool().name()))) {
            wrapped.add(wrapper.wrap(GetMoreTools.buildTool()));
        }

        StatefulToolList newList = wrapper.newToolList(wrapped);
        writeField(asyncServer, "tools", newList);

        writeToLog("AgentCat setup complete | project=" + projectId
                + " | tools=" + tools.size() + (opts.isEnableReportMissing() ? "+get_more_tools" : "")
                + " | tracing=" + opts.isEnableTracing()
                + " | context=" + opts.isEnableToolCallContext()
                + " | reportMissing=" + opts.isEnableReportMissing());
    }

    /**
     * Core logic shared by both stateless track() overloads.
     */
    private static void trackStatelessAsyncServer(McpStatelessAsyncServer asyncServer,
                                                   String projectId,
                                                   String serverName,
                                                   String serverVersion,
                                                   AgentCatOptions opts) throws Exception {
        writeToLog("AgentCat setup started | project=" + projectId
                + " | server=" + serverName + "@" + serverVersion);

        // Direct field manipulation — avoids addTool()/removeTool() API which triggers
        // notifyToolsListChanged() and may block on older SDK versions with no connected clients.
        List<McpStatelessServerFeatures.AsyncToolSpecification> tools = ReflectionUtils.readField(asyncServer, "tools");

        // Guard: second track() on the same server is a no-op
        if (tools instanceof com.agentcat.sdk.engine.StatelessToolList) {
            writeToLog("AgentCat already installed on this stateless server — second track() is a no-op");
            return;
        }

        StatelessToolCallWrapper wrapper = new StatelessToolCallWrapper(projectId, serverName, serverVersion, opts);
        List<McpStatelessServerFeatures.AsyncToolSpecification> wrapped = new ArrayList<>();
        for (McpStatelessServerFeatures.AsyncToolSpecification spec : new ArrayList<>(tools)) {
            wrapped.add(wrapper.wrap(spec));
        }

        // Never add a second get_more_tools: duplicate names break some clients.
        if (opts.isEnableReportMissing()
                && wrapped.stream().noneMatch(t -> GetMoreTools.GET_MORE_TOOLS_NAME.equals(t.tool().name()))) {
            wrapped.add(wrapper.wrap(GetMoreTools.buildStatelessTool()));
        }

        writeField(asyncServer, "tools", wrapper.newToolList(wrapped));

        writeToLog("AgentCat setup complete | project=" + projectId
                + " | tools=" + tools.size() + (opts.isEnableReportMissing() ? "+get_more_tools" : "")
                + " | tracing=" + opts.isEnableTracing()
                + " | context=" + opts.isEnableToolCallContext()
                + " | reportMissing=" + opts.isEnableReportMissing());
    }

}
