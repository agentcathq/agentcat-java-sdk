package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.api.model.PublishServerInfoRequest;
import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.AgentCatTestExtension;
import com.agentcat.sdk.modules.pipeline.JsonTree;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared setup of the heartbeat tests: a fake clock, an aged memory and an empty recorder. */
abstract class HeartbeatTestSupport {

    static final long AGED_MS = 60_000L;

    final AtomicLong clock = new AtomicLong(1_000_000_000L);
    final AgentCatTestExtension.ServerInfoRecorder recorder = AgentCatTestExtension.serverInfoRecorder();

    @BeforeEach
    void resetHeartbeat() throws Exception {
        awaitIdle();
        AgentCatTestExtension.installServerInfoSender(recorder);
        recorder.clear();
        ServerInfoHeartbeat.resetForTesting(clock::get, AGED_MS);
    }

    @AfterEach
    void settleHeartbeat() throws Exception {
        awaitIdle();
        AgentCatTestExtension.installServerInfoSender(recorder);
        recorder.clear();
    }

    static void awaitIdle() throws InterruptedException {
        assertTrue(ServerInfoHeartbeat.awaitIdleForTesting(Duration.ofSeconds(40)), "a heartbeat still runs");
    }

    List<PublishServerInfoRequest> postsAfterIdle() throws InterruptedException {
        awaitIdle();
        return recorder.requests();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> features(PublishServerInfoRequest request) {
        return (Map<String, Object>) request.getFeatures();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(PublishServerInfoRequest request, String key) {
        return (List<Map<String, Object>>) features(request).get(key);
    }

    static List<Object> wire(List<McpSchema.Tool> tools) {
        List<Object> out = new ArrayList<>();
        for (McpSchema.Tool tool : tools) out.add(JsonTree.toTree(tool));
        return out;
    }

    static List<String> names(List<Map<String, Object>> items) {
        return items.stream().map(item -> (String) item.get("name")).toList();
    }

    // ── Fake servers ──────────────────────────────────────────────────────────

    static ServerInfoTarget target(ServerInfoSource source) {
        return target(source, new AgentCatOptions());
    }

    static ServerInfoTarget target(ServerInfoSource source, AgentCatOptions opts) {
        return new ServerInfoTarget(ServerInfoFixture.PROJECT_ID, ServerInfoFixture.NAME,
                ServerInfoFixture.VERSION, opts, source);
    }

    static ServerInfoSource source(Map<String, ListPager> lists) {
        return () -> new ServerFeatures(ServerInfoFixture.TITLE, ServerInfoFixture.INSTRUCTIONS, lists);
    }

    static Map<String, ListPager> lists(Object... keysAndPagers) {
        Map<String, ListPager> lists = new LinkedHashMap<>();
        for (int i = 0; i < keysAndPagers.length; i += 2) {
            lists.put((String) keysAndPagers[i], (ListPager) keysAndPagers[i + 1]);
        }
        return lists;
    }

    /** A pager over fixed pages; page {@code n} has the cursor {@code "c" + n}. */
    static ListPager pages(List<?>... pages) {
        return cursor -> {
            int index = cursor == null ? 0 : Integer.parseInt(cursor.substring(1));
            String next = index + 1 < pages.length ? "c" + (index + 1) : null;
            return Mono.just(new ListPage(pages[index], next));
        };
    }

    static ListPager failing() {
        return cursor -> Mono.error(new IllegalStateException("handler failed"));
    }

    static Map<String, Object> item(String name) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("description", name + " description");
        return item;
    }
}
