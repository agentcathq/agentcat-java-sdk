package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.engine.ReflectionUtils;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * The fixture server of the heartbeat tests, in each of the four server kinds: name
 * {@code demo-server}, version {@code 1.2.3}, title {@code Demo Title}, instructions
 * {@code Use the demo tools.}, the tools {@code alpha}, {@code beta} and {@code gamma}, two
 * resources, one resource template and two prompts.
 */
final class ServerInfoFixture {

    static final String PROJECT_ID = "proj_" + "a".repeat(27);
    static final String NAME = "demo-server";
    static final String VERSION = "1.2.3";
    static final String TITLE = "Demo Title";
    static final String INSTRUCTIONS = "Use the demo tools.";
    static final String BETA_DESCRIPTION = "Beta describes itself at length. ".repeat(63).substring(0, 2_000);

    enum Kind { SYNC, ASYNC, STATELESS_SYNC, STATELESS_ASYNC }

    private ServerInfoFixture() {}

    /** A tracked fixture server. */
    static Tracked tracked(Kind kind, String projectId, String serverName, AgentCatOptions opts, boolean toolsOnly) {
        McpSchema.Implementation info = new McpSchema.Implementation(serverName, TITLE, VERSION);
        McpSchema.ServerCapabilities caps = toolsOnly
                ? McpSchema.ServerCapabilities.builder().tools(false).build()
                : McpSchema.ServerCapabilities.builder().tools(false).resources(false, false).prompts(false).build();
        return switch (kind) {
            case SYNC -> {
                var spec = McpServer.sync(stdio()).serverInfo(info).instructions(INSTRUCTIONS).capabilities(caps);
                for (McpSchema.Tool tool : tools()) spec.toolCall(tool, (ex, req) -> ok());
                if (!toolsOnly) {
                    for (McpSchema.Resource r : resources()) {
                        spec.resources(new McpServerFeatures.SyncResourceSpecification(r, (ex, req) -> read()));
                    }
                    spec.resourceTemplates(new McpServerFeatures.SyncResourceTemplateSpecification(template(), (ex, req) -> read()));
                    for (McpSchema.Prompt p : prompts()) {
                        spec.prompts(new McpServerFeatures.SyncPromptSpecification(p, (ex, req) -> prompt()));
                    }
                }
                McpSyncServer server = AgentCat.track(spec.build(), projectId, opts);
                yield new Tracked(server.getAsyncServer(), server.getAsyncServer().listTools().collectList().block());
            }
            case ASYNC -> {
                var spec = McpServer.async(stdio()).serverInfo(info).instructions(INSTRUCTIONS).capabilities(caps);
                for (McpSchema.Tool tool : tools()) spec.toolCall(tool, (ex, req) -> Mono.just(ok()));
                if (!toolsOnly) {
                    for (McpSchema.Resource r : resources()) {
                        spec.resources(new McpServerFeatures.AsyncResourceSpecification(r, (ex, req) -> Mono.just(read())));
                    }
                    spec.resourceTemplates(new McpServerFeatures.AsyncResourceTemplateSpecification(template(), (ex, req) -> Mono.just(read())));
                    for (McpSchema.Prompt p : prompts()) {
                        spec.prompts(new McpServerFeatures.AsyncPromptSpecification(p, (ex, req) -> Mono.just(prompt())));
                    }
                }
                McpAsyncServer server = AgentCat.track(spec.build(), projectId, opts);
                yield new Tracked(server, server.listTools().collectList().block());
            }
            case STATELESS_SYNC -> {
                var spec = McpServer.sync(stateless()).serverInfo(info).instructions(INSTRUCTIONS).capabilities(caps);
                for (McpSchema.Tool tool : tools()) spec.toolCall(tool, (ctx, req) -> ok());
                if (!toolsOnly) {
                    for (McpSchema.Resource r : resources()) {
                        spec.resources(new McpStatelessServerFeatures.SyncResourceSpecification(r, (ctx, req) -> read()));
                    }
                    spec.resourceTemplates(new McpStatelessServerFeatures.SyncResourceTemplateSpecification(template(), (ctx, req) -> read()));
                    for (McpSchema.Prompt p : prompts()) {
                        spec.prompts(new McpStatelessServerFeatures.SyncPromptSpecification(p, (ctx, req) -> prompt()));
                    }
                }
                McpStatelessSyncServer server = AgentCat.track(spec.build(), projectId, opts);
                McpStatelessAsyncServer async = asyncOf(server);
                yield new Tracked(async, async.listTools().collectList().block());
            }
            case STATELESS_ASYNC -> {
                var spec = McpServer.async(stateless()).serverInfo(info).instructions(INSTRUCTIONS).capabilities(caps);
                for (McpSchema.Tool tool : tools()) spec.toolCall(tool, (ctx, req) -> Mono.just(ok()));
                if (!toolsOnly) {
                    for (McpSchema.Resource r : resources()) {
                        spec.resources(new McpStatelessServerFeatures.AsyncResourceSpecification(r, (ctx, req) -> Mono.just(read())));
                    }
                    spec.resourceTemplates(new McpStatelessServerFeatures.AsyncResourceTemplateSpecification(template(), (ctx, req) -> Mono.just(read())));
                    for (McpSchema.Prompt p : prompts()) {
                        spec.prompts(new McpStatelessServerFeatures.AsyncPromptSpecification(p, (ctx, req) -> Mono.just(prompt())));
                    }
                }
                McpStatelessAsyncServer server = AgentCat.track(spec.build(), projectId, opts);
                yield new Tracked(server, server.listTools().collectList().block());
            }
        };
    }

