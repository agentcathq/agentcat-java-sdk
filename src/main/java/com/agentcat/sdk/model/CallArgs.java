package com.agentcat.sdk.model;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

import java.util.Map;

/**
 * Immutable snapshot of a tool call's arguments as seen by the AgentCat SDK.
 *
 * @param rawArgs    tool arguments as received, including injected handle parameters
 * @param stripped   tool call request with AgentCat handle parameters removed;
 *                   forwarded to the user's tool handler
 * @param userIntent agent's stated intent extracted from raw args before stripping;
 *                   {@code null} when not provided
 */
public record CallArgs(Map<String, Object> rawArgs,
                        CallToolRequest stripped,
                        String userIntent) {}
