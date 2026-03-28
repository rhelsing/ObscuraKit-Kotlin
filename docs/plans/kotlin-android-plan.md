# ObscuraKit: Android/Kotlin Data Layer Plan (Confined Coroutines Architecture)

## Context

Port the Obscura web client's data layer to a Kotlin library (`ObscuraKit`) that is fully smoke-testable without any views. The library's public API is what Jetpack Compose views will call — and what tests call. Zero controller/glue code. Views are just a thin projection of tested state.

Architecture: **Confined Coroutines** — each domain class confines mutable state to a single-threaded dispatcher (`Dispatchers.Default.limitedParallelism(1)`). This is the modern Kotlin equivalent of Swift Actors — compiler doesn't enforce it, but the pattern gives the same serial execution guarantee. Signal crypto ops (order-sensitive per session) are protected by confinement. Clean `suspend fun` throughout.

---

## Repo & Toolchain

**Repo:** `/Users/ryanhelsing/Projects/obscura-client-kotlin`
**Web client (reference):** `/Users/ryanhelsing/Projects/obscura-client-web` — all JS source files, proto definitions, and smoke tests live here. This is the source of truth for what to port.
**No Android Studio required** — everything runs via `./gradlew build` and `./gradlew test` from the command line (pure JVM).

### Prerequisites (install before starting)

```bash
# 1. JDK 21 (LTS) — you have javac but no runtime
brew install openjdk@21

# 2. Gradle
brew install gradle

# 3. Kotlin (Gradle pulls it too, but useful for REPL/scripting)
brew install kotlin

# 4. Protobuf compiler (may already be installed from swift-protobuf)
brew install protobuf

# 5. Verify
java -version          # should show 21.x
gradle --version
kotlin -version
protoc --version
```

**Gradle dependencies (in build.gradle.kts):**
- `com.google.protobuf:protobuf-kotlin` — protobuf runtime + Kotlin DSL
- `app.cash.sqldelight:sqlite-driver` — pure JVM SQLite (no Android dependency)
- `org.signal:libsignal-client` — Signal protocol (JVM variant, not Android)
- `com.squareup.okhttp3:okhttp` — HTTP + WebSocket client
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` — coroutines
- `org.jetbrains.kotlinx:kotlinx-coroutines-test` — test dispatchers

---

## Module Structure

```
obscura-client-kotlin/   ← /Users/ryanhelsing/Projects/obscura-client-android
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml     ← version catalog
├── lib/
│   ├── build.gradle.kts       ← ObscuraKit library module
│   └── src/
│       ├── main/kotlin/
│       │   └── com/obscura/kit/
│       │       ├── proto/
│       │       │   ├── server/     ← generated from obscura.proto
│       │       │   └── client/     ← generated from client.proto
│       │       ├── orm/
│       │       │   ├── crdt/       ← GSet.kt, LWWMap.kt
│       │       │   ├── Model.kt
│       │       │   ├── ModelStore.kt  ← SQLDelight persistence
│       │       │   ├── SyncManager.kt
│       │       │   ├── TTLManager.kt
│       │       │   └── QueryBuilder.kt
│       │       ├── crypto/         ← libsignal wrapper, SignalStore
│       │       ├── network/        ← APIClient (OkHttp), GatewayConnection (OkHttp WS)
│       │       ├── stores/         ← FriendStore, MessageStore, DeviceStore
│       │       └── ObscuraClient.kt  ← public API facade
│       ├── test/kotlin/
│       │   ├── scratchpad/         ← throwaway validation tests (delete when done)
│       │   └── scenarios/          ← permanent scenario tests (the deliverable)
│       └── sqldelight/
│           └── com/obscura/kit/
│               ├── Friend.sq
│               ├── Message.sq
│               ├── Device.sq
│               ├── SignalKey.sq
│               └── ModelEntry.sq
└── fixtures/                       ← .proto source files copied from web repo
```

---

## Public API (what views and tests both call)

```kotlin
class ObscuraClient(config: ObscuraConfig) {
    val friends: FriendDomain
    val messages: MessageDomain
    val devices: DeviceDomain
    val messenger: MessengerDomain
    val orm: SchemaDomain

