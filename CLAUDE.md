# ObscuraKit-Kotlin

## Read this before changing anything

Read [`obscura-proto/SPEC.md`](../obscura-proto/SPEC.md) and
[`obscura-proto/KIT_API.md`](../obscura-proto/KIT_API.md) first.

This kit exposes an explicit-audience send path, a durable
`peek`/`consume`/`discard`/`depth` inbox, and opaque `EntryStore` storage.
Application merge, audience resolution, schemas, queries, expiry, and
notification policy live in `obscura-pix`.

The receive loop is persist-then-ack (`SPEC` §0.9): never acknowledge a decrypt
failure, a deferred sender, or data that was not durably handled. Signal
sessions are addressed by device UUID (`SPEC` §0.10);
`FriendDeviceInfo.registrationId` is diagnostic metadata, not an address.

The rule that governs this repo:

> **If the kit reads it, it is a field in `client.proto`.
> If it is not in `client.proto`, the kit MUST NOT read it.**

**Do not add an ORM, CRDT layer, query builder, audience/routing engine, or
schema parser.** If a task seems to require one, re-check the boundary in
`SPEC.md` §0 and the application implementation in `obscura-pix`.

## Quick Context

- **What:** the **native platform layer** for the Obscura app. Not a general-purpose framework;
  it has exactly one consumer (`obscura-pix`) and no API-stability obligation to anyone else.
- **Why it exists natively at all:** libsignal ships only as `libsignal-java` / `libsignal-swift`
  (no shared core), and background push processing cannot depend on a React
  Native runtime. Everything *else* belongs in the app.
- **Server:** `obscura.barrelmaker.dev` (OpenAPI spec at `/openapi.yaml`)
- **Contract:** `obscura-proto` (submodule at `proto/`) — `SPEC.md` is normative.
- **Sibling kit:** `ObscuraKit-swift`. It must agree with this one on the **wire**
  (`conformance/wire.json`) and nothing more.

> **Not a reference:** `obscura-client-web` is a throwaway proof-of-concept, **not** a porting
> target and **not** a normative implementation.
- **Build:** `JAVA_HOME=/path/to/jdk-21 ./gradlew :lib:test`
- **Tests:** `src/test` runs without a network; `src/integrationTest` drives the
  public facade against a configured server. JUnit 5 ignores non-void `@Test`
  methods, so a body ending in `assertThrows(...)` needs a trailing `Unit`.

## Three-Level Architecture

1. **Level 1 (Server Protocol):** `network/APIClient.kt`, `network/GatewayConnection.kt` — REST + WebSocket transport. Server is a dumb relay.
2. **Level 2 (Client Protocol):** `stores/MessengerDomain.kt`, `crypto/SignalStore.kt` — Signal encrypt/decrypt, the 18 client-to-client payload arms `client.proto` declares. Server never sees contents.
3. **Level 3 (app data):** `stores/InboxDomain.kt` + `stores/EntryStore.kt` — a durable inbox of
   decrypted rows and a blind key/value store of application entries, both keyed on an **opaque**
   model name. Payload bytes are never parsed. `wire/WireCodec.kt` owns the wire↔app-facing
   mappings pinned by `conformance/wire.json`.

`ObscuraClient.kt` is the facade that wires all three levels together and exposes StateFlows for Compose views.

## Key Patterns

- **Confined coroutines:** Each domain class uses `Dispatchers.Default.limitedParallelism(1)` — Kotlin equivalent of Swift Actors
- **Auto-session building:** `MessengerDomain.queueMessage()` fetches prekey bundles and builds Signal sessions on demand
- **StateFlow for UI:** `connectionState`, `authState`, `friendList`,
  `pendingRequests`, and `conversations`. The current Android bridge observes
  `connectionState`, `authState`, `friendList`, and `incomingMessages`; it reads
  pending requests on demand. `typedEvents` is an optional aggregate stream.
- **Inbound wake stream:** `incomingMessages` has exactly one app consumer; push draining observes
  receive activity without consuming it.

## Server API Endpoints Used

```
POST /v1/users              register
POST /v1/devices            provision device with Signal keys
POST /v1/sessions           login (with optional deviceId)
POST /v1/sessions/refresh   token refresh
DELETE /v1/sessions         logout
GET  /v1/users/{id}         fetch PreKey bundles
GET  /v1/devices            list devices
DELETE /v1/devices/{id}     delete device
POST /v1/devices/keys       upload/replace keys (takeover)
POST /v1/messages           send encrypted batch (protobuf)
POST /v1/gateway/ticket     WebSocket auth ticket
WS   /v1/gateway            WebSocket (EnvelopeBatch/AckMessage)
POST /v1/attachments        upload blob
GET  /v1/attachments/{id}   download blob
POST /v1/backup             upload encrypted backup
GET  /v1/backup             download backup
HEAD /v1/backup             check backup exists
```

## Dependencies

libsignal-client (Signal Protocol JVM), protobuf-kotlin, SQLDelight (JVM SQLite), OkHttp, kotlinx-coroutines, org.json

## Critical Knowledge (read before making changes)

Hard-won lessons in `docs/knowledge/`. Read these before touching the codebase:

- [runTest vs runBlocking](docs/knowledge/critical_runtest_vs_runblocking.md) — WebSocket tests MUST use runBlocking (virtual time breaks OkHttp)
- [Server API quirks](docs/knowledge/critical_server_api_quirks.md) — password min 12, listDevices wraps in object, rate limiting, no /health
- [Signal session building](docs/knowledge/critical_signal_session_building.md) — encrypt() fails without session, ensureSession() pattern is critical
- [Multi-device queue draining](docs/knowledge/critical_multidevice_queue_draining.md) — befriend() fans out to ALL devices, tests must drain every queue
- [Protobuf naming](docs/knowledge/critical_protobuf_naming.md) — p2p_public_key → p2PPublicKey, data → data_, ByteString ambiguity
- [Facade completeness](docs/knowledge/critical_facade_completeness.md) — supported user flows use the facade; adversarial wire tests are explicit exceptions
