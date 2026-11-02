package com.agentcat.sdk.model;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Immutable snapshot of the core tool-call data submitted to the event pipeline.
 *
 * @param projectId  AgentCat project ID
 * @param sessionId  resolved session identifier (KSUID, or empty in hook mode)
 * @param toolName   name of the called tool
 * @param params     arguments after handle parameters have been stripped
 * @param response   serialised tool result; {@code null} if the call threw
 * @param durationMs wall-clock duration in milliseconds
 * @param isError      {@code true} if the tool returned or threw an error
 * @param errorMessage error message string when the call threw; {@code null} otherwise
 * @param userIntent   agent's stated intent; non-null only for {@code get_more_tools}
 * @param sdkTags      SDK-internal handle tags
 * @param callTime     wall-clock timestamp captured at the moment the tool call was received
 */
public record EventData(String projectId,
                         String sessionId,
                         String toolName,
                         Map<String, Object> params,
                         Map<String, Object> response,
                         long durationMs,
                         boolean isError,
                         String errorMessage,
                         String userIntent,
                         Map<String, String> sdkTags,
                         OffsetDateTime callTime) {}
