# ObscuraKit-Kotlin

@README.md for full architecture, API reference, file structure, multi-app and multi-platform strategy.

## Quick Context

- **What:** E2E encrypted data layer library (not an app) — any Android/JVM app links against this
- **Server:** `obscura.barrelmaker.dev` (OpenAPI spec at `/openapi.yaml`)
- **Reference:** JS source at `/Users/ryanhelsing/Projects/obscura-client-web/`
- **Build:** `JAVA_HOME=/path/to/jdk-21 ./gradlew test`
- **Tests:** 40 E2E scenarios against live server, all using `ObscuraClient` public API

## Three-Level Architecture

1. **Level 1 (Server Protocol):** `network/APIClient.kt`, `network/GatewayConnection.kt` — REST + WebSocket transport. Server is a dumb relay.
2. **Level 2 (Client Protocol):** `stores/MessengerDomain.kt`, `crypto/SignalStore.kt` — Signal encrypt/decrypt, 20+ client-to-client message types. Server never sees contents.
3. **Level 3 (ORM):** `orm/` — CRDT-based data models (GSet, LWWMap) riding on MODEL_SYNC messages. Never touches Signal or WebSocket.

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
