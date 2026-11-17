package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.api.model.PublishServerInfoRequest;
import com.agentcat.sdk.AgentCatOptions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerInfoRedactionTest extends HeartbeatTestSupport {

    private static AgentCatOptions replacingSecret() {
        return new AgentCatOptions().setRedactSensitiveInformation(text ->
                CompletableFuture.completedFuture(text.replace("secret", "[x]")));
    }

    private static ServerInfoTarget secretServer(AgentCatOptions opts) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "secret_tool");
        tool.put("description", "secret tool");
        tool.put("inputSchema", Map.of("type", "object",
                "properties", Map.of("secret", Map.of("type", "string", "description", "a secret"))));
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("uri", "file:///secret.txt");
        resource.put("name", "secret");
        resource.put("description", "the secret file");
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("uriTemplate", "file:///secret/{id}");
        template.put("name", "secret_template");
        ServerInfoSource source = () -> new ServerFeatures("secret title", "the secret plan", lists(
                "tools", pages(List.of(tool)),
                "resources", pages(List.of(resource)),
                "resourceTemplates", pages(List.of(template))));
        return new ServerInfoTarget(ServerInfoFixture.PROJECT_ID, "secret-server", "1.0.0-secret", opts, source);
    }

    @Test
    @SuppressWarnings("unchecked")
    void v11_theRedactionFunctionRunsOnTheTextButNotOnNames() throws Exception {
        ServerInfoHeartbeat.onToolCall(secretServer(replacingSecret()), "alpha");

        List<PublishServerInfoRequest> posts = postsAfterIdle();
        assertEquals(1, posts.size());
        PublishServerInfoRequest body = posts.get(0);
        assertEquals("the [x] plan", body.getInstructions());
        assertEquals("[x] title", body.getServerTitle());
        assertEquals("secret-server", body.getServerName());
        assertEquals("1.0.0-secret", body.getServerVersion());

        Map<String, Object> tool = list(body, "tools").get(0);
        assertEquals("secret_tool", tool.get("name"));
        assertEquals("[x] tool", tool.get("description"));
        Map<String, Object> properties = (Map<String, Object>) ((Map<String, Object>) tool.get("inputSchema")).get("properties");
        assertTrue(properties.containsKey("secret"), "a key is never changed");
        assertEquals("a [x]", ((Map<String, Object>) properties.get("secret")).get("description"));

        Map<String, Object> resource = list(body, "resources").get(0);
        assertEquals("file:///secret.txt", resource.get("uri"));
        assertEquals("secret", resource.get("name"));
        assertEquals("the [x] file", resource.get("description"));
        Map<String, Object> template = list(body, "resourceTemplates").get(0);
        assertEquals("file:///secret/{id}", template.get("uriTemplate"));
        assertEquals("secret_template", template.get("name"));
    }

    @Test
    void v12_aFailingRedactionFunctionSendsNothing() throws Exception {
        AgentCatOptions opts = new AgentCatOptions().setRedactSensitiveInformation(text -> {
            throw new IllegalStateException("redaction broke");
        });

        ServerInfoHeartbeat.onToolCall(secretServer(opts), "alpha");

        assertTrue(postsAfterIdle().isEmpty());
    }

    @Test
    void v12_aRedactionFunctionWhoseFutureFailsSendsNothing() throws Exception {
        AgentCatOptions opts = new AgentCatOptions().setRedactSensitiveInformation(text ->
                CompletableFuture.failedFuture(new IllegalStateException("redaction broke")));

        ServerInfoHeartbeat.onToolCall(secretServer(opts), "alpha");

        assertTrue(postsAfterIdle().isEmpty());
    }

    @Test
    void v12_aRedactionFunctionThatGivesNoStringSendsNothing() throws Exception {
        AgentCatOptions opts = new AgentCatOptions().setRedactSensitiveInformation(text ->
                CompletableFuture.completedFuture(null));

        ServerInfoHeartbeat.onToolCall(secretServer(opts), "alpha");

        assertTrue(postsAfterIdle().isEmpty());
    }

    @Test
    void v12_aRedactionFunctionThatPassesItsTimeLimitSendsNothing() throws Exception {
        AgentCatOptions opts = new AgentCatOptions().setRedactSensitiveInformation(text -> new CompletableFuture<>());

        ServerInfoHeartbeat.onToolCall(secretServer(opts), "alpha");

        assertTrue(postsAfterIdle().isEmpty());
    }

    @Test
    void withoutARedactionFunctionTheTextIsSentUnchanged() throws Exception {
        ServerInfoHeartbeat.onToolCall(secretServer(new AgentCatOptions()), "alpha");

        List<PublishServerInfoRequest> posts = postsAfterIdle();
        assertEquals(1, posts.size());
        assertEquals("the secret plan", posts.get(0).getInstructions());
    }
}
