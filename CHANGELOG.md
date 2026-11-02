# Changelog

This file lists the notable changes of each release of `com.agentcat:agentcat-java-sdk`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the SDK follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-11-02

### Added

- `AgentCat.track(...)`: records each tool call of an MCP Java SDK server (`McpSyncServer`,
  `McpAsyncServer`, `McpStatelessSyncServer` and `McpStatelessAsyncServer`) as an AgentCat event.
  The SDK works with MCP Java SDK 0.18.x, 1.x and 2.x.
- Changes to the tool list: tools get the `context`, `session_id` and (with
  `setEnableAgentTracking(true)`) `agent_id` parameters, and the list gets the `get_more_tools`
  tool. The SDK strips these parameters before your tool handler runs.
- `AgentCatOptions`, with the hooks `identify`, `redactEvent`, `redactSensitiveInformation`,
  `eventTags` and `eventProperties`.
- `AgentCat.publishCustomEvent(...)`, which sends an `agentcat:custom` event.
- Auto-configuration for Spring Boot, Micronaut and Quarkus.
- Event delivery with the JDK HTTP client. The SDK adds no HTTP or JSON library to your
  classpath: its only required dependency is `com.agentcat:api`, which has no dependencies.

[Unreleased]: https://github.com/agentcathq/agentcat-java-sdk/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/agentcathq/agentcat-java-sdk/releases/tag/v0.1.0
