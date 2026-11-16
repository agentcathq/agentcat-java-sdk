package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.sdk.AgentCat;
import com.agentcat.sdk.AgentCatTestExtension;
import com.agentcat.sdk.modules.events.EventQueue;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The posted JSON of {@code features} equals, value by value, the JSON that the MCP library
 * puts on the wire for the same lists.
 */
class ServerInfoWireFormTest extends HeartbeatTestSupport {

    private static final String SERVER_INFO_PATH = "/custom/v1/public/server-info/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Map<String, Object> shapesSchema() {
        Map<String, Object> count = new LinkedHashMap<>();
        count.put("type", "integer");
        count.put("minimum", 1);
        count.put("maximum", 10.5);
        count.put("default", null);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("count", count);
        properties.put("flag", Map.of("type", "boolean", "default", true));
        properties.put("options", Map.of("type", "object", "properties", Map.of()));
        properties.put("tags", Map.of("type", "array", "items", Map.of("type", "string"), "default", List.of()));
        properties.put("nested", Map.of("type", "object",
                "properties", Map.of("inner", Map.of("type", "object",
                        "properties", Map.of("depth", Map.of("type", "integer", "minimum", 2))))));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    @Test
    void h4_thePostedFeaturesEqualTheLibraryWireJsonValueByValue() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            try (InputStream in = exchange.getRequestBody()) {
                bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        api.start();

        McpSyncServer server = AgentCat.track(McpServer.sync(new StdioServerTransportProvider(new JacksonMcpJsonMapper(new tools.jackson.databind.json.JsonMapper())))
                .serverInfo(new McpSchema.Implementation("demo-server", "Demo Title", "1.2.3"))
                .instructions("Use the demo tools.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).resources(false, false).prompts(false).build())
                .toolCall(McpSchema.Tool.builder("shapes").description("Every JSON shape").inputSchema(shapesSchema()).build(),
                        (exchange, request) -> McpSchema.CallToolResult.builder().addTextContent("ok").build())
                .resources(new McpServerFeatures.SyncResourceSpecification(
                        new McpSchema.Resource("file:///a.txt", "a", "A", "First file", "text/plain", 12L, null, null),
                        (exchange, request) -> new McpSchema.ReadResourceResult(List.of())))
                .prompts(new McpServerFeatures.SyncPromptSpecification(
                        new McpSchema.Prompt("greet", "Greet someone",
                                List.of(new McpSchema.PromptArgument("name", "Who to greet", true))),
                        (exchange, request) -> new McpSchema.GetPromptResult("p", List.of())))
                .build(), ServerInfoFixture.PROJECT_ID);
        McpAsyncServer async = server.getAsyncServer();

        try {
            EventQueue.getInstance().configure("http://127.0.0.1:" + api.getAddress().getPort() + "/custom");
            ServerInfoFixture.Tracked tracked = new ServerInfoFixture.Tracked(async, List.of());
            tracked.call("shapes");
            awaitIdle();
        } finally {
            EventQueue.getInstance().configure("http://localhost:1");
            AgentCatTestExtension.installServerInfoSender(recorder);
            api.stop(0);
        }

        int index = paths.indexOf(SERVER_INFO_PATH);
        assertTrue(index >= 0, "paths: " + paths);
        JsonNode posted = JSON.readTree(bodies.get(index)).get("features");

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("tools", wireList(async.listTools().collectList().block()));
        expected.put("resources", wireList(async.listResources().collectList().block()));
        expected.put("resourceTemplates", wireList(async.listResourceTemplates().collectList().block()));
        expected.put("prompts", wireList(async.listPrompts().collectList().block()));
        JsonNode wire = JSON.readTree(JSON.writeValueAsString(expected));

        assertEquals(wire, posted);

        JsonNode count = posted.get("tools").get(0).get("inputSchema").get("properties").get("count");
        assertTrue(count.get("minimum").isIntegralNumber(), "an integer stays an integer: " + count.get("minimum"));
        assertEquals("1", count.get("minimum").toString());
        assertTrue(count.get("maximum").isFloatingPointNumber());
        assertEquals(10.5, count.get("maximum").doubleValue());
        assertTrue(count.has("default") && count.get("default").isNull(), "a null value keeps its key");
        JsonNode properties = posted.get("tools").get(0).get("inputSchema").get("properties");
        assertTrue(properties.get("flag").get("default").isBoolean());
        assertTrue(properties.get("options").get("properties").isObject());
        assertEquals(0, properties.get("options").get("properties").size());
        assertTrue(properties.get("tags").get("default").isArray());
        assertEquals(0, properties.get("tags").get("default").size());
        assertEquals("2", properties.get("nested").get("properties").get("inner")
                .get("properties").get("depth").get("minimum").toString());
        assertEquals("12", posted.get("resources").get(0).get("size").toString());
    }

    /** Each item as the MCP library writes it on the wire. */
    private static List<JsonNode> wireList(List<?> items) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (Object item : items) out.add(JSON.readTree(McpJsonDefaults.getMapper().writeValueAsString(item)));
        return out;
    }
}
