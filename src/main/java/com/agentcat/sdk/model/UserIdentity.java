package com.agentcat.sdk.model;

import java.util.Map;

/**
 * Identity of the actor behind a tool call, returned by the {@code identify} hook.
 *
 * <p>All fields are optional — supply only what is available. At minimum a
 * non-null {@code userId} is required for identity to be stamped on the event.
 */
public final class UserIdentity {

    /** Stable identifier for the actor (e.g. database primary key, email). */
    public final String userId;

    /** Human-readable display name for the actor. May be {@code null}. */
    public final String userName;

    /**
     * Arbitrary JSON-serialisable metadata about the actor (plan, company, etc.).
     * May be {@code null}.
     */
    public final Map<String, Object> userData;

    public UserIdentity(String userId) {
        this(userId, null, null);
    }

    public UserIdentity(String userId, String userName, Map<String, Object> userData) {
        this.userId   = userId;
        this.userName = userName;
        this.userData = userData;
    }
}
