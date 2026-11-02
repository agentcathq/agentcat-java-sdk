package com.agentcat.sdk.modules.handles;

import com.agentcat.sdk.model.HandleResolution;
import com.agentcat.sdk.model.SessionSource;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MintBackHandler} — text block generation, content prepending,
 * structured mint-back building, and structured mirroring.
 */
class MintBackHandlerTest {

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static McpSchema.CallToolResult simpleResult(String text) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(text)))
                .isError(false)
                .build();
    }

    private static HandleResolution minted() {
        return new HandleResolution("ses_" + "b".repeat(27), SessionSource.MINTED, null);
    }

    private static HandleResolution mintedWithAgent() {
        return new HandleResolution("ses_" + "c".repeat(27), SessionSource.MINTED, "agent-42");
    }

    private static HandleResolution supplied() {
        return new HandleResolution("ses_" + "d".repeat(27), SessionSource.SUPPLIED, null);
    }

    private static HandleResolution suppliedWithAgent() {
        return new HandleResolution("ses_" + "e".repeat(27), SessionSource.SUPPLIED, "agent-99");
    }

    private static HandleResolution invalid() {
        return new HandleResolution("ses_" + "f".repeat(27), SessionSource.INVALID, null);
    }

    private static HandleResolution invalidWithAgent() {
        return new HandleResolution("ses_" + "g".repeat(27), SessionSource.INVALID, "agent-77");
    }

    private static HandleResolution hookModeNoAgent() {
        return new HandleResolution("", SessionSource.HOOK_MODE, null);
    }

    private static HandleResolution hookModeWithAgent() {
        return new HandleResolution("", SessionSource.HOOK_MODE, "hook-agent");
    }

    // ── buildMintBackText ─────────────────────────────────────────────────────────

    @Test
    void mintedTextContainsIssuedHeader() {
        String text = MintBackHandler.buildMintBackText(minted());
        assertNotNull(text);
        assertTrue(text.contains("[session_id issued"), "MINTED text must start with issued header");
    }

    @Test
    void mintedTextStartsWithIssuedHeader() {
        String text = MintBackHandler.buildMintBackText(minted());
        assertNotNull(text);
        assertTrue(text.startsWith("[session_id issued"), "first line must be the issued header");
    }

    @Test
    void mintedTextSecondLineContainsSessionId() {
        HandleResolution h = minted();
        String text = MintBackHandler.buildMintBackText(h);
        assertNotNull(text);
        String[] lines = text.split("\n");
        assertTrue(lines.length >= 2, "must have at least 2 lines");
        assertEquals("session_id: " + h.getSessionId(), lines[1]);
    }

    @Test
    void mintedTextContainsBodyText() {
        String text = MintBackHandler.buildMintBackText(minted());
        assertNotNull(text);
        assertTrue(text.contains("first-call issuance") || text.length() > 50,
                "MINTED text must include a body explanation");
    }

    @Test
    void suppliedTextIsNull() {
        assertNull(MintBackHandler.buildMintBackText(supplied()),
                "SUPPLIED session must produce null text (no announcement needed)");
    }

    @Test
    void hookModeTextIsNull() {
        assertNull(MintBackHandler.buildMintBackText(hookModeNoAgent()),
                "HOOK_MODE must produce null text");
    }

    @Test
    void invalidTextStartsWithUnrecognizedHeader() {
        String text = MintBackHandler.buildMintBackText(invalid());
        assertNotNull(text);
        assertTrue(text.startsWith("[session_id unrecognized"),
                "INVALID text must start with unrecognized header");
    }

    @Test
    void invalidTextContainsSendStartInstruction() {
        String text = MintBackHandler.buildMintBackText(invalid());
        assertNotNull(text);
        assertTrue(text.contains("send start"), "INVALID text must instruct to send start");
    }

    @Test
    void invalidTextDoesNotContainSesPrefix() {
        String text = MintBackHandler.buildMintBackText(invalid());
        assertNotNull(text);
        // The minted replacement ID must not leak into the INVALID announcement
        assertFalse(text.contains("ses_"), "INVALID text must not echo any ses_ value");
    }

    // ── appendMintBack ────────────────────────────────────────────────────────────

    @Test
    void appendMintBackPrependsTextAsFirstContent() {
        McpSchema.CallToolResult result = simpleResult("original");
        McpSchema.CallToolResult updated = MintBackHandler.appendMintBack(result, "BLOCK");
        McpSchema.Content first = updated.content().get(0);
        assertTrue(first instanceof McpSchema.TextContent, "first element must be TextContent");
        assertEquals("BLOCK", ((McpSchema.TextContent) first).text());
    }

    @Test
    void appendMintBackOriginalContentFollows() {
        McpSchema.CallToolResult result = simpleResult("original");
        McpSchema.CallToolResult updated = MintBackHandler.appendMintBack(result, "BLOCK");
        assertEquals(2, updated.content().size(), "updated result must have 2 content elements");
        McpSchema.Content second = updated.content().get(1);
        assertTrue(second instanceof McpSchema.TextContent);
        assertEquals("original", ((McpSchema.TextContent) second).text());
    }

    @Test
    void appendMintBackDoesNotMutateOriginal() {
        McpSchema.CallToolResult result = simpleResult("original");
        int originalSize = result.content().size();
        MintBackHandler.appendMintBack(result, "BLOCK");
        assertEquals(originalSize, result.content().size(),
                "original result content must not be mutated");
    }

    @Test
    void appendMintBackWorksOnErrorResult() {
        McpSchema.CallToolResult errResult = McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("error detail")))
                .isError(true)
                .build();
        McpSchema.CallToolResult updated = MintBackHandler.appendMintBack(errResult, "BLOCK");
        assertNotNull(updated);
        assertEquals(2, updated.content().size());
        assertTrue(updated.isError(), "isError flag must be preserved");
    }

    // ── buildStructuredMintBack ───────────────────────────────────────────────────

    @Test
    void mintedStructuredContainsSessionId() {
        Map<String, Object> m = MintBackHandler.buildStructuredMintBack(minted());
        assertNotNull(m);
        assertTrue(m.containsKey("session_id"));
    }

    @Test
    void mintedStructuredStatusIsIssued() {
        Map<String, Object> m = MintBackHandler.buildStructuredMintBack(minted());
        assertNotNull(m);
        assertEquals("issued", m.get("status"));
    }

    @Test
    void mintedWithAgentStructuredContainsAgentId() {
        Map<String, Object> m = MintBackHandler.buildStructuredMintBack(mintedWithAgent());
        assertNotNull(m);
        assertEquals("agent-42", m.get("agent_id"));
    }

    @Test
    void suppliedStructuredContainsSessionId() {
        Map<String, Object> m = MintBackHandler.buildStructuredMintBack(supplied());
        assertNotNull(m);
        assertTrue(m.containsKey("session_id"));
    }

    @Test
    void suppliedStructuredStatusIsActive() {
        Map<String, Object> m = MintBackHandler.buildStructuredMintBack(supplied());
        assertNotNull(m);
        assertEquals("active", m.get("status"));
    }

    @Test
    void invalidStructuredHasNoSessionId() {
        Map<String, Object> m = MintBackHandler.buildStructuredMintBack(invalid());
        assertNotNull(m);
        assertFalse(m.containsKey("session_id"),
                "INVALID must not echo a session_id in structured content");
    }

    @Test
    void invalidStructuredStatusIsUnrecognized() {
        Map<String, Object> m = MintBackHandler.buildStructuredMintBack(invalid());
        assertNotNull(m);
        assertEquals("unrecognized", m.get("status"));
    }

    @Test
    void invalidWithAgentStructuredContainsAgentId() {
        Map<String, Object> m = MintBackHandler.buildStructuredMintBack(invalidWithAgent());
        assertNotNull(m);
        assertEquals("agent-77", m.get("agent_id"));
    }

    @Test
    void hookModeNoAgentReturnsNull() {
        assertNull(MintBackHandler.buildStructuredMintBack(hookModeNoAgent()),
                "HOOK_MODE with no agent_id must return null (nothing to report)");
    }

    @Test
    void hookModeWithAgentContainsAgentId() {
        Map<String, Object> m = MintBackHandler.buildStructuredMintBack(hookModeWithAgent());
        assertNotNull(m);
        assertEquals("hook-agent", m.get("agent_id"));
    }

    @Test
    void hookModeWithAgentHasNoSessionId() {
        Map<String, Object> m = MintBackHandler.buildStructuredMintBack(hookModeWithAgent());
        assertNotNull(m);
        assertFalse(m.containsKey("session_id"),
                "HOOK_MODE must not include session_id");
    }

    @Test
    void hookModeWithAgentHasNoStatus() {
        Map<String, Object> m = MintBackHandler.buildStructuredMintBack(hookModeWithAgent());
        assertNotNull(m);
        assertFalse(m.containsKey("status"),
                "HOOK_MODE must not include status");
    }

    @Test
    void mintedStructuredKeyOrderSessionIdFirst() {
        Map<String, Object> m = MintBackHandler.buildStructuredMintBack(mintedWithAgent());
        assertNotNull(m);
        List<String> keys = List.copyOf(m.keySet());
        assertEquals("session_id", keys.get(0), "session_id must be the first key");
        assertEquals("agent_id", keys.get(1), "agent_id must be the second key");
        assertEquals("status", keys.get(2), "status must be the third key");
    }

    // ── mirrorStructuredMintBack ──────────────────────────────────────────────────

    @Test
    void mirrorAddsMcpSessionAsFirstKey() {
        Map<String, Object> existingSc = new LinkedHashMap<>();
        existingSc.put("foo", "bar");
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("hi")))
                .isError(false)
                .structuredContent(existingSc)
                .build();
        Map<String, Object> mint = Map.of("session_id", "ses_xxx");
        McpSchema.CallToolResult updated = MintBackHandler.mirrorStructuredMintBack(result, mint);

        @SuppressWarnings("unchecked")
        Map<String, Object> sc = (Map<String, Object>) updated.structuredContent();
        assertNotNull(sc);
        String firstKey = sc.keySet().iterator().next();
        assertEquals(MintBackHandler.MCP_SESSION_KEY, firstKey,
                "mcp_session must be the first key in structuredContent");
    }

    @Test
    void mirrorDoesNotMutateOriginalResult() {
        Map<String, Object> existingSc = new LinkedHashMap<>();
        existingSc.put("foo", "bar");
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("hi")))
                .isError(false)
                .structuredContent(existingSc)
                .build();
        Map<String, Object> mint = Map.of("session_id", "ses_xxx");
        MintBackHandler.mirrorStructuredMintBack(result, mint);

        @SuppressWarnings("unchecked")
        Map<String, Object> sc = (Map<String, Object>) result.structuredContent();
        assertFalse(sc.containsKey(MintBackHandler.MCP_SESSION_KEY),
                "original result structuredContent must not be mutated");
    }

    @Test
    void mirrorReturnsOriginalWhenMcpSessionKeyAlreadyPresent() {
        Map<String, Object> existingSc = new LinkedHashMap<>();
        existingSc.put(MintBackHandler.MCP_SESSION_KEY, "customer-owned");
        existingSc.put("other", "data");
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("hi")))
                .isError(false)
                .structuredContent(existingSc)
                .build();
        Map<String, Object> mint = Map.of("session_id", "ses_xxx");
        McpSchema.CallToolResult returned = MintBackHandler.mirrorStructuredMintBack(result, mint);

        assertSame(result, returned,
                "when customer owns mcp_session key, original result must be returned unchanged");
    }

    @Test
    void mirrorReturnsOriginalWhenStructuredContentIsNull() {
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("hi")))
                .isError(false)
                .build();
        Map<String, Object> mint = Map.of("session_id", "ses_xxx");
        McpSchema.CallToolResult returned = MintBackHandler.mirrorStructuredMintBack(result, mint);
        assertSame(result, returned,
                "null structuredContent must return original result unchanged");
    }

    @Test
    void mirrorReturnsOriginalWhenStructuredContentIsList() {
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("hi")))
                .isError(false)
                .structuredContent(List.of("a", "b"))
                .build();
        Map<String, Object> mint = Map.of("session_id", "ses_xxx");
        McpSchema.CallToolResult returned = MintBackHandler.mirrorStructuredMintBack(result, mint);
        assertSame(result, returned,
                "List structuredContent must return original result unchanged");
    }
}
