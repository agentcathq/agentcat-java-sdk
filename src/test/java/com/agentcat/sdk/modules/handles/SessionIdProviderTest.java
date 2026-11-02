package com.agentcat.sdk.modules.handles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SessionIdProvider} — minting, derivation, and validation.
 *
 * <p>Includes exhaustive boundary and character-class tests for
 * {@link SessionIdProvider#isValid(String)} ported from {@code SessionIdValidationTest}.
 */
class SessionIdProviderTest {

    private static final String SES_REGEX = "^ses_[0-9A-Za-z]{27}$";

    // ── newSessionId ────────────────────────────────────────────────────────────

    @Test
    void newSessionIdMatchesExpectedFormat() {
        String id = SessionIdProvider.newSessionId();
        assertTrue(id.matches(SES_REGEX), "newSessionId() must match ^ses_[0-9A-Za-z]{27}$, got: " + id);
    }

    @Test
    void newSessionIdHasCorrectTotalLength() {
        String id = SessionIdProvider.newSessionId();
        assertEquals(31, id.length(), "ses_ (4) + 27 chars = 31 total");
    }

    @Test
    void twoNewSessionIdsAreDifferent() {
        String id1 = SessionIdProvider.newSessionId();
        String id2 = SessionIdProvider.newSessionId();
        assertNotEquals(id1, id2, "consecutive newSessionId() calls must return distinct values");
    }

    // ── deriveSessionId ─────────────────────────────────────────────────────────

    @Test
    void deriveSessionIdGoldenVector() {
        String result = SessionIdProvider.deriveSessionId("customer-abc", "proj_1");
        assertEquals("ses_2cOHEO0LYGADMzRvWTXXVbbgxgm", result,
                "golden vector: deriveSessionId(\"customer-abc\", \"proj_1\")");
    }

    @Test
    void deriveSessionIdIsDeterministic() {
        String r1 = SessionIdProvider.deriveSessionId("customer-abc", "proj_1");
        String r2 = SessionIdProvider.deriveSessionId("customer-abc", "proj_1");
        assertEquals(r1, r2, "same input must always produce the same derived id");
    }

    @Test
    void deriveSessionIdDifferentProjectIdYieldsDifferentResult() {
        String r1 = SessionIdProvider.deriveSessionId("customer-abc", "proj_1");
        String r2 = SessionIdProvider.deriveSessionId("customer-abc", "proj_2");
        assertNotEquals(r1, r2, "different projectId must yield a different derived id");
    }

    @Test
    void deriveSessionIdDoesNotTrimHookValue() {
        String withSpaces = SessionIdProvider.deriveSessionId(" x ", "p");
        String withoutSpaces = SessionIdProvider.deriveSessionId("x", "p");
        assertNotEquals(withSpaces, withoutSpaces,
                "hookValue is used as-is without trimming");
    }

    @Test
    void deriveSessionIdResultMatchesSesFormat() {
        String result = SessionIdProvider.deriveSessionId("some-user", "some-project");
        assertTrue(result.matches(SES_REGEX),
                "derived id must match ses_ format, got: " + result);
    }

    // ── isValid ─────────────────────────────────────────────────────────────────

    @Test
    void isValidAcceptsWellFormedLowercaseId() {
        assertTrue(SessionIdProvider.isValid("ses_" + "a".repeat(27)));
    }

    @Test
    void isValidAcceptsNewSessionId() {
        assertTrue(SessionIdProvider.isValid(SessionIdProvider.newSessionId()));
    }

    @Test
    void isValidAcceptsDerivedSessionId() {
        assertTrue(SessionIdProvider.isValid(SessionIdProvider.deriveSessionId("x", "p")));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // isValid — exhaustive boundary and character-class tests
    // (ported from SessionIdValidationTest)
    // ══════════════════════════════════════════════════════════════════════════

    // ── Valid inputs ─────────────────────────────────────────────────────────

    @Test
    void acceptsAllUppercase27CharSuffix() {
        assertTrue(SessionIdProvider.isValid("ses_" + "A".repeat(27)));
    }

    @Test
    void acceptsAllDigits27CharSuffix() {
        assertTrue(SessionIdProvider.isValid("ses_" + "0".repeat(27)));
    }

    // ── Invalid: wrong length ────────────────────────────────────────────────

    @Test
    void rejectsSuffixTooShort() {
        assertFalse(SessionIdProvider.isValid("ses_" + "a".repeat(26)));
    }

    @Test
    void rejectsSuffixTooLong() {
        assertFalse(SessionIdProvider.isValid("ses_" + "a".repeat(28)));
    }

    @Test
    void rejectsPrefixOnly() {
        assertFalse(SessionIdProvider.isValid("ses_"));
    }

    // ── Invalid: wrong prefix / no prefix ───────────────────────────────────

    @Test
    void rejectsWrongPrefix() {
        assertFalse(SessionIdProvider.isValid("task_" + "a".repeat(27)));
    }

    @Test
    void rejectsNoPrefix() {
        assertFalse(SessionIdProvider.isValid("2xF9kQm3rTvB8nLpYw7ZcHd4Ke1"));
    }

    // ── Invalid: bad characters ──────────────────────────────────────────────

    @Test
    void rejectsHyphensInSuffix() {
        assertFalse(SessionIdProvider.isValid("ses_" + "-".repeat(27)));
    }

    @Test
    void rejectsSpaceInsideSuffix() {
        // "ses_ " + 26 a's = prefix + space + 26 chars (length 31 but invalid char)
        assertFalse(SessionIdProvider.isValid("ses_ " + "a".repeat(26)));
    }

    // ── Invalid: misc ────────────────────────────────────────────────────────

    @Test
    void rejectsEmptyString() {
        assertFalse(SessionIdProvider.isValid(""));
    }

    @Test
    void rejectsNull() {
        assertFalse(SessionIdProvider.isValid(null));
    }

    @Test
    void rejectsArbitraryAppSessionId() {
        assertFalse(SessionIdProvider.isValid("my-app-session-42"));
    }

    // ── start sentinel is NOT a valid session id ─────────────────────────────

    @Test
    void rejectsStartSentinel() {
        assertFalse(SessionIdProvider.isValid("start"),
                "\"start\" is a sentinel, not a valid session id");
    }

    // ── Parameterized reject cases ───────────────────────────────────────────

    @ParameterizedTest(name = "isValid(\"{0}\") must be false")
    @ValueSource(strings = {
            "ses_aaaaaaaaaaaaaaaaaaaaaaaaaa",     // 26-char suffix (too short, total 30)
            "ses_aaaaaaaaaaaaaaaaaaaaaaaaaaaa",   // 28-char suffix (too long, total 32)
            "ses_",
            "task_aaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "ses_---------------------------",
            "START",
            "restart",
            "my-app-session-42",
            "2xF9kQm3rTvB8nLpYw7ZcHd4Ke1"
    })
    void rejectsInvalidIds(String id) {
        assertFalse(SessionIdProvider.isValid(id), "expected isValid(\"" + id + "\") == false");
    }
}
