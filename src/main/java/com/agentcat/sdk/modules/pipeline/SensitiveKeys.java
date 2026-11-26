package com.agentcat.sdk.modules.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Masks the values of sensitive keys (credential headers, tokens, secrets) in an event
 * payload, at any depth. A port of the shared AgentCat reference implementation; the test
 * {@code SensitiveKeysTest} checks it against the shared {@code sensitive-keys-vectors.json}.
 */
public final class SensitiveKeys {

    public static final String MASK = "[redacted]";

    // A key matches a name when its canonical form equals the name or ends with "-" + name.
    static final List<String> EXACT = List.of(
            "accesskey", "cb-access-sign", "cf-access-client-id", "dpop", "fastly-key",
            "jenkins-crumb", "paypal-transmission-sig", "plaid-verification",
            "sec-websocket-protocol", "x-airtable-content-mac", "x-amzn-ava-user-context",
            "x-amzn-oidc-data", "x-firebase-appcheck", "x-forge-oauth-system",
            "x-forge-oauth-user", "x-honeycomb-team", "x-insert-key", "x-jfrog-art-api",
            "x-nf-sign", "x-registry-config", "x-requestdigest", "x-sfdc-session",
            "x-trello-webhook", "x-vault-mfa", "x-vercel-protection-bypass", "x-wp-nonce");
    // A key matches when its canonical form contains the word.
    static final List<String> CONTAINS = List.of(
            "authorization", "secret", "password", "passwd", "passphrase", "cookie", "signature",
            "credential", "jwt", "assertion", "bearer", "csrf", "xsrf", "hmac", "saml",
            "api-key", "apikey", "access-key", "private-key", "privatekey", "subscription-key",
            "encryption-key", "signing-key", "shared-key", "sharedkey", "master-key",
            "account-key", "app-key", "appkey", "application-key", "functions-key", "sas-key",
            "license-key", "consumer-key", "client-key", "customer-key");
    // A key matches when one hyphen-separated part of its canonical form equals the word.
    static final List<String> SEGMENT = List.of("auth", "token", "otp");
    // A key matches when its canonical form ends with the word.
    static final List<String> SUFFIX = List.of("token");

    // The pair rule: a map that names a key in one entry and holds its value in another.
    static final List<String> PAIR_NAME_KEYS = List.of("name", "key", "header");
    static final List<String> PAIR_VALUE_KEYS = List.of("value", "values");
    // The children of a map stored under this exact key are JSON Schema property names.
    static final String SCHEMA_PROPERTIES_KEY = "properties";

    private SensitiveKeys() {}

    static String camelSplit(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (i > 0 && ch >= 'A' && ch <= 'Z') {
                char prev = text.charAt(i - 1);
                char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
                if ((prev >= 'a' && prev <= 'z') || (prev >= '0' && prev <= '9')) {
                    out.append('-');
                } else if ((prev >= 'A' && prev <= 'Z') && (next >= 'a' && next <= 'z')) {
                    out.append('-');
                }
            }
            out.append(ch);
        }
        return out.toString();
    }

    static List<String> canonicalParts(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                out.append((char) (ch + 32));
            } else if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                out.append(ch);
            } else {
                out.append('-');
            }
        }
        List<String> parts = new ArrayList<>();
        for (String p : out.toString().split("-", -1)) {
            if (!p.isEmpty()) parts.add(p);
        }
        while (!parts.isEmpty() && allDigits(parts.get(parts.size() - 1))) {
            parts.remove(parts.size() - 1);
        }
        return parts;
    }

    private static boolean allDigits(String part) {
        for (int i = 0; i < part.length(); i++) {
            if (part.charAt(i) < '0' || part.charAt(i) > '9') return false;
        }
        return true;
    }

    static boolean partsMatch(List<String> parts) {
        if (parts.isEmpty()) return false;
        String joined = String.join("-", parts);
        for (String name : EXACT) {
            if (joined.equals(name) || joined.endsWith("-" + name)) return true;
        }
        for (String word : CONTAINS) {
            if (joined.contains(word)) return true;
        }
        for (String part : parts) {
            if (SEGMENT.contains(part)) return true;
        }
        for (String word : SUFFIX) {
            if (joined.endsWith(word)) return true;
        }
        return false;
    }

    static boolean isSensitiveKey(String key) {
        return partsMatch(canonicalParts(key)) || partsMatch(canonicalParts(camelSplit(key)));
    }

    static String canonical(String key) {
        return String.join("-", canonicalParts(key));
    }

    private static Object maskValue(Object value, boolean underProperties, int depth) {
        if (value == null || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> && underProperties) {
            return maskSensitiveKeys(value, false, depth);
        }
        return MASK;
    }

    /** A masked copy of {@code value}. The input is never changed. */
    public static Object maskSensitiveKeys(Object value) {
        return maskSensitiveKeys(value, false, Truncator.MAX_DEPTH);
    }

    /**
     * {@code underProperties} is true when {@code value} is the map stored under the key
     * "properties". A map or list deeper than {@link Truncator#MAX_DEPTH}, where the
     * truncator stops, becomes {@link #MASK}.
     */
    private static Object maskSensitiveKeys(Object value, boolean underProperties, int depth) {
        if ((value instanceof List<?> || value instanceof Map<?, ?>) && depth <= 0) return MASK;
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(maskSensitiveKeys(item, false, depth - 1));
            return out;
        }
        if (!(value instanceof Map<?, ?> map)) return value;
        Map<Object, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() instanceof String key && isSensitiveKey(key)) {
                out.put(key, maskValue(e.getValue(), underProperties, depth - 1));
            } else {
                out.put(e.getKey(), maskSensitiveKeys(e.getValue(),
                        SCHEMA_PROPERTIES_KEY.equals(e.getKey()), depth - 1));
            }
        }
        boolean namesASensitiveKey = false;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() instanceof String key && PAIR_NAME_KEYS.contains(canonical(key))) {
                if (e.getValue() instanceof String child && isSensitiveKey(child)) {
                    namesASensitiveKey = true;
                }
            }
        }
        if (namesASensitiveKey) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() instanceof String key && PAIR_VALUE_KEYS.contains(canonical(key))) {
                    out.put(key, maskValue(e.getValue(), false, depth - 1));
                }
            }
        }
        return out;
    }
}
