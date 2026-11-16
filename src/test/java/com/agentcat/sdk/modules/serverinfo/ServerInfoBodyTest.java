package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.api.model.PublishServerInfoRequest;
import com.agentcat.sdk.modules.serverinfo.ServerInfoFixture.Kind;
import com.agentcat.sdk.modules.serverinfo.ServerInfoFixture.Tracked;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerInfoBodyTest extends HeartbeatTestSupport {

    @ParameterizedTest
    @EnumSource(Kind.class)
    void v1_oneToolCallSendsTheWholeServer(Kind kind) throws Exception {
        Tracked server = ServerInfoFixture.tracked(kind);

        assertTrue(server.call("alpha").content().stream()
                .anyMatch(c -> c instanceof McpSchema.TextContent t && "ok".equals(t.text())));

        List<PublishServerInfoRequest> posts = postsAfterIdle();
        assertEquals(1, posts.size());
        PublishServerInfoRequest body = posts.get(0);
        assertEquals(Set.of("project_id", "server_name", "server_version", "server_title", "instructions", "features"),
                body.toJsonMap().keySet());
        assertEquals(ServerInfoFixture.PROJECT_ID, body.getProjectId());
        assertEquals("demo-server", body.getServerName());
        assertEquals("1.2.3", body.getServerVersion());
        assertEquals("Demo Title", body.getServerTitle());
        assertEquals("Use the demo tools.", body.getInstructions());

        assertEquals(List.of("tools", "resources", "resourceTemplates", "prompts"), List.copyOf(features(body).keySet()));
        assertEquals(wire(server.listedTools()), features(body).get("tools"));
        List<Map<String, Object>> tools = list(body, "tools");
        assertEquals(List.of("alpha", "beta", "gamma", "get_more_tools"), names(tools));
        @SuppressWarnings("unchecked")
        Map<String, Object> alphaProps = (Map<String, Object>)
                ((Map<String, Object>) tools.get(0).get("inputSchema")).get("properties");
        assertTrue(alphaProps.containsKey("context"), "the tool list holds the injected parameters");
        assertEquals(ServerInfoFixture.BETA_DESCRIPTION, tools.get(1).get("description"));
        assertEquals(2_000, ((String) tools.get(1).get("description")).length());
        assertTrue(tools.get(1).containsKey("outputSchema"));
        assertTrue(tools.get(1).containsKey("annotations"));
        assertEquals("Beta Title", tools.get(1).get("title"));

        assertEquals(List.of("file:///a.txt", "file:///b.txt"), values(list(body, "resources"), "uri"));
        assertEquals(1, list(body, "resourceTemplates").size());
        assertEquals("file:///{name}.txt", list(body, "resourceTemplates").get(0).get("uriTemplate"));
        assertEquals(List.of("greet", "plain"), names(list(body, "prompts")));
        assertEquals(1, ((List<?>) list(body, "prompts").get(0).get("arguments")).size());
    }

    @Test
    void v1_hashMapListsAreSortedByKeySoEveryJdkSendsTheSameBody() throws Exception {
        List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) names.add("p" + Integer.toString(i * 7919, 36));
        var spec = McpServer.async(ServerInfoFixture.stateless())
                .capabilities(McpSchema.ServerCapabilities.builder().resources(false, false).prompts(false).build());
        for (String n : names) {
            spec.resources(new McpStatelessServerFeatures.AsyncResourceSpecification(
                    new McpSchema.Resource("file:///" + n + ".txt", n, null, null, null, null, null, null),
                    (ctx, req) -> Mono.empty()));
            spec.resourceTemplates(new McpStatelessServerFeatures.AsyncResourceTemplateSpecification(
                    new McpSchema.ResourceTemplate("file:///" + n + "/{id}", n, null, null, null, null),
                    (ctx, req) -> Mono.empty()));
            spec.prompts(new McpStatelessServerFeatures.AsyncPromptSpecification(
                    new McpSchema.Prompt(n, null, null), (ctx, req) -> Mono.empty()));
        }
        Map<String, ListPager> lists = McpServerSource.of(spec.build()).read().lists();
        long deadline = System.nanoTime() + ListReader.LIST_LIMIT_NANOS;

        List<String> sorted = names.stream().sorted().toList();
        assertEquals(sorted, names(ListReader.readAll(lists.get("prompts"), deadline)));
        assertEquals(sorted.stream().map(n -> "file:///" + n + ".txt").toList(),
                values(ListReader.readAll(lists.get("resources"), deadline), "uri"));
        assertEquals(sorted.stream().map(n -> "file:///" + n + "/{id}").toList(),
                values(ListReader.readAll(lists.get("resourceTemplates"), deadline), "uriTemplate"));
    }

    @Test
    void v2_fiveToolsOnThreePagesGiveEachToolAndGetMoreToolsOneTime() throws Exception {
        ServerInfoTarget target = target(source(lists("tools", pages(
                List.of(item("t1"), item("t2"), item("get_more_tools")),
                List.of(item("t3"), item("t4"), item("get_more_tools")),
                List.of(item("t5"), item("get_more_tools"))))));

        ServerInfoHeartbeat.onToolCall(target, "alpha");

        List<PublishServerInfoRequest> posts = postsAfterIdle();
        assertEquals(1, posts.size());
        assertEquals(List.of("t1", "t2", "get_more_tools", "t3", "t4", "t5"), names(list(posts.get(0), "tools")));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void v3_aServerWithToolsOnlyHasOnlyTheToolsKey(Kind kind) throws Exception {
        Tracked server = ServerInfoFixture.tracked(kind, ServerInfoFixture.PROJECT_ID, "demo-server",
                new com.agentcat.sdk.AgentCatOptions(), true);

        server.call("alpha");

        List<PublishServerInfoRequest> posts = postsAfterIdle();
        assertEquals(1, posts.size());
        assertEquals(Set.of("tools"), features(posts.get(0)).keySet());
    }

    @Test
    void v4_aFailedPromptListLeavesOnlyThePromptsOut() throws Exception {
        ServerInfoTarget target = target(source(lists(
                "tools", pages(List.of(item("alpha"), item("beta"), item("gamma"))),
                "resources", pages(List.of(Map.of("uri", "file:///a.txt", "name", "a"))),
                "prompts", failing())));

        ServerInfoHeartbeat.onToolCall(target, "alpha");

        List<PublishServerInfoRequest> posts = postsAfterIdle();
        assertEquals(1, posts.size());
        assertEquals(Set.of("tools", "resources"), features(posts.get(0)).keySet());
        assertEquals(List.of("alpha", "beta", "gamma"), names(list(posts.get(0), "tools")));
    }

    @Test
    void v5_aFailedToolListSendsNothing() throws Exception {
        ServerInfoTarget target = target(source(lists(
                "tools", failing(),
                "prompts", pages(List.of(item("greet"))))));

        ServerInfoHeartbeat.onToolCall(target, "alpha");

        assertTrue(postsAfterIdle().isEmpty());
    }

    @Test
    void v6_aRepeatedCursorFailsTheListWithin100Pages() {
        AtomicInteger calls = new AtomicInteger();
        ListPager sameCursor = cursor -> {
            calls.incrementAndGet();
            return Mono.just(new ListPage(List.of(item("t")), "same"));
        };
        long start = System.nanoTime();

        assertThrows(ListReader.ListFailedException.class,
                () -> ListReader.readAll(sameCursor, System.nanoTime() + ListReader.LIST_LIMIT_NANOS));

        assertTrue(calls.get() <= ListReader.MAX_PAGES, "pages read: " + calls.get());
        assertTrue(System.nanoTime() - start < 5_000_000_000L);
    }

    @Test
    void v6_aListThatNeverEndsFailsAfter100Pages() {
        AtomicInteger calls = new AtomicInteger();
        ListPager endless = cursor -> {
            int n = calls.incrementAndGet();
            return Mono.just(new ListPage(List.of(item("t" + n)), "c" + n));
        };

        assertThrows(ListReader.ListFailedException.class,
                () -> ListReader.readAll(endless, System.nanoTime() + ListReader.LIST_LIMIT_NANOS));

        assertEquals(ListReader.MAX_PAGES, calls.get());
    }

    @Test
    void v6_aToolListWithARepeatedCursorSendsNothing() throws Exception {
        ServerInfoTarget target = target(source(lists(
                "tools", (ListPager) cursor -> Mono.just(new ListPage(List.of(item("t")), "same")))));
        long start = System.nanoTime();

        ServerInfoHeartbeat.onToolCall(target, "alpha");

        assertTrue(postsAfterIdle().isEmpty());
        assertTrue(System.nanoTime() - start < 5_000_000_000L);
    }

    @Test
    void anEmptyListGivesAnEmptyArray() throws Exception {
        ServerInfoTarget target = target(source(lists(
                "tools", pages(List.of(item("alpha"))),
                "prompts", pages(List.of()))));

        ServerInfoHeartbeat.onToolCall(target, "alpha");

        List<PublishServerInfoRequest> posts = postsAfterIdle();
        assertEquals(1, posts.size());
        assertEquals(List.of(), features(posts.get(0)).get("prompts"));
    }

    @Test
    void aGoneServerSendsNothing() throws Exception {
        ServerInfoHeartbeat.onToolCall(target(() -> null), "alpha");

        assertTrue(postsAfterIdle().isEmpty());
    }

    @Test
    void aMissingInstructionsFieldGivesAnEmptyString() {
        assertEquals("", McpServerSource.readInstructions(new Object()));
    }

    @Test
    void theRequestHasNoKeyForAMissingServerVersion() {
        PublishServerInfoRequest request = ServerInfoBody.request("proj_x", "name", null, "", "", Map.of());
        assertFalse(request.toJsonMap().containsKey("server_version"));
    }
}
