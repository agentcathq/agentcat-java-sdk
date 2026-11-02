package com.agentcat.sdk.config;

/**
 * Compile-time constants for the AgentCat SDK.
 *
 * <p>All fields are {@code public static final}. There are no instances of this class.
 */
public final class AgentCatConstants {

    /** Version of the AgentCat Java SDK, reported on every event as {@code agentcat_version}. */
    public static final String AGENTCAT_SDK_VERSION = "0.1.0";

    /** Language tag reported on every event as {@code sdk_language}. */
    public static final String SDK_LANGUAGE = "java";

    /** Base URL of the AgentCat ingest API. */
    public static final String API_BASE_URL = "https://api.agentcat.com";

    /**
     * Length of the random suffix appended to generated event IDs.
     * Produces a 27-character alphanumeric string compatible with the KSUID-adjacent
     * format used across AgentCat IDs.
     */
    public static final int ID_SUFFIX_LENGTH = 27;

    /**
     * SEP version tag stamped on every event via {@code agentcat_protocol_version}.
     * Matches the TypeScript SDK constant {@code PROTOCOL_VERSION}.
     */
    public static final String PROTOCOL_VERSION = "SEP-2567";

    private AgentCatConstants() {}
}
