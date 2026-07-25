# ObscuraKit-Kotlin

The **native Android/JVM platform layer** for the Obscura app (`obscura-pix`). It is not a
general-purpose framework, it has exactly one consumer, and it owes API stability to no one.

> ### ⚠️ Mid-reset — much of what is documented below is being deleted
>
> The normative brief is [`obscura-proto/SPEC.md` §0 — The kit boundary](../obscura-proto/SPEC.md),
> with the deletion inventory in [`obscura-proto/RESET.md`](../obscura-proto/RESET.md).
>
> This kit grew an ORM, a CRDT engine, a query DSL, and an audience-routing system — duplicated
> in Swift — to serve five flat models that use none of it. The app's entire ORM usage is four
> calls (`defineModels`, `createEntry`, `upsertEntry`, `allEntries`). That layer is being removed
> and its logic moved into the app, where it will exist once.
>
> **This README still describes the old design.** Trust `SPEC.md` over it.

**Why a native kit exists at all:** libsignal ships only as `libsignal-java` / `libsignal-swift`
— there is no supported shared core, so the Signal protocol must be implemented per platform.
And the push path must decrypt a message with the app closed (on iOS, inside a Notification
Service Extension, which cannot run a React Native runtime). Those two facts, and nothing else,
are what justify native code. Everything else belongs in the app.

**What survives the reset:** Signal sessions/identity/prekeys, device provisioning + linking +
revocation, transport (REST + gateway WebSocket, offline queue), the friend graph, attachment
crypto, the message store, and the push-wake path.

*(`obscura-client-web` is a throwaway proof-of-concept. It is **not** a reference implementation
and must not be treated as a porting target.)*

## Quick Start

```kotlin
val client = ObscuraClient(ObscuraConfig(apiUrl = "https://obscura.barrelmaker.dev"))
client.register("alice", "mypassword123!")
client.connect()

// Define models
client.orm.define(mapOf(
    "directMessage" to ModelConfig(
        fields = mapOf("conversationId" to "string", "content" to "string", "senderUsername" to "string"),
        sync = "gset"
    ),
    "story" to ModelConfig(
        fields = mapOf("content" to "string", "authorUsername" to "string"),
        sync = "gset", ttl = "24h"
    ),
    "settings" to ModelConfig(
        fields = mapOf("theme" to "string", "notificationsEnabled" to "boolean"),
        sync = "lww", private = true
    )
))

// Typed models (compile-safe)
@Serializable
data class Story(val content: String, val authorUsername: String)

val stories = TypedModel.wrap<Story>(client.orm.model("story"))
stories.create(Story(content = "Hello!", authorUsername = "alice"))
val feed by stories.observe().collectAsState(emptyList())
```

