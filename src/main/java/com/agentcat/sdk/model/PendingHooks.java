package com.agentcat.sdk.model;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Groups the async hook futures and synchronous redaction functions that are
 * captured at tool-call time and resolved during event queue processing.
 *
 * <p>All fields are immutable references captured once per tool invocation.
 * Individual fields may be {@code null} when the corresponding hook is not configured.
 *
 * @param redactSensitiveInformation async string-level redaction hook
 * @param redactEvent                async event-level redaction hook
 * @param pendingSessionId           future from the {@code resolveSessionId} hook
 * @param pendingIdentity            future from the {@code identify} hook
 * @param pendingTags                future from the {@code eventTags} hook
 * @param pendingProperties          future from the {@code eventProperties} hook
 */
public record PendingHooks(
        Function<String, CompletableFuture<String>> redactSensitiveInformation,
        Function<Map<String, Object>, CompletableFuture<Map<String, Object>>> redactEvent,
        CompletableFuture<String> pendingSessionId,
        CompletableFuture<UserIdentity> pendingIdentity,
        CompletableFuture<Map<String, String>> pendingTags,
        CompletableFuture<Map<String, Object>> pendingProperties) {

    /** Convenience factory when no hooks are configured. */
    public static PendingHooks empty() {
        return new PendingHooks(null, null, null, null, null, null);
    }
}
