package com.agentcat.sdk.modules.handles;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * KSUID-based session identifier utilities for AgentCat.
 *
 * <h2>Format</h2>
 * <pre>ses_{27 base-62 chars}</pre>
 * Total length: 31 characters.
 *
 * <h3>Binary layout (20 bytes)</h3>
 * <ul>
 *   <li>Bytes 0–3: big-endian uint32 timestamp = {@code (currentMs − EPOCH_MS) / 1000}</li>
 *   <li>Bytes 4–19: 16 cryptographically random bytes</li>
 * </ul>
 *
 * <h3>Encoding</h3>
 * The 20-byte payload is treated as a positive big-endian integer and encoded in base-62
 * ({@code 0–9A–Za–z}) left-padded to exactly 27 characters. The {@code ses_} prefix is prepended.
 */
public final class SessionIdProvider {

    // ── Constants ────────────────────────────────────────────────────────────

    /** Base-62 alphabet: digits, upper-case, lower-case — in that order (KSUID convention). */
    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /** Number of base-62 characters in the encoded payload. */
    private static final int BASE62_LEN = 27;

    private static final BigInteger BASE = BigInteger.valueOf(62);

    /** KSUID epoch: 2014-05-13T16:53:20 UTC in milliseconds. */
    private static final long KSUID_EPOCH_MS = 1_400_000_000_000L;

    /** Maximum value of the uint32 timestamp field. */
    private static final long UINT32_MAX = 0xFFFFFFFFL;

    /** The sentinel value that instructs the SDK to mint a fresh session. Case-insensitive. */
    public static final String START_SENTINEL = "start";

    /** Jan 1 2024 00:00:00 UTC in milliseconds — fixed base for deterministic derivation. */
    private static final long EPOCH_2024_MS = 1_704_067_200_000L;

    /** One year in milliseconds — modulus for the hash-derived timestamp offset. */
    private static final long ONE_YEAR_MS = 365L * 24 * 60 * 60 * 1000;

    /** Pre-compiled validation pattern. */
    private static final Pattern VALID = Pattern.compile("^ses_[0-9A-Za-z]{27}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private SessionIdProvider() {}

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Mints a fresh session identifier.
     *
     * @return a new {@code ses_XXXXXXXXXXXXXXXXXXXXXXXX} string (31 chars)
     */
    public static String newSessionId() {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        return "ses_" + toBase62(buildKsuidPayload(System.currentTimeMillis(), random));
    }

    /**
     * Mints a fresh event identifier: {@code evt_} plus a KSUID, as in TS
     * ({@code KSUID.withPrefix("evt")}), so event IDs sort by time.
     *
     * @return a new {@code evt_} string (31 chars)
     */
    public static String newEventId() {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        return "evt_" + toBase62(buildKsuidPayload(System.currentTimeMillis(), random));
    }

    /**
     * Builds a 20-byte KSUID payload from a millisecond timestamp and 16 random bytes.
     *
     * <p>Layout: bytes [0–3] = uint32 big-endian seconds since KSUID epoch;
     * bytes [4–19] = caller-supplied random.
     */
    private static byte[] buildKsuidPayload(long nowMs, byte[] random) {
        long offsetSec = (nowMs - KSUID_EPOCH_MS) / 1000L;
        long ts        = Math.max(0, Math.min(offsetSec, UINT32_MAX));

        byte[] payload = new byte[20];
        payload[0] = (byte) ((ts >> 24) & 0xFF);
        payload[1] = (byte) ((ts >> 16) & 0xFF);
        payload[2] = (byte) ((ts >>  8) & 0xFF);
        payload[3] = (byte) ( ts        & 0xFF);
        System.arraycopy(random, 0, payload, 4, 16);
        return payload;
    }

    /**
     * Returns {@code true} if {@code id} matches the {@code ses_[0-9A-Za-z]{27}} pattern.
     *
     * <p>The {@link #START_SENTINEL} is intentionally <em>not</em> valid — callers must handle
     * it separately before invoking this method.
     */
    public static boolean isValid(String id) {
        return id != null && VALID.matcher(id).matches();
    }

    /**
     * Derives a deterministic session ID from a customer-supplied hook value and project ID.
     *
     * <p>Algorithm: {@code SHA-256(hookValue + ":" + projectId)} → 20-byte payload →
     * base-62 encoded KSUID. The same input always produces the same session ID.
     * This mirrors the TypeScript SDK's {@code deriveSessionId} behaviour.
     *
     * @param hookValue the raw string returned by the {@code resolveSessionId} hook
     * @param projectId the AgentCat project ID (used to namespace across projects)
     * @return a deterministic {@code ses_XXXXX} session ID
     */
    public static String deriveSessionId(String hookValue, String projectId) {
        try {
            // Mirrors TypeScript: input = projectId ? `${id}:${projectId}` : id
            String input = (projectId != null && !projectId.isEmpty())
                    ? hookValue + ":" + projectId
                    : hookValue;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            // Mirrors TypeScript algorithm:
            //   timestampOffset = hash.readUInt32BE(0) % (365 * 24 * 60 * 60 * 1000)
            //   timeInMs = EPOCH_2024 + timestampOffset
            //   timestamp = Math.floor((timeInMs - KSUID_EPOCH_IN_MS) / 1000)  [uint32 big-endian]
            //   payload = hash[4..19]
            long uint32 = ((hash[0] & 0xFFL) << 24) | ((hash[1] & 0xFFL) << 16)
                        | ((hash[2] & 0xFFL) <<  8) |  (hash[3] & 0xFFL);
            long timestampOffset = uint32 % ONE_YEAR_MS;
            long timeInMs = EPOCH_2024_MS + timestampOffset;
            long ts = (timeInMs - KSUID_EPOCH_MS) / 1000L;

            byte[] payload = new byte[20];
            payload[0] = (byte) ((ts >> 24) & 0xFF);
            payload[1] = (byte) ((ts >> 16) & 0xFF);
            payload[2] = (byte) ((ts >>  8) & 0xFF);
            payload[3] = (byte) ( ts        & 0xFF);
            System.arraycopy(hash, 4, payload, 4, 16);
            return "ses_" + toBase62(payload);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in any JVM
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * Encodes {@code bytes} as a base-62 string, left-padded to {@link #BASE62_LEN} characters.
     */
    private static String toBase62(byte[] bytes) {
        BigInteger value = new BigInteger(1, bytes); // positive interpretation
        StringBuilder sb  = new StringBuilder(BASE62_LEN);

        while (value.signum() > 0) {
            BigInteger[] dr = value.divideAndRemainder(BASE);
            sb.insert(0, ALPHABET.charAt(dr[1].intValue()));
            value = dr[0];
        }

        while (sb.length() < BASE62_LEN) {
            sb.insert(0, '0');
        }

        return sb.toString();
    }
}
