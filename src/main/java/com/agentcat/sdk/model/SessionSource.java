package com.agentcat.sdk.model;

/**
 * Describes how the {@code session_id} for a given tool call was resolved.
 */
public enum SessionSource {

    /** No {@code session_id} in the request (or sentinel {@code "start"}) — a fresh KSUID was minted. */
    MINTED,

    /** Agent supplied a valid {@code ses_XXXXX} — echoed back as-is. */
    SUPPLIED,

    /** Agent sent an unrecognised value — a fresh KSUID was minted and a warning logged. */
    INVALID,

    /**
     * The tool declares its own {@code session_id} parameter — AgentCat never injected one,
     * so nothing in the arguments is ours to read. Sessionless until the customer adopts
     * {@code resolveSessionId}. Mirrors TypeScript SDK's {@code "foreign"} source.
     */
    FOREIGN,

    /**
     * The {@code resolveSessionId} hook is configured — session ownership belongs to the
     * customer. The {@code session_id} parameter is not injected into schemas and no
     * mint-back text is added to tool responses. The actual session ID is resolved
     * asynchronously in the event queue's Stage 0.
     */
    HOOK_MODE
}
