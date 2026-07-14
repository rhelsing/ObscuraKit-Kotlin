# Changelog

All notable changes to ObscuraKit-Kotlin are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- `ObscuraClient` implements `AutoCloseable`; `close()` tears down all coroutines,
  the WebSocket, and the database driver (if owned). Idempotent — safe to call multiple times.
- `GatewayConnection` implements `AutoCloseable`; `close()` shuts down the OkHttp client
  and evicts the connection pool.
- `SignalManager` implements `AutoCloseable`; `close()` cancels the internal signal timer scope.
- `SessionSnapshot` data class with `version: Int` field for typed, versioned session
  persistence. `SessionStorage` gains default `saveSnapshot()`/`loadSnapshot()` helpers.
- `ObscuraConfig.allowUnencryptedDatabase: Boolean` explicit opt-in to suppress the
  unencrypted-database warning emitted to stderr when a file-backed database is used.
- `explicitApiWarning()` mode enforced across the library module.
- Binary compatibility validator (`org.jetbrains.kotlinx.binary-compatibility-validator 0.16.0`)
  added to surface accidental API breaks.
- Sources and Javadoc JARs are now included in the Maven publication.
- Full POM metadata: license (MIT), SCM, description.
- Maven coordinates (`group`, `version`) read from `gradle.properties` — no version
  hard-coding in build scripts.
- Background TTL cleanup job in `ObscuraClient` runs every 60 s to purge expired ORM entries.
- `Signal` context is now passed as an opaque `contextKey: String` (app-owned) rather than
  embedding hardcoded `conversationId`/`senderUsername` fields.

### Changed
- `ObscuraEvent.TypingChanged.conversationId` renamed to `contextId` (binary-incompatible).
- `SignalManager` API: `emit/receive/clear/observeTyping/observeRead` take `contextKey: String`
  instead of `data: Map<String, Any?>`. `sendSignal` callback signature changed accordingly.
- `Model.typing`, `stopTyping`, `read`, `observeTyping`, `observeRead` parameter renamed
  `conversationId` → `contextKey`.
- `ObscuraClient.send()` no longer embeds `conversationId` or `senderUsername` in ORM entries.
  Only `content` is set. Apps requiring these fields should call `orm.model(name).create(fields)`.
- `coroutines-core` and `protobuf-kotlin` promoted from `implementation` to `api` dependencies
  (both types appear in the public API surface via StateFlow/SharedFlow/Channel/ByteString).
- `APIClient` retry waits use `delay()` (coroutine-friendly) instead of `Thread.sleep()`.
  All HTTP execute calls are wrapped in `withContext(Dispatchers.IO)`.
- `GatewayConnection` internal fields are now thread-safe (`AtomicReference`, `AtomicInteger`,
  `@Volatile`). Failed `trySend` calls are logged at WARN level.
- CI integration tests clone the server repo to a local path instead of `/tmp`.
  Server image tag is now documented and pinned to the `main` branch tag.

### Deprecated
- `ObscuraConfig.gatewayUrl`: the WebSocket URL is derived from `apiUrl` automatically.
  Passing `gatewayUrl` has no effect. Will be removed in a future release.

### Removed
- `senderUsername` field from `ActiveSignal` data class.
- Hard-coded `conversationId` and `senderUsername` injection in `ObscuraClient.send()`.

---

## [0.1.0] — Initial release

Initial library release. Signal Protocol E2E encryption, ORM CRDT layer,
WebSocket gateway, multi-device sync, friend management.