    val api: APIClient
    val gateway: GatewayConnection

    suspend fun register(username: String, password: String)
    suspend fun login(username: String, password: String)
    suspend fun logout()
}

// Each domain class confines state to a single-threaded dispatcher.
// This is the Kotlin equivalent of a Swift Actor.
class FriendDomain internal constructor(private val db: ObscuraDatabase) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun add(userId: String, username: String, status: FriendStatus, devices: List<DeviceInfo>)
    suspend fun getAccepted(): List<Friend>
    suspend fun getFanOutTargets(userId: String): List<DeviceTarget>
    suspend fun getPending(): List<Friend>
    suspend fun updateDevices(userId: String, devices: List<DeviceInfo>)
    suspend fun remove(userId: String)
    suspend fun exportAll(): FriendExport
    suspend fun importAll(data: FriendExport)
}

class MessengerDomain internal constructor(
    private val signalStore: SignalStore,
    private val api: APIClient
) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun mapDevice(deviceId: String, userId: String, registrationId: Int)
    suspend fun queueMessage(targetDeviceId: String, message: ClientMessage, userId: String)
    suspend fun flushMessages()
    suspend fun fetchPreKeyBundles(userId: String): List<PreKeyBundle>
    suspend fun decrypt(envelope: Envelope): DecryptedMessage
}

class MessageDomain internal constructor(private val db: ObscuraDatabase) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun add(conversationId: String, message: Message)
    suspend fun getMessages(conversationId: String, limit: Int = 50, offset: Int = 0): List<Message>
    suspend fun migrateMessages(from: String, to: String)
    suspend fun deleteByAuthorDevice(deviceId: String)
}

class DeviceDomain internal constructor(private val db: ObscuraDatabase) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun storeIdentity(identity: DeviceIdentity)
    suspend fun getIdentity(): DeviceIdentity?
    suspend fun addOwnDevice(device: OwnDevice)
    suspend fun getOwnDevices(): List<OwnDevice>
    suspend fun getSelfSyncTargets(): List<DeviceTarget>
}

