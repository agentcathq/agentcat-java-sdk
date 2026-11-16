package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.sdk.engine.ReflectionUtils;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static com.agentcat.sdk.logging.AgentCatLogger.writeToLogFileOnly;

/**
 * Reads an MCP Java SDK server through its public list methods. The library's own list
 * handlers return these same lists whole, with no cursor. The server has a handler for a
 * list exactly when it declares the matching capability.
 *
 * <p>The library keeps resources, resource templates and prompts in hash maps, and the
 * iteration order of a hash map changes between JDK releases. The source sorts those three
 * lists by their key (uri, uri template, name), so every JDK sends the same body. Tools keep
 * the library's registration order.
 *
 * <p>The source holds the server weakly, so a tracked tool never keeps its server alive.
 */
final class McpServerSource<S> implements ServerInfoSource {

    private final WeakReference<S> server;
    private final Function<S, McpSchema.Implementation> info;
    private final Function<S, McpSchema.ServerCapabilities> capabilities;
    private final Function<S, Flux<?>> tools;
    private final Function<S, Flux<?>> resources;
    private final Function<S, Flux<?>> resourceTemplates;
    private final Function<S, Flux<?>> prompts;

    private McpServerSource(S server,
                            Function<S, McpSchema.Implementation> info,
                            Function<S, McpSchema.ServerCapabilities> capabilities,
                            Function<S, Flux<?>> tools,
                            Function<S, Flux<?>> resources,
                            Function<S, Flux<?>> resourceTemplates,
                            Function<S, Flux<?>> prompts) {
        this.server = new WeakReference<>(server);
        this.info = info;
        this.capabilities = capabilities;
        this.tools = tools;
        this.resources = resources;
        this.resourceTemplates = resourceTemplates;
        this.prompts = prompts;
    }

    static McpServerSource<McpAsyncServer> of(McpAsyncServer server) {
        return new McpServerSource<>(server,
                McpAsyncServer::getServerInfo, McpAsyncServer::getServerCapabilities,
                McpAsyncServer::listTools, McpAsyncServer::listResources,
                McpAsyncServer::listResourceTemplates, McpAsyncServer::listPrompts);
    }

    static McpServerSource<McpStatelessAsyncServer> of(McpStatelessAsyncServer server) {
        return new McpServerSource<>(server,
                McpStatelessAsyncServer::getServerInfo, McpStatelessAsyncServer::getServerCapabilities,
                McpStatelessAsyncServer::listTools, McpStatelessAsyncServer::listResources,
                McpStatelessAsyncServer::listResourceTemplates, McpStatelessAsyncServer::listPrompts);
    }

    @Override
    public ServerFeatures read() {
        S s = server.get();
        if (s == null) return null;

        McpSchema.Implementation implementation = info.apply(s);
        String title = implementation != null && implementation.title() != null ? implementation.title() : "";

        Map<String, ListPager> lists = new LinkedHashMap<>();
        McpSchema.ServerCapabilities caps = capabilities.apply(s);
        if (caps != null) {
            if (caps.tools() != null) lists.put("tools", wholeList(s, tools));
            if (caps.resources() != null) {
                lists.put("resources", sortedList(s, resources, McpServerSource::resourceKey));
                lists.put("resourceTemplates", sortedList(s, resourceTemplates, McpServerSource::templateKey));
            }
            if (caps.prompts() != null) lists.put("prompts", sortedList(s, prompts, McpServerSource::promptKey));
        }
        return new ServerFeatures(title, readInstructions(s), lists);
    }

    private ListPager wholeList(S s, Function<S, Flux<?>> list) {
        return cursor -> Mono.defer(() -> list.apply(s).collectList())
                .map(items -> new ListPage(items, null));
    }

    private ListPager sortedList(S s, Function<S, Flux<?>> list, Function<Object, String> key) {
        Comparator<Object> byKey = Comparator.comparing(key, Comparator.nullsFirst(Comparator.naturalOrder()));
        return cursor -> Mono.defer(() -> list.apply(s).collectSortedList(byKey))
                .map(items -> new ListPage(items, null));
    }

    private static String resourceKey(Object item) {
        return item instanceof McpSchema.Resource r ? r.uri() : null;
    }

    private static String templateKey(Object item) {
        return item instanceof McpSchema.ResourceTemplate t ? t.uriTemplate() : null;
    }

    private static String promptKey(Object item) {
        return item instanceof McpSchema.Prompt p ? p.name() : null;
    }

    /**
     * The library keeps the instructions in a private field with no getter. A library
     * without the field gives an empty string.
     */
    static String readInstructions(Object server) {
        try {
            Object value = ReflectionUtils.readField(server, "instructions");
            return value instanceof String text ? text : "";
        } catch (Throwable t) {
            writeToLogFileOnly("server info: no instructions field on " + server.getClass().getName()
                    + ", sending an empty string (" + t.getClass().getSimpleName() + ")");
            return "";
        }
    }
}
