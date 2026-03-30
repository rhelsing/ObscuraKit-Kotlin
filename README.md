# ObscuraKit-Kotlin

A Rails-like framework for Signal-powered apps. E2E encrypted data layer for Android/JVM. Define models, call `create()` — encryption, sync, and conflict resolution happen underneath. Pure JVM, tested against `obscura.barrelmaker.dev`.

There is a matching iOS implementation at `obscura-client-ios` and a JS web client at `obscura-client-web`. All three share the same server, protobuf wire format, and ORM model definitions. Messages sent from any platform arrive on any other.

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

Tested with 210+ tests (unit + integration against live server):

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
- **Cross-platform** — same wire format as iOS. DirectMessage, Story, Profile, Settings models interoperate.
- **Chat via ORM** — `client.send()` uses DirectMessage model (MODEL_SYNC type 30). Falls back to TEXT (type 0) if model not defined.

## What Doesn't Work Yet

- Cross-platform interop not verified end-to-end against live server (wire format matches by design)
- `observe()` on queries with `include()` — observation works, eager loading works, not together yet
- Counter CRDT (only GSet and LWWMap)

## Demo App

The `app/` module is a working Android app with 5 tabs (Friends, Chat, Stories, Profile, Settings), all using typed ORM models. See `app/src/main/kotlin/com/obscura/app/MainActivity.kt`.

## Build & Test

```bash
export JAVA_HOME=/path/to/jdk-21

./gradlew :lib:test                              # all tests
./gradlew :lib:test --tests "scenarios.CRDTTests" # specific suite
./gradlew :app:assembleDebug                      # build Android app
```

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