class SchemaDomain internal constructor(private val db: ObscuraDatabase) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun define(definitions: Map<String, ModelConfig>)
    suspend fun model(name: String): Model?
    suspend fun handleSync(modelSync: ModelSync, from: String)
}
```

---

## Build Order

### Layer 1: Server Proto

**Goal:** Generated Kotlin types for server communication. Prove they round-trip.

**Steps:**
1. `cd /Users/ryanhelsing/Projects/obscura-client-kotlin`
2. `gradle init --type kotlin-library --dsl kotlin`
3. `git init && git add -A && git commit -m "init"`
4. Add protobuf plugin + runtime to build.gradle.kts
5. Copy `/Users/ryanhelsing/Projects/obscura-client-web/public/proto/obscura/v1/obscura.proto` → `fixtures/`
6. Configure protobuf gradle plugin to generate from fixtures/
7. `./gradlew build`

**Scratchpad tests (`scratchpad/ServerProtoTests.kt`):**
- Serialize `WebSocketFrame` → bytes → deserialize, assert fields match
- Serialize `Envelope` with dummy ciphertext, verify byte layout
- Serialize `SendMessageRequest` with multiple envelopes (batch)
- Serialize/deserialize `AckMessage`
- If server reachable: POST real registration, verify response parses

**Also validate:**
- `libsignal-client` (JVM) compiles: generate keypair, sign, verify
- SQLDelight JVM driver compiles, can create in-memory database

**JS reference (in obscura-client-web):** `public/proto/obscura/v1/obscura.proto`

---

### Layer 2: Client Proto + Signal Store

**Goal:** Client-to-client message types + Signal protocol store backed by SQLDelight.

**Steps:**
1. Add `client.proto` to protobuf generation
2. Implement `SignalStore` (SQLDelight-backed, implements libsignal's 4 store interfaces: `IdentityKeyStore`, `PreKeyStore`, `SignedPreKeyStore`, `SessionStore`)
3. Implement `APIClient` (OkHttp, same endpoints as `api/client.js`)
4. Implement `GatewayConnection` (OkHttp WebSocket)

**Scratchpad tests (`scratchpad/ClientProtoTests.kt`):**
- `ClientMessage` with type TEXT → serialize → wrap in `EncryptedMessage` → round-trip
- `ModelSync` with CREATE op, verify fields
- `DeviceInfo`, `FriendRequest`, `FriendResponse` round-trips

**Scratchpad tests (`scratchpad/SignalStoreTests.kt`):**
- Generate identity keypair, store in SQLDelight, retrieve, assert match
- Store/load prekeys and signed prekeys
- Store/load sessions at `(userId, registrationId)` addresses
- Full local encrypt/decrypt: Alice → Bob using in-memory stores (no server)

**Scratchpad tests (`scratchpad/APIClientTests.kt`):**
- Register user, assert token parses, userId extractable from JWT
- Login with device, assert fresh token
- Fetch prekey bundles, assert bundle has all required fields

**JS reference (in obscura-client-web):** `public/proto/v2/client.proto`, `src/lib/IndexedDBStore.js`, `src/api/client.js`, `src/api/gateway.js`

---

### Layer 3: ORM

**Goal:** CRDT engine, model persistence, sync targeting, TTL. Port of `src/v2/orm/`.

**Steps:**
1. `GSet.kt` — grow-only set (add, merge, getAll, filter)
2. `LWWMap.kt` — last-writer-wins map (set, merge, delete via tombstone, timestamp conflict resolution)
3. `ModelStore.kt` — SQLDelight tables for `(modelName, id)` keyed entries + associations + TTL
4. `Model.kt` — base: create, find, where, upsert, delete, handleSync, sign
5. `SyncManager.kt` — broadcast targeting (self-sync, private, belongs_to, all friends)
6. `TTLManager.kt` — schedule, cleanup, isExpired
7. `QueryBuilder.kt` — where(conditions).exec()
8. `Schema.kt` — define models from config, wire together

**Scratchpad tests (`scratchpad/CRDTTests.kt`):**
- GSet: add 3 items, merge duplicate, assert count = 3
- GSet: merge two disjoint sets, assert union
- LWWMap: set value, set again with newer timestamp, assert latest wins
- LWWMap: concurrent conflict — older timestamp loses
- LWWMap: delete (tombstone), assert excluded from getAll
- LWWMap: merge remote entries, assert only newer entries update local

**Scratchpad tests (`scratchpad/ModelTests.kt`):**
- Define "story" model (g-set, fields: {content: string, mediaRef: string?})
- `model.create(mapOf("content" to "hello"))` → verify ID generated, timestamp set, persisted
- `model.find(id)` → verify retrieval
- `model.all()` → verify listing
- `model.where(mapOf("authorDeviceId" to "xyz")).exec()` → verify filtering
- `model.handleSync(remoteEntry)` → verify merge into local CRDT

**Scratchpad tests (`scratchpad/SyncManagerTests.kt`):**
- Private model: broadcast targets = only own devices
- Public model: broadcast targets = own devices + all accepted friends
- belongs_to model: broadcast targets = own devices + group members
- TTL: schedule "24h", verify isExpired = false now, true after advancing clock

**JS reference (in obscura-client-web):** `src/v2/orm/crdt/GSet.js`, `src/v2/orm/crdt/LWWMap.js`, `src/v2/orm/Model.js`, `src/v2/orm/storage/ModelStore.js`, `src/v2/orm/sync/SyncManager.js`, `src/v2/orm/sync/TTLManager.js`, `src/v2/orm/QueryBuilder.js`, `src/v2/orm/index.js`

---

### Layer 4: Domains + ObscuraClient Facade

**Goal:** Wire layers 1-3 into the public API.

**Steps:**
1. `FriendDomain` — confined dispatcher + SQLDelight, manages friend state + device lists
2. `MessageDomain` — confined dispatcher + SQLDelight, messages by conversationId
3. `DeviceDomain` — confined dispatcher + SQLDelight, device identity + own device list
4. `MessengerDomain` — confined dispatcher, encrypt/decrypt/queue/flush, device mapping, prekey bundle fetching. Single-threaded dispatcher protects Signal ratchet state.
5. `ObscuraClient` — facade that owns all domains, exposes the public API
6. `ObscuraTestClient` — thin wrapper for tests (register + connect in one call, waitForMessage with timeout)

**No separate scratchpad tests** — the scenario tests ARE the tests for this layer.

**JS reference (in obscura-client-web):** `src/v2/store/friendStore.js`, `src/v2/store/messageStore.js`, `src/v2/store/deviceStore.js`, `src/lib/messenger.js`, `src/lib/ObscuraClient.js`, `test/helpers/testClient.js`

---

### Layer 5: Scenario Tests (the deliverable)

Live in `scenarios/`. Use `ObscuraTestClient` which calls `ObscuraClient` — same API views use. JUnit 5 + kotlinx-coroutines-test.

**Scenario 1-4: `CoreFlowTests.kt`**
```
1. Register → keys generated, token valid, userId parseable
2. Logout → login → identity restored, WebSocket connects
3. Friend request flow → pending → accepted → both see each other, safety codes match
4. Send message → receiver gets it → queued delivery after offline → persistence
```

**Scenario 5: `MultiDeviceLinkingTests.kt`**
```
5.1 New device login → link-pending state
5.2 Link code generation
5.3 Existing device approves → new device receives SYNC_BLOB (friends + messages)
5.4 Fan-out: message from Alice reaches both Bob devices
5.5 Self-sync: message from Bob2 triggers SENT_SYNC on Bob1
5.6 Link code replay rejection
5.7 Self-friend rejection
```

**Scenario 6: `AttachmentTests.kt`**
```
6.1 Upload → sender has attachment immediately
6.2 Fan-out to multiple devices via CONTENT_REFERENCE
6.3 Download + integrity check (JPEG header bytes)
6.4 Cache hit on second download
6.5 Offline delivery of attachments
```

**Scenario 7: `DeviceRevocationTests.kt`**
```
7.1 Three-way message exchange (Alice, Bob1, Bob2)
7.2 Bob1 revokes Bob2 using recovery phrase
7.3 All users notified via device announce
7.4 Bob2's messages purged from history
7.5 Bob2 self-bricks (data wiped)
```

**Scenario 8: `ORMTests.kt`**
```
8.1 Auto-generation (ID, timestamp, signature, author)
8.2 Local persistence via ORM finder
8.3 Fan-out to all friend devices
8.4 Self-sync to own devices
8.5 Receiver queries synced data
8.6 Reverse direction sync
8.7 Field validation rejects bad data
```

**Scenario 9: `PixFlowTests.kt`**
```
9.1 Capture + send to single recipient
9.2 Recipient queries unviewed pix, decrypts
9.3 Attachment download + JPEG validation
9.4 Multi-recipient pix (Alice → Bob + Carol)
9.5 Offline delivery
```

**Scenario 10: `StoryAttachmentTests.kt`**
```
10.1 Image-only story creation via ORM
10.2 Story syncs to friends with media via ModelSync
10.3 Receiver decrypts attachment
10.4 Cache works on second load
10.5 Story with text + image combined
```

---

## Execution Order (the loop)

```
 1. gradle init, add deps, ./gradlew build             ← prove toolchain works
 2. Layer 1: protobuf server proto, scratchpad tests    ← ./gradlew test
 3. Layer 2: protobuf client proto, signal store, API   ← ./gradlew test
 4. Layer 3: CRDTs, Model, ModelStore, Sync, TTL        ← ./gradlew test
 5. Layer 4: Domains, MessengerDomain, ObscuraClient    ← ./gradlew build
 6. Scenario 1-4                                        ← ./gradlew test (needs server)
 7. Scenario 5                                          ← ./gradlew test
 8. Scenario 6                                          ← ./gradlew test
 9. Scenario 7                                          ← ./gradlew test