    static Tracked tracked(Kind kind) {
        return tracked(kind, PROJECT_ID, NAME, new AgentCatOptions(), false);
    }

    /** The fixture tools. */
    static List<McpSchema.Tool> tools() {
        McpSchema.JsonSchema query = McpSchema.JsonSchema.builder()
                .type("object")
                .properties(Map.of("q", Map.of("type", "string", "description", "The query")))
                .build();
        return List.of(
                McpSchema.Tool.builder("alpha").description("Alpha tool").inputSchema(query).build(),
                McpSchema.Tool.builder("beta")
                        .title("Beta Title")
                        .description(BETA_DESCRIPTION)
                        .inputSchema(query)
                        .outputSchema(Map.of("type", "object",
                                "properties", Map.of("ok", Map.of("type", "boolean"))))
                        .annotations(new McpSchema.ToolAnnotations("Beta Annotation", true, false, true, false, null))
                        .build(),
                McpSchema.Tool.builder("gamma").description("Gamma tool").inputSchema(query).build());
    }

    private static List<McpSchema.Resource> resources() {
        return List.of(
                new McpSchema.Resource("file:///a.txt", "a", "A", "First file", "text/plain", null, null, null),
                new McpSchema.Resource("file:///b.txt", "b", "B", "Second file", "text/plain", null, null, null));
    }

    private static McpSchema.ResourceTemplate template() {
        return new McpSchema.ResourceTemplate("file:///{name}.txt", "files", "Files", "Text files", "text/plain", null);
    }

    private static List<McpSchema.Prompt> prompts() {
        return List.of(
                new McpSchema.Prompt("greet", "Greet someone",
                        List.of(new McpSchema.PromptArgument("name", "Who to greet", true))),
                new McpSchema.Prompt("plain", "A prompt with no arguments", null));
    }

    private static McpSchema.CallToolResult ok() {
        return McpSchema.CallToolResult.builder().addTextContent("ok").build();
    }

    private static McpSchema.ReadResourceResult read() {
        return new McpSchema.ReadResourceResult(List.of());
    }

    private static McpSchema.GetPromptResult prompt() {
        return new McpSchema.GetPromptResult("p", List.of());
    }

    private static StdioServerTransportProvider stdio() {
        return new StdioServerTransportProvider(new JacksonMcpJsonMapper(new JsonMapper()));
    }

    private static McpStatelessServerTransport stateless() {
        return new McpStatelessServerTransport() {
            @Override
            public void setMcpHandler(McpStatelessServerHandler handler) {}

            @Override
            public Mono<Void> closeGracefully() {
                return Mono.empty();
            }
        };
    }

    private static McpStatelessAsyncServer asyncOf(McpStatelessSyncServer server) {
        try {
            return ReflectionUtils.readField(server, "asyncServer");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A tracked server: its async core and the tool list that a client gets from it.
     */
    record Tracked(Object asyncServer, List<McpSchema.Tool> listedTools) {

        /** Calls a tool through the handler that AgentCat installed, as the library does. */
        McpSchema.CallToolResult call(String toolName) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, Map.of("q", "x"));
            try {
                if (asyncServer instanceof McpAsyncServer stateful) {
                    List<McpServerFeatures.AsyncToolSpecification> specs = ReflectionUtils.readField(stateful, "tools");
                    return specs.stream().filter(s -> toolName.equals(s.tool().name())).findFirst().orElseThrow()
                            .callHandler().apply(null, request).block();
                }
                List<McpStatelessServerFeatures.AsyncToolSpecification> specs =
                        ReflectionUtils.readField(asyncServer, "tools");
                return specs.stream().filter(s -> toolName.equals(s.tool().name())).findFirst().orElseThrow()
                        .callHandler().apply(McpTransportContext.EMPTY, request).block();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