See [docs/ORM.md](docs/ORM.md) for the full guide. See [docs/AUTHENTICATION.md](docs/AUTHENTICATION.md) for auth and device linking.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│  YOUR APP (Compose views, typed models)              │
├──────────────────────────────────────────────────────┤
│  ObscuraClient facade                                │
╞══════════════════════════════════════════════════════╡
│  Layer 3: ORM + Friends + Devices                    │
│  GSet/LWWMap CRDTs, auto-sync, TTL, signals          │
╞══════════════════════════════════════════════════════╡
│  Layer 2: Signal Protocol encrypt/decrypt            │
╞══════════════════════════════════════════════════════╡
│  Layer 1: WebSocket + REST (server is a dumb relay)  │
╞══════════════════════════════════════════════════════╡
│  SQLDelight (Signal keys, friends, ORM entries)      │
└──────────────────────────────────────────────────────┘
```

Your app only touches the top. Everything below is invisible.

## What Works

Tested with 400+ tests — 300 unit (no network) + 117 integration (against a containerized
`obscura-server`), counted from source on 2026-07-24; four `@TestFactory` suites expand further at
runtime:

- **ORM auto-sync** — `model.create()` encrypts and delivers to friends automatically
- **Typed models** — `@Serializable` data classes with `TypedModel.wrap<T>()`
- **Query DSL** — `story.where { "author" eq "alice" }.orderBy("likes").limit(10).exec()`
- **Reactive observation** — `model.observe()` returns `Flow<List<OrmEntry>>` for Compose
- **Offline resilience** — create while friend is offline, they get it on reconnect
- **Conflict resolution** — LWWMap: newer timestamp wins. GSet: merge = union.
- **TTL** — entries with `ttl = "24h"` expire automatically
- **Private models** — `private = true` syncs only to your own devices
- **Relationships** — `hasMany`/`belongsTo` with `include()` eager loading
- **Device linking** — `loginAndProvision()` → `PENDING_APPROVAL` → QR/code approval required
- **ECS signals** — `model.typing(convId)` / `model.observeTyping(convId)` for ephemeral indicators
- ~~**Cross-platform** — iOS ↔ Android proven with shared ORM wire format.~~ **Not true as
  written.** The two kits have diverged: Swift still hard-codes application field names, narrows
  a `friends` broadcast when an entry happens to carry a `conversationId`, and has no schema
  migration mechanism at all. The kits agree on the *wire*; they do not agree on *behavior*.
- ~~**Chat via ORM** — `client.send()` falls back to TEXT if the model is not defined.~~ That
  fallback is a **silent-delivery bug**, not a feature: only MODEL_SYNC contributes to push
  counts, so a TEXT message arrives with no notification. Being removed.
- ~~**`authorDeviceId` is currently a lie.** `senderDeviceId` is null over the wire, so signals
  report the sender's *userId* in a field documented as a device id.~~ **Fixed in this kit**
  (Phase 2, PR #40). `Envelope` now carries `sender_device_id`; the inbound Signal session is
  selected by that device UUID and `authorDeviceId` is the address of the session that decrypted,
  so a valid MAC is what proves the attribution. `AuthorDeviceIdTests` asserts it against the
  sender's real device. The rule is normative in `obscura-proto/SPEC.md` §0.10. **Still a lie on
  ObscuraKit-swift `main`** — its fix is on PR #6 there, which currently fails to build on macOS CI.

## What Doesn't Work Yet

- `observe()` on queries with `include()` — observation works, eager loading works, not together yet
- Counter CRDT (only GSet and LWWMap)

## Build & Test

```bash
export JAVA_HOME=/path/to/jdk-21

./gradlew :lib:test                              # 300 unit tests (fast, no network)
./gradlew :lib:integrationTest                   # 117 server-dependent tests
./gradlew :lib:koverHtmlReport                   # coverage report
```

The integration suite targets `https://obscura.barrelmaker.dev` by default but
honors `OBSCURA_TEST_API` — CI points it at a containerized `obscura-server`
(rate limits disabled) so the suite runs on every PR without touching prod.
Each integration test is gated on `assumeTrue(checkServer())`, so it
skips-not-fails when no server is reachable.

## Docs

- [ORM Guide](docs/ORM.md) — models, queries, typed models, observation, sync, signals, interop
- [Authentication](docs/AUTHENTICATION.md) — register, login, device linking, session restore
- [Test Tiers](docs/plans/test_tiers.md) — unit / integration / scenario test plan

## Dependencies

- `org.signal:libsignal-client` — Signal Protocol
- `com.google.protobuf:protobuf-kotlin` — wire format
- `app.cash.sqldelight:sqlite-driver` — persistence
- `com.squareup.okhttp3:okhttp` — HTTP + WebSocket
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` — async
- `org.jetbrains.kotlinx:kotlinx-serialization-json` — typed models
- `org.json:json` — JSON parsing

## Server

- **API:** https://obscura.barrelmaker.dev
- **Spec:** https://obscura.barrelmaker.dev/openapi.yaml