10. Scenario 8                                          ← ./gradlew test
11. Scenario 9                                          ← ./gradlew test
12. Scenario 10                                         ← ./gradlew test
13. Delete src/test/kotlin/scratchpad/                  ← cleanup
```

Each step: build → test → fix → commit. If a scratchpad test reveals a wrong assumption, fix the layer before moving on.

---

## Swift → Kotlin Mapping Reference

| Swift (iOS plan) | Kotlin (this plan) | Notes |
|---|---|---|
| Swift Actor | Class + `limitedParallelism(1)` | Same serial guarantee, not compiler-enforced |
| `async/await` | `suspend fun` | Identical mental model |
| GRDB (SQLite) | SQLDelight (SQLite) | Both type-safe, both pure-platform |
| SwiftProtobuf | protobuf-kotlin | Both generate from .proto |
| libsignal-swift | `org.signal:libsignal-client` (JVM) | Same C library, different bindings |
| URLSession | OkHttp | OkHttp is the standard |
| URLSessionWebSocketTask | OkHttp WebSocket | Built into OkHttp |
| SPM (Package.swift) | Gradle (build.gradle.kts) | Both CLI-only, no IDE needed |
| XCTest | JUnit 5 + coroutines-test | `runTest { }` for suspend funs |
| `@Observable` (SwiftUI) | `StateFlow` / `MutableStateFlow` (Compose) | View observation layer (later) |

## Key Files to Reference During Port

All JS source paths are relative to `/Users/ryanhelsing/Projects/obscura-client-web/`.

| JS Source | Kotlin Target |
|-----------|--------------|
| `public/proto/obscura/v1/obscura.proto` | `proto/server/` (generated) |
| `public/proto/v2/client.proto` | `proto/client/` (generated) |
| `src/v2/orm/crdt/GSet.js` | `orm/crdt/GSet.kt` |
| `src/v2/orm/crdt/LWWMap.js` | `orm/crdt/LWWMap.kt` |
| `src/v2/orm/Model.js` | `orm/Model.kt` |
| `src/v2/orm/storage/ModelStore.js` | `orm/ModelStore.kt` |
| `src/v2/orm/sync/SyncManager.js` | `orm/SyncManager.kt` |
| `src/v2/orm/sync/TTLManager.js` | `orm/TTLManager.kt` |
| `src/v2/orm/QueryBuilder.js` | `orm/QueryBuilder.kt` |
| `src/v2/orm/index.js` | `orm/Schema.kt` |
| `src/v2/store/friendStore.js` | `stores/FriendStore.kt` |
| `src/v2/store/messageStore.js` | `stores/MessageStore.kt` |
| `src/v2/store/deviceStore.js` | `stores/DeviceStore.kt` |
| `src/lib/IndexedDBStore.js` | `crypto/SignalStore.kt` |
| `src/lib/messenger.js` | `MessengerDomain.kt` |
| `src/api/client.js` | `network/APIClient.kt` |
| `src/api/gateway.js` | `network/GatewayConnection.kt` |
| `test/helpers/testClient.js` | `ObscuraTestClient.kt` |

## Verification

- After each layer: `./gradlew build && ./gradlew test`
- After all scenarios: `./gradlew test` with server running
- Final: delete `src/test/kotlin/scratchpad/`, `./gradlew test` — only scenario tests remain
