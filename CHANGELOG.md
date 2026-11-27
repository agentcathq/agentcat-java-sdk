# Changelog

This file lists the notable changes of each release of `com.agentcat:agentcat-java-sdk`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the SDK follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.3] - 2026-11-27

### Security

- Update the Spring WebMVC, WebFlux and test dependencies to 7.0.9 to address security
  vulnerabilities without a Spring 6.2 patch. Align the optional Spring Boot
  auto-configuration dependency with Spring Framework 7 by updating it to 4.0.8.

## [1.1.2] - 2026-11-26

### Fixed

- Tool call events now carry the HTTP request of the call at `parameters.extra.http.req`: the
  method, the URL and all headers. Before, stateless servers sent an empty `extra` and stateful
  servers sent no headers, so AgentCat could not detect the client (for example Codex or
  Claude Code) from `user-agent`. `track()` wraps the transport's `contextExtractor`; your own
  extractor still runs and its context is unchanged. This covers the MCP SDK's `HttpServlet*`
  transports and the Spring WebMVC and WebFlux transports. The SDK masks the values of
  credential headers such as `Authorization` and `Cookie`: see Security below.
- SDK version reporting now uses a constant generated from the SDK's Maven project version,
  rather than a hardcoded value or the consuming application's version.

### Security

- The SDK now masks sensitive values in every event before the event leaves your server. It
  tests each key in `parameters` and `response`, at any depth. When the key names a secret,
  the SDK keeps the key and replaces the value with `[redacted]`. A key names a secret when
  it is a known credential header (`authorization`, `cookie`, `x-api-key` and others) or has
  a word such as `token`, `secret`, `password`, `signature` or `api-key`, in any spelling
  (`X-Api-Key`, `HTTP_X_API_KEY`, `xApiKey`). This covers the request headers that this
  release adds at `parameters.extra.http.req.headers`. Tool arguments and results under such
  key names lose their values too, for example `pageToken` and `auth_type`. `null`, `true`
  and `false` stay. No option turns the mask off (#14).

## [1.1.1] - 2026-11-25

### Added

- `AgentCatOptions.setExcludedTools(Set<String>)` leaves selected tools' schemas and responses
  unchanged and skips their instrumentation and analytics, including tools registered later.
  This supports OpenAI-specific use cases such as ChatGPT account linking, where a profile
  tool like `get_my_account` is called with `{}` and must return its declared response schema.

## [1.1.0] - 2026-11-17

### Added

- The SDK sends a server info record about once an hour: the server's name, version, title,
  instructions and its tool, resource, resource template and prompt lists. The text redaction
  function runs on it. The `com.agentcat:api` client is now 0.0.6.

## [1.0.0] - 2026-11-02

### Changed

- The SDK is now generally available. Version 1.0.0 is the first stable release after months of
  beta use, and from this release the public API follows Semantic Versioning: a breaking change
  needs a new major version.

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

[Unreleased]: https://github.com/agentcathq/agentcat-java-sdk/compare/v1.1.3...HEAD
[1.1.3]: https://github.com/agentcathq/agentcat-java-sdk/compare/v1.1.2...v1.1.3
[1.1.2]: https://github.com/agentcathq/agentcat-java-sdk/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/agentcathq/agentcat-java-sdk/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/agentcathq/agentcat-java-sdk/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/agentcathq/agentcat-java-sdk/compare/v0.1.0...v1.0.0
[0.1.0]: https://github.com/agentcathq/agentcat-java-sdk/releases/tag/v0.1.0
