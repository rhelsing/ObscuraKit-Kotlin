# ObscuraKit-Kotlin

## ⚠️ Read this before changing anything

**The reset has landed. Do not re-add what it removed.**

Read [`obscura-proto/SPEC.md` §0 — The kit boundary](../obscura-proto/SPEC.md),
[`obscura-proto/PLAN.md`](../obscura-proto/PLAN.md) (order of operations + current phase status) and
[`obscura-proto/RESET.md`](../obscura-proto/RESET.md) **first**. They are the brief.

**Where this kit is (2026-07-31): Phase 3 — the reset — has LANDED here (PR #56).** The `orm/`
package is gone: Model, ModelConfig, ModelStore, MonotonicClock, OrmEntry, Query, QueryBuilder,
Schema, SyncManager, TTLManager, TypedModel, crdt/GSet, crdt/LWWMap, plus `SchemaDomain`. What
replaced them: `sendEntry(...)` for the outbox, `inbox` (peek/consume/discard/inboxDepth) for the
receive path, and `EntryStore` for storage. Merge and audience resolution live in obscura-pix now,
once. `WireCodec.kt` and `SignalManager.kt` moved to `wire/` rather than dying — they were
keep-forever code that happened to live in `orm/`.

**Phase 2 acceptance is SIGNED OFF on both kits** — see `obscura-proto/PLAN.md` for the evidence and
the four gaps recorded at sign-off. Phases 1 and 2 landed here before it. The receive loop is
persist-then-ack (`SPEC` §0.9): never ack a decrypt failure, a rate-limited skip, or anything not
yet durably written. Signal sessions are addressed by **device UUID** (`SPEC` §0.10) — the inbound
session comes from `Envelope.sender_device_id`, prekey bundles are selected by device UUID with no
fallback, and `registrationId` addresses **nothing**. `FriendDeviceInfo.registrationId` still exists
as a vestigial diagnostic slot; do not build on it, and do not "restore" registration-id
addressing — that is finding F1, and it silently breaks multi-device delivery.

An audit found that this kit grew a schema-driven ORM, CRDT engine, query DSL, and
audience-routing system — implemented *twice*, here and in Swift — to serve five flat models
in one app, none of it reachable from that app. It was deleted, not improved.

The rule that governs this repo:

> **If the kit reads it, it is a field in `client.proto`.
> If it is not in `client.proto`, the kit MUST NOT read it.**

**Do not re-add an ORM, a CRDT layer, a query builder, an audience/routing engine, or a schema
parser.** If a task seems to require one, that is the signal to stop and re-read §0. An agent
working only inside this repo cannot see why they are unnecessary, because the evidence lives in
`obscura-pix`. `KIT_API.md` §9 names the shape the regression takes: `EntryStore` grows a filter,
then an index abstraction, then observation, and the deleted engine is back under a new name.

## Quick Context

- **What:** the **native platform layer** for the Obscura app. Not a general-purpose framework;
  it has exactly one consumer (`obscura-pix`) and no API-stability obligation to anyone else.
- **Why it exists natively at all:** libsignal ships only as `libsignal-java` / `libsignal-swift`
  (no shared core), and the push path must decrypt with the app closed (iOS NSE — no RN runtime).
  Everything *else* belongs in the app.
- **Server:** `obscura.barrelmaker.dev` (OpenAPI spec at `/openapi.yaml`)
- **Contract:** `obscura-proto` (submodule at `proto/`) — `SPEC.md` is normative.
- **Sibling kit:** `ObscuraKit-swift`. It must agree with this one on the **wire**
  (`conformance/wire.json`) and nothing more.

> **Not a reference:** `obscura-client-web` is a throwaway proof-of-concept, **not** a porting
> target and **not** a normative implementation. Earlier versions of this file pointed agents at
> it. That was a significant source of the mess.
- **Build:** `JAVA_HOME=/path/to/jdk-21 ./gradlew :lib:test`
- **Tests:** two source sets — `src/test` (216 unit tests, no network) and `src/integrationTest` (96 tests against a containerized/live `obscura-server`, all driving the `ObscuraClient` public API). Both counts dropped with the ORM deletion. Those are the counts **JUnit reports**, not `@Test` greps: `grep -o "@Test"` also matches `@TestFactory` and `@TestMethodOrder` and overcounts. Verify a suspicious count against `lib/build/test-results/*/TEST-*.xml` — that is how a test that had never executed was found. **A non-void `@Test` is silently ignored by JUnit 5** — this has now bitten twice, most recently on an `assertThrows(...)` whose returned `Throwable` made the method non-`Unit`; add a trailing `Unit`. The integration suite needs a *correctly configured* server: seed the MinIO `test-bucket` and raise the auth rate limit, or you get ~63 environmental failures (HTTP 429/500) that are not code failures — see `PLAN.md` 0.3.

## Three-Level Architecture

1. **Level 1 (Server Protocol):** `network/APIClient.kt`, `network/GatewayConnection.kt` — REST + WebSocket transport. Server is a dumb relay.
2. **Level 2 (Client Protocol):** `stores/MessengerDomain.kt`, `crypto/SignalStore.kt` — Signal encrypt/decrypt, 20+ client-to-client message types. Server never sees contents.
3. **Level 3 (app data):** `stores/InboxDomain.kt` + `stores/EntryStore.kt` — a durable inbox of
   decrypted rows and a blind key/value store of application entries, both keyed on an **opaque**
   model name. Payload bytes are never parsed. `wire/WireCodec.kt` owns the wire↔app-facing
   mappings pinned by `conformance/wire.json`. This level used to be `orm/`, a CRDT engine and query
   DSL; that is deleted (`RESET.md` §10 step 4).

`ObscuraClient.kt` is the facade that wires all three levels together and exposes StateFlows for Compose views.

## Key Patterns

- **Confined coroutines:** Each domain class uses `Dispatchers.Default.limitedParallelism(1)` — Kotlin equivalent of Swift Actors
- **Auto-session building:** `MessengerDomain.queueMessage()` fetches prekey bundles and builds Signal sessions on demand
- **StateFlow for UI:** `connectionState`, `authState`, `friendList`, `pendingRequests`, `conversations`, `events`
- **Channel for tests:** `incomingMessages` channel + `waitForMessage()` for synchronous test flow

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
- [Facade completeness](docs/knowledge/critical_facade_completeness.md) — raw proto in test = missing facade method
