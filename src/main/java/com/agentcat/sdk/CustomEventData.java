package com.agentcat.sdk;

import java.util.Map;

/**
 * Data for a custom analytics event published via
 * {@link AgentCat#publishCustomEvent(io.modelcontextprotocol.server.McpSyncServer, String, CustomEventData)}.
 *
 * <p>Mirrors the TypeScript {@code CustomEventData} interface from {@code src/types.ts}.
 */
public final class CustomEventData {

    /** Session ID to attribute this event to. If omitted the event is published without a session. */
    public String sessionId;

    /** Logical resource or action name (e.g. {@code "custom-action"}). */
    public String resourceName;

    /** Arbitrary input parameters to record on the event. */
    public Object parameters;

    /** Arbitrary response payload to record on the event. */
    public Object response;

    /** Free-form message / user intent string. */
    public String message;

    /** Elapsed time in milliseconds. */
    public Integer duration;

    /** Whether this event represents an error. */
    public Boolean isError;

    /**
     * Customer-defined string tags.
     * Invalid keys/values are silently dropped by {@link com.agentcat.sdk.modules.events.TagValidator#validate}.
     */
    public Map<String, String> tags;

    /**
     * Customer-defined structured properties.
     * An empty map is treated as omission (no properties are sent).
     */
    public Map<String, Object> properties;
}
