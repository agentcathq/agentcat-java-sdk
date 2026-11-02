package com.agentcat.sdk.engine;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.model.SessionSource;
import com.agentcat.sdk.modules.handles.ParameterInjector;
import com.agentcat.sdk.modules.handles.SessionIdProvider;
import com.agentcat.sdk.tools.GetMoreTools;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ToolCallWrapper} — covering both
 * {@link ToolCallWrapper#prepare} and {@link ToolCallWrapper#stripHandleArgs} /
 * {@link ToolCallWrapper#captureArgs} package-private static methods.
 *
 * <p>Tests run in the {@code com.agentcat.sdk.engine} package so they can access the
 * package-private methods and the {@link ToolCallWrapper.PreparedCall} record directly,
 * without reflection or an MCP server.
 *
 * <p>The MCP server exchange ({@code extra}) is always {@code null} in these tests —
 * hooks receive it as-is and the prepare logic does not dereference it.
 */
class ToolCallWrapperTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AgentCatOptions defaultOpts() {
        return new AgentCatOptions();
    }

    private static McpSchema.CallToolRequest request(String toolName, Object... kvPairs) {
        Map<String, Object> args = new HashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            args.put((String) kvPairs[i], kvPairs[i + 1]);
        }
        return new McpSchema.CallToolRequest(toolName, args);
    }

    private static McpSchema.CallToolRequest emptyRequest(String toolName) {
        return new McpSchema.CallToolRequest(toolName, Map.of());
    }

    private static Map<String, Object> args(Object... kvPairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            map.put((String) kvPairs[i], kvPairs[i + 1]);
        }
        return map;
    }

    private static McpSchema.CallToolRequest requestFromMap(String toolName, Map<String, Object> a) {
        return new McpSchema.CallToolRequest(toolName, a);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // prepare() tests
    // ══════════════════════════════════════════════════════════════════════════

    // ── startMs ───────────────────────────────────────────────────────────────

    @Test
    void prepareRecordsStartTimeBeforeReturn() {
        long before = System.currentTimeMillis();
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, defaultOpts());
        long after = System.currentTimeMillis();

        assertTrue(call.startMs() >= before && call.startMs() <= after,
                "startMs must be within [before, after] wall-clock interval");
    }

    // ── toolName ──────────────────────────────────────────────────────────────

    @Test
    void prepareStoresToolName() {
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("my_tool"), null, "my_tool", null, null, defaultOpts());
        assertEquals("my_tool", call.toolName());
    }

    // ── rawArgs and strippedArgs ───────────────────────────────────────────────

    @Test
    void prepareRawArgsContainsSessionId() {
        McpSchema.CallToolRequest req = request("echo", "session_id", "start", "x", "1");
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(req, null, "echo", null, null, defaultOpts());
        assertTrue(call.callArgs().rawArgs().containsKey("session_id"),
                "rawArgs must preserve the original session_id before stripping");
    }

    @Test
    void prepareStrippedArgsDoesNotContainSessionId() {
        McpSchema.CallToolRequest req = request("echo", "session_id", "start", "x", "1");
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(req, null, "echo", null, null, defaultOpts());
        assertFalse(call.callArgs().stripped().arguments().containsKey("session_id"),
                "strippedArgs must not contain session_id");
    }

    @Test
    void prepareStrippedArgsPreservesNonHandleParams() {
        McpSchema.CallToolRequest req = request("echo", "session_id", "start", "payload", "hello");
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(req, null, "echo", null, null, defaultOpts());
        assertEquals("hello", call.callArgs().stripped().arguments().get("payload"),
                "strippedArgs must preserve non-handle params");
    }

    @Test
    void prepareStrippedRequestToolNameMatchesOriginal() {
        McpSchema.CallToolRequest req = request("my_tool", "session_id", "start");
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(req, null, "my_tool", null, null, defaultOpts());
        assertEquals("my_tool", call.callArgs().stripped().name(),
                "the stripped CallToolRequest must carry the same tool name");
    }

    // ── handles ───────────────────────────────────────────────────────────────

    @Test
    void prepareHandlesAbsentSessionIdAsMinted() {
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, defaultOpts());
        assertEquals(SessionSource.MINTED, call.handles().getSessionSource(),
                "absent session_id must produce MINTED handles");
        assertTrue(SessionIdProvider.isValid(call.handles().getSessionId()),
                "minted session_id must be a valid KSUID");
    }

    @Test
    void prepareHandlesStartSentinelAsMinted() {
        McpSchema.CallToolRequest req = request("echo", "session_id", "start");
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(req, null, "echo", null, null, defaultOpts());
        assertEquals(SessionSource.MINTED, call.handles().getSessionSource());
    }

    @Test
    void prepareHandlesValidSessionIdAsSupplied() {
        String validId = SessionIdProvider.newSessionId();
        McpSchema.CallToolRequest req = request("echo", "session_id", validId);
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(req, null, "echo", null, null, defaultOpts());
        assertEquals(SessionSource.SUPPLIED, call.handles().getSessionSource());
        assertEquals(validId, call.handles().getSessionId());
    }

    // ── userIntent ────────────────────────────────────────────────────────────

    @Test
    void prepareCaputresUserIntentFromContextParam() {
        AgentCatOptions opts = defaultOpts(); // enableToolCallContext=true
        McpSchema.CallToolRequest req = request("echo",
                "session_id", "start",
                "context",    "searching the org repos for latency issues");
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(req, null, "echo", null, null, opts);
        assertEquals("searching the org repos for latency issues", call.callArgs().userIntent());
    }

    @Test
    void prepareUserIntentIsNullWhenContextDisabled() {
        AgentCatOptions opts = defaultOpts();
        opts.setEnableToolCallContext(false);
        McpSchema.CallToolRequest req = request("echo",
                "session_id", "start",
                "context",    "some context");
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(req, null, "echo", null, null, opts);
        assertNull(call.callArgs().userIntent(),
                "userIntent must be null when enableToolCallContext=false");
    }

    @Test
    void prepareUserIntentIsNullWhenContextBlank() {
        McpSchema.CallToolRequest req = request("echo",
                "session_id", "start",
                "context",    "   ");
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(req, null, "echo", null, null, defaultOpts());
        assertNull(call.callArgs().userIntent(),
                "blank context string must not be treated as a meaningful intent");
    }

    @Test
    void prepareUserIntentFromGetMoreToolsContext() {
        McpSchema.CallToolRequest req = request(GetMoreTools.GET_MORE_TOOLS_NAME,
                "context", "I need a CSV export tool");
        ToolCallWrapper.PreparedCall call = ToolCallWrapper.prepare(
                req, null, GetMoreTools.GET_MORE_TOOLS_NAME, null, null, defaultOpts());
        assertEquals("I need a CSV export tool", call.callArgs().userIntent(),
                "get_more_tools context must be captured as userIntent");
    }

    // ── Hook futures — fired on prepare, null when no hook ───────────────────

    @Test
    void prepareHookFuturesAreNullWhenHooksNotConfigured() {
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, defaultOpts());
        assertNull(call.pendingHooks().pendingSessionId(),   "pendingSessionId must be null when no resolveSessionId hook");
        assertNull(call.pendingHooks().pendingIdentity(),    "pendingIdentity must be null when no identify hook");
        assertNull(call.pendingHooks().pendingTags(),        "pendingTags must be null when no eventTags hook");
        assertNull(call.pendingHooks().pendingProperties(),  "pendingProperties must be null when no eventProperties hook");
    }

    @Test
    void prepareFiresIdentifyHookImmediately() {
        AtomicBoolean hookCalled = new AtomicBoolean(false);
        AgentCatOptions opts = defaultOpts();
        opts.setIdentify((req, extra) -> {
            hookCalled.set(true);
            return CompletableFuture.completedFuture(null);
        });
        ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, opts);
        assertTrue(hookCalled.get(), "identify hook must be invoked during prepare()");
    }

    @Test
    void prepareFiresResolveSessionIdHookImmediately() {
        AtomicBoolean hookCalled = new AtomicBoolean(false);
        AgentCatOptions opts = defaultOpts();
        opts.setResolveSessionId((req, extra) -> {
            hookCalled.set(true);
            return CompletableFuture.completedFuture("user-123");
        });
        ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, opts);
        assertTrue(hookCalled.get(), "resolveSessionId hook must be invoked during prepare()");
    }

    @Test
    void prepareFiresEventTagsHookImmediately() {
        AtomicBoolean hookCalled = new AtomicBoolean(false);
        AgentCatOptions opts = defaultOpts();
        opts.setEventTags((req, extra) -> {
            hookCalled.set(true);
            return CompletableFuture.completedFuture(Map.of("env", "test"));
        });
        ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, opts);
        assertTrue(hookCalled.get(), "eventTags hook must be invoked during prepare()");
    }

    @Test
    void prepareFiresEventPropertiesHookImmediately() {
        AtomicBoolean hookCalled = new AtomicBoolean(false);
        AgentCatOptions opts = defaultOpts();
        opts.setEventProperties((req, extra) -> {
            hookCalled.set(true);
            return CompletableFuture.completedFuture(Map.of("version", "2.0"));
        });
        ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, opts);
        assertTrue(hookCalled.get(), "eventProperties hook must be invoked during prepare()");
    }

    @Test
    void preparePendingIdentityFutureIsNonNullWhenHookConfigured() {
        AgentCatOptions opts = defaultOpts();
        opts.setIdentify((req, extra) -> CompletableFuture.completedFuture(null));
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, opts);
        assertNotNull(call.pendingHooks().pendingIdentity(),
                "pendingIdentity future must be non-null when identify hook is configured");
    }

    @Test
    void preparePendingTagsFutureIsNonNullWhenHookConfigured() {
        AgentCatOptions opts = defaultOpts();
        opts.setEventTags((req, extra) -> CompletableFuture.completedFuture(null));
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, opts);
        assertNotNull(call.pendingHooks().pendingTags(),
                "pendingTags future must be non-null when eventTags hook is configured");
    }

    // ── Fault containment in prepare ─────────────────────────────────────────

    @Test
    void prepareDoesNotThrowWhenIdentifyHookThrowsSynchronously() {
        AgentCatOptions opts = defaultOpts();
        opts.setIdentify((req, extra) -> {
            throw new RuntimeException("identify hook exploded");
        });
        assertDoesNotThrow(
                () -> ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, opts),
                "prepare() must not propagate synchronous hook exceptions");
    }

    @Test
    void prepareDoesNotThrowWhenEventTagsHookThrowsSynchronously() {
        AgentCatOptions opts = defaultOpts();
        opts.setEventTags((req, extra) -> {
            throw new RuntimeException("eventTags hook exploded");
        });
        assertDoesNotThrow(
                () -> ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, opts),
                "prepare() must not propagate synchronous hook exceptions");
    }

    @Test
    void prepareDoesNotThrowWhenResolveSessionIdHookThrowsSynchronously() {
        AgentCatOptions opts = defaultOpts();
        opts.setResolveSessionId((req, extra) -> {
            throw new RuntimeException("resolveSessionId hook exploded");
        });
        assertDoesNotThrow(
                () -> ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, opts),
                "prepare() must not propagate synchronous hook exceptions");
    }

    @Test
    void preparePendingFutureResolvesToNullWhenHookThrowsSynchronously() throws Exception {
        AgentCatOptions opts = defaultOpts();
        opts.setIdentify((req, extra) -> {
            throw new RuntimeException("identify exploded");
        });
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, opts);
        // The future must complete (resolved to null after swallowing the exception)
        assertNotNull(call.pendingHooks().pendingIdentity(), "pendingIdentity future must still be non-null");
        assertNull(call.pendingHooks().pendingIdentity().get(),
                "pendingIdentity must resolve to null when hook threw synchronously");
    }

    @Test
    void preparePendingFutureResolvesToNullWhenHookFutureFailsAsynchronously() throws Exception {
        // invokeHook() attaches .exceptionally() — async failures must be swallowed,
        // not propagated to the caller.
        AgentCatOptions opts = defaultOpts();
        opts.setIdentify((req, extra) -> {
            CompletableFuture<com.agentcat.sdk.model.UserIdentity> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("async identify boom"));
            return f;
        });
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, opts);
        assertNotNull(call.pendingHooks().pendingIdentity());
        // Future must be resolved (via exceptionally) — get() must not throw
        assertDoesNotThrow(() -> call.pendingHooks().pendingIdentity().get(),
                "async hook failure must not cause pendingIdentity.get() to throw");
        assertNull(call.pendingHooks().pendingIdentity().get(),
                "async hook failure must resolve pendingIdentity to null");
    }

    @Test
    void preparePendingTagsResolvesToNullWhenHookFutureFailsAsynchronously() throws Exception {
        AgentCatOptions opts = defaultOpts();
        opts.setEventTags((req, extra) -> {
            CompletableFuture<Map<String, String>> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("async tags boom"));
            return f;
        });
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, opts);
        assertNotNull(call.pendingHooks().pendingTags());
        assertDoesNotThrow(() -> call.pendingHooks().pendingTags().get());
        assertNull(call.pendingHooks().pendingTags().get(),
                "async eventTags failure must resolve pendingTags to null");
    }

    @Test
    void preparePendingPropertiesResolvesToNullWhenHookFutureFailsAsynchronously() throws Exception {
        AgentCatOptions opts = defaultOpts();
        opts.setEventProperties((req, extra) -> {
            CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("async props boom"));
            return f;
        });
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, opts);
        assertNotNull(call.pendingHooks().pendingProperties());
        assertDoesNotThrow(() -> call.pendingHooks().pendingProperties().get());
        assertNull(call.pendingHooks().pendingProperties().get());
    }

    @Test
    void preparePendingSessionIdResolvesToNullWhenHookFutureFailsAsynchronously() throws Exception {
        AgentCatOptions opts = defaultOpts();
        opts.setResolveSessionId((req, extra) -> {
            CompletableFuture<String> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("async session boom"));
            return f;
        });
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, opts);
        assertNotNull(call.pendingHooks().pendingSessionId());
        assertDoesNotThrow(() -> call.pendingHooks().pendingSessionId().get());
        assertNull(call.pendingHooks().pendingSessionId().get());
    }

    // ── clientName / clientVersion (extractClientInfoFromSession) ────────────

    @Test
    void prepareClientNameIsNullWhenExtraIsNull() {
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, defaultOpts());
        assertNull(call.serverContext().clientName(),
                "clientName must be null when extra is null (no exchange present)");
    }

    @Test
    void prepareClientVersionIsNullWhenExtraIsNull() {
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, defaultOpts());
        assertNull(call.serverContext().clientVersion(),
                "clientVersion must be null when extra is null");
    }

    @Test
    void prepareClientNameIsNullWhenExtraIsNotAnExchange() {
        // A non-null extra that is NOT a McpAsyncServerExchange must not reach the
        // exchange-specific branch — clientName must remain null.
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), "not_an_exchange", "echo", null, null, defaultOpts());
        assertNull(call.serverContext().clientName(),
                "clientName must be null when extra is not a McpAsyncServerExchange");
    }

    @Test
    void prepareClientVersionIsNullWhenExtraIsNotAnExchange() {
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), 42, "echo", null, null, defaultOpts());
        assertNull(call.serverContext().clientVersion(),
                "clientVersion must be null when extra is an arbitrary non-exchange object");
    }

    @Test
    void prepareDoesNotThrowWhenExtraIsArbitraryObject() {
        // Passing any non-exchange object must not cause an exception inside prepare()
        assertDoesNotThrow(() ->
                ToolCallWrapper.prepare(emptyRequest("echo"), new Object(), "echo", null, null, defaultOpts()),
                "prepare() must not throw when extra is an unrecognised object type");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // captureArgs() tests
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void captureArgsReturnsEmptyMapForNullArguments() {
        McpSchema.CallToolRequest req = new McpSchema.CallToolRequest("echo", null);
        Map<String, Object> captured = ToolCallWrapper.captureArgs(req);
        assertNotNull(captured, "captureArgs must never return null");
        assertTrue(captured.isEmpty(), "null arguments must produce an empty map");
    }

    @Test
    void captureArgsReturnsEmptyMapForEmptyArguments() {
        McpSchema.CallToolRequest req = requestFromMap("echo", Map.of());
        Map<String, Object> captured = ToolCallWrapper.captureArgs(req);
        assertTrue(captured.isEmpty());
    }

    @Test
    void captureArgsPreservesAllEntries() {
        Map<String, Object> original = args("session_id", "start", "my_param", "hello", "count", 42);
        Map<String, Object> captured = ToolCallWrapper.captureArgs(requestFromMap("echo", original));
        assertEquals("start", captured.get("session_id"));
        assertEquals("hello", captured.get("my_param"));
        assertEquals(42,      captured.get("count"));
    }

    @Test
    void captureArgsIsImmutable() {
        Map<String, Object> original = new HashMap<>();
        original.put("key", "value");
        Map<String, Object> captured = ToolCallWrapper.captureArgs(requestFromMap("echo", original));
        assertThrows(UnsupportedOperationException.class,
                () -> captured.put("new_key", "new_value"),
                "captureArgs must return an unmodifiable snapshot");
    }

    @Test
    void captureArgsSnapshotIsIndependentOfOriginal() {
        Map<String, Object> original = new HashMap<>();
        original.put("key", "before");
        Map<String, Object> captured = ToolCallWrapper.captureArgs(requestFromMap("echo", original));
        original.put("key", "after"); // mutate original
        assertEquals("before", captured.get("key"),
                "captured snapshot must not reflect later mutations to the original map");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // stripHandleArgs() tests
    // ══════════════════════════════════════════════════════════════════════════

    // ── enableTracing=false ───────────────────────────────────────────────────

    @Test
    void stripHandleArgsReturnsOriginalWhenTracingDisabled() {
        AgentCatOptions opts = defaultOpts();
        opts.setEnableTracing(false);
        Map<String, Object> original = args("session_id", "start", "input", "hello");
        Map<String, Object> stripped = ToolCallWrapper.stripHandleArgs(original, "echo", opts);
        assertSame(original, stripped,
                "when enableTracing=false, the original map reference must be returned unchanged");
    }

    @Test
    void stripHandleArgsReturnsOriginalWhenArgsEmpty() {
        Map<String, Object> empty = Collections.emptyMap();
        Map<String, Object> stripped = ToolCallWrapper.stripHandleArgs(empty, "echo", defaultOpts());
        assertSame(empty, stripped,
                "when args is empty, the original map reference must be returned unchanged");
    }

    // ── session_id ────────────────────────────────────────────────────────────

    @Test
    void stripsSessionIdFromArgs() {
        Map<String, Object> a = args("session_id", "start", "real_param", "value");
        Map<String, Object> stripped = ToolCallWrapper.stripHandleArgs(a, "echo", defaultOpts());
        assertFalse(stripped.containsKey("session_id"),
                "session_id must be stripped from tool args");
    }

    @Test
    void stripsSessionIdAndPreservesOtherParams() {
        Map<String, Object> a = args("session_id", "start", "real_param", "hello", "count", 7);
        Map<String, Object> stripped = ToolCallWrapper.stripHandleArgs(a, "echo", defaultOpts());
        assertFalse(stripped.containsKey("session_id"));
        assertEquals("hello", stripped.get("real_param"), "real_param must be preserved");
        assertEquals(7,        stripped.get("count"),      "count must be preserved");
    }

    @Test
    void doesNotStripSessionIdWhenAbsent() {
        Map<String, Object> a = args("real_param", "value");
        Map<String, Object> stripped = ToolCallWrapper.stripHandleArgs(a, "echo", defaultOpts());
        assertSame(a, stripped,
                "when session_id is absent there is nothing to strip — original reference must be returned");
    }

    @Test
    void strippedMapIsMutableLikeTheClientsArguments() {
        // The handler gets what it would get without AgentCat: a map it can change.
        Map<String, Object> a = args("session_id", "start", "input", "hi");
        Map<String, Object> stripped = ToolCallWrapper.stripHandleArgs(a, "echo", defaultOpts());
        assertDoesNotThrow(() -> stripped.put("new_key", "val"));
    }

    // ── agent_id ──────────────────────────────────────────────────────────────

    @Test
    void doesNotStripAgentIdWhenTrackingDisabledByDefault() {
        Map<String, Object> a = args("session_id", "start", "agent_id", "my-agent");
        AgentCatOptions opts = defaultOpts(); // enableAgentTracking=false by default
        Map<String, Object> stripped = ToolCallWrapper.stripHandleArgs(a, "echo", opts);
        assertTrue(stripped.containsKey("agent_id"),
                "agent_id must NOT be stripped when enableAgentTracking=false");
    }

    @Test
    void stripsAgentIdWhenTrackingEnabled() {
        Map<String, Object> a = args("session_id", "start", "agent_id", "my-agent", "input", "x");
        AgentCatOptions opts = defaultOpts();
        opts.setEnableAgentTracking(true);
        Map<String, Object> stripped = ToolCallWrapper.stripHandleArgs(a, "echo", opts);
        assertFalse(stripped.containsKey("agent_id"),
                "agent_id must be stripped when enableAgentTracking=true");
        assertFalse(stripped.containsKey("session_id"),
                "session_id must also be stripped");
        assertEquals("x", stripped.get("input"), "input must be preserved");
    }

    @Test
    void doesNotStripAgentIdWhenAbsentButTrackingEnabled() {
        Map<String, Object> a = args("session_id", "start", "input", "x");
        AgentCatOptions opts = defaultOpts();
        opts.setEnableAgentTracking(true);
        Map<String, Object> stripped = ToolCallWrapper.stripHandleArgs(a, "echo", opts);
        assertFalse(stripped.containsKey("agent_id"),
                "agent_id is not in args — must not appear in stripped map");
        assertEquals("x", stripped.get("input"));
    }

    // ── context ───────────────────────────────────────────────────────────────

    @Test
    void stripsContextWhenToolCallContextEnabled() {
        Map<String, Object> a = args("session_id", "start", "context", "doing something", "input", "v");
        AgentCatOptions opts = defaultOpts(); // enableToolCallContext=true by default
        Map<String, Object> stripped = ToolCallWrapper.stripHandleArgs(a, "echo", opts);
        assertFalse(stripped.containsKey("context"),
                "context must be stripped when enableToolCallContext=true");
        assertEquals("v", stripped.get("input"), "other params must be preserved");
    }

    @Test
    void doesNotStripContextWhenToolCallContextDisabled() {
        Map<String, Object> a = args("session_id", "start", "context", "doing something");
        AgentCatOptions opts = defaultOpts();
        opts.setEnableToolCallContext(false);
        Map<String, Object> stripped = ToolCallWrapper.stripHandleArgs(a, "echo", opts);
        assertTrue(stripped.containsKey("context"),
                "context must NOT be stripped when enableToolCallContext=false");
    }

    @Test
    void doesNotStripContextFromGetMoreTools() {
        Map<String, Object> a = args("context", "I need a DB query tool");
        AgentCatOptions opts = defaultOpts(); // enableToolCallContext=true
        Map<String, Object> stripped = ToolCallWrapper.stripHandleArgs(
                a, GetMoreTools.GET_MORE_TOOLS_NAME, opts);
        assertTrue(stripped.containsKey("context"),
                "get_more_tools owns its context field — must never be stripped");
    }

    // ── no mutation of original ───────────────────────────────────────────────

    @Test
    void stripHandleArgsDoesNotMutateOriginalMap() {
        Map<String, Object> original = new HashMap<>();
        original.put("session_id", "start");
        original.put("agent_id",   "my-agent");
        original.put("context",    "some context");
        original.put("real",       "value");
        int originalSize = original.size();

        AgentCatOptions opts = defaultOpts();
        opts.setEnableAgentTracking(true);
        ToolCallWrapper.stripHandleArgs(original, "echo", opts);

        assertEquals(originalSize, original.size(),
                "stripHandleArgs must not mutate the original args map");
        assertTrue(original.containsKey("session_id"), "session_id still in original");
        assertTrue(original.containsKey("agent_id"),   "agent_id still in original");
        assertTrue(original.containsKey("context"),    "context still in original");
        assertEquals("value", original.get("real"),    "real param still in original");
    }

    // ── all three stripped together ───────────────────────────────────────────

    @Test
    void stripsAllThreeHandleParamsTogether() {
        AgentCatOptions opts = defaultOpts();
        opts.setEnableAgentTracking(true);
        opts.setEnableToolCallContext(true);

        Map<String, Object> a = args(
                "session_id", "start",
                "agent_id",   "agent-42",
                "context",    "finding data",
                "payload",    "hello");

        Map<String, Object> stripped = ToolCallWrapper.stripHandleArgs(a, "echo", opts);

        assertFalse(stripped.containsKey("session_id"), "session_id must be stripped");
        assertFalse(stripped.containsKey("agent_id"),   "agent_id must be stripped");
        assertFalse(stripped.containsKey("context"),    "context must be stripped");
        assertEquals("hello", stripped.get("payload"),  "payload must be preserved");
        assertEquals(1, stripped.size(), "only payload must remain");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // scheduleAnalytics() — fire-and-forget fault containment
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void scheduleAnalyticsDoesNotThrowWithSuccessResult() {
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, defaultOpts());
        CallToolResult result = CallToolResult.builder().addTextContent("ok").build();
        assertDoesNotThrow(() ->
                ToolCallWrapper.scheduleAnalytics("proj", defaultOpts(), call, result, null),
                "scheduleAnalytics must never throw — it is fire-and-forget");
    }

    @Test
    void scheduleAnalyticsDoesNotThrowWhenResultIsNull() {
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, defaultOpts());
        assertDoesNotThrow(() ->
                ToolCallWrapper.scheduleAnalytics("proj", defaultOpts(), call, null, null),
                "scheduleAnalytics must not throw when result is null");
    }

    @Test
    void scheduleAnalyticsDoesNotThrowWhenErrorIsProvided() {
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, defaultOpts());
        assertDoesNotThrow(() ->
                ToolCallWrapper.scheduleAnalytics("proj", defaultOpts(), call, null,
                        new RuntimeException("tool failed")),
                "scheduleAnalytics must not throw when an error is passed");
    }

    @Test
    void scheduleAnalyticsDoesNotThrowWhenProjectIdIsNull() {
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, defaultOpts());
        // Null projectId should not cause scheduleAnalytics itself to throw
        assertDoesNotThrow(() ->
                ToolCallWrapper.scheduleAnalytics(null, defaultOpts(), call, null, null),
                "scheduleAnalytics must be fault-contained even with null project/server info");
    }

    @Test
    void scheduleAnalyticsDoesNotThrowWhenAnalyticsAreDisabled() {
        AgentCatOptions opts = defaultOpts();
        opts.setEnableTracing(false);
        ToolCallWrapper.PreparedCall call =
                ToolCallWrapper.prepare(emptyRequest("echo"), null, "echo", null, null, opts);
        CallToolResult result = CallToolResult.builder().addTextContent("ok").build();
        assertDoesNotThrow(() ->
                ToolCallWrapper.scheduleAnalytics("proj", opts, call, result, null),
                "scheduleAnalytics must not throw when tracing is disabled");
    }
}
