# ObscuraKit-Kotlin

**Rails for Signal-powered apps.**

ObscuraKit is an E2E encrypted data layer library. Define your models, set sync rules, call `create()` — encryption, CRDT merging, fan-out to devices, persistence, and conflict resolution happen automatically. A developer building on ObscuraKit never thinks about Signal sessions, protobuf framing, or WebSocket management. They think about models, relationships, and queries — exactly like a Rails developer thinks about ActiveRecord.

This is a **library, not an app**. Any Android/JVM application links against it. Pure JVM, no Android Studio required. Tested against `obscura.barrelmaker.dev`.

## The Vision

ObscuraKit makes Signal Protocol as invisible as TCP/IP. Just as a web developer doesn't think about packet framing when they make an HTTP request, an ObscuraKit developer doesn't think about ratchet state when they create a story. The protocol is the backbone — reliable, encrypted, conflict-free — and the developer builds on top of it.

```
What a Rails dev writes:              What ObscuraKit does underneath:
─────────────────────────             ─────────────────────────────────
Post.create(caption: "Hi")           1. Validate against schema
                                      2. Persist to local CRDT (GSet)
                                      3. Signal-encrypt for each friend device
                                      4. Fan out via WebSocket batch
                                      5. Remote devices decrypt + merge
                                      6. Conflict resolution via CRDT semantics
                                      7. UI observers fire automatically
```

## Architecture

Three layers, each completely hiding the one below. The developer surface is Layer 3 — everything beneath it is invisible plumbing.

```
┌─────────────────────────────────────────────────────────────────┐
│                        COMPOSE VIEWS                            │
│  Observes StateFlow<List<Friend>>, model.observe()              │
│  Calls: create(), send(), befriend() — never thinks about       │
│  encryption, sessions, or transport                             │
├─────────────────────────────────────────────────────────────────┤
│                     ObscuraClient (facade)                      │
│  Wires levels together. Owns StateFlows. Envelope loop.         │
│  Views never go below this line.                                │
╞═════════════════════════════════════════════════════════════════╡
│                                                                 │
│  LAYER 3: Application Classes                                   │
│                                                                 │
│  First-class domains:                                           │
│    Friends — prekey ceremony, device announce, fan-out targets  │
│    Devices — identity, linking, revocation, self-sync           │
│                                                                 │
│  ORM (freeform models):                                         │
│    Define any model: stories, posts, settings, streaks          │
│    CRDT-backed: GSet (immutable) / LWWMap (mutable)             │
│    Config-driven sync: private | self | friends | group members │
│    Relationships: has_many / belongs_to                         │
│    TTL, validation, reactive observation via StateFlow          │
│    Rides on: ClientMessage.Type.MODEL_SYNC                      │
│                                                                 │
╞═════════════════════════════════════════════════════════════════╡
│                                                                 │
│  LAYER 2: Encryption Primitives (invisible to Layer 3)          │
│  Signal Protocol encrypt/decrypt, auto-session building         │
│  20+ client-to-client message types                             │
│  Server never sees contents                                     │
│                                                                 │
╞═════════════════════════════════════════════════════════════════╡
│                                                                 │
│  LAYER 1: Server Protocol (invisible to Layer 2)                │
│  WebSocket (EnvelopeBatch/AckMessage) + REST API                │
│  Server is a dumb relay of opaque encrypted blobs               │
│                                                                 │
╞═════════════════════════════════════════════════════════════════╡
│  STORAGE: SQLDelight (Signal keys, friends, messages, ORM)     │
└─────────────────────────────────────────────────────────────────┘
```

**Abstraction contracts:**
- Layer 1 never sees what's inside an encrypted message
- Layer 2 never sees ORM models or how CRDTs merge
- Layer 3 never sees Signal sessions, WebSocket frames, or HTTP calls
- Friends and Devices are first-class at Layer 3 because they require enough protocol ceremony (prekey bundles, device signing, fan-out resolution) to warrant dedicated implementations
- Everything else — stories, posts, settings, streaks, group messages — is a freeform ORM model

## ORM — The Developer Surface

The ORM is the heart of ObscuraKit. It's where a Rails developer should feel immediately at home.

### Defining Models

```kotlin
// Like ActiveRecord: declare fields, relationships, behavior
client.orm.define(mapOf(
    "story" to ModelConfig(
        fields = mapOf("content" to "string", "mediaUrl" to "string?"),
        sync = "gset",                    // immutable, append-only (like a tweet)
        ttl = "24h",                      // auto-expires (like Snapchat)
        hasMany = listOf("comment", "reaction")
    ),
    "comment" to ModelConfig(
        fields = mapOf("text" to "string"),
        sync = "gset",
        ttl = "24h",
        belongsTo = listOf("story", "comment")  // nested comments
    ),
    "reaction" to ModelConfig(
        fields = mapOf("emoji" to "string"),
        sync = "lww",                     // mutable, last-writer-wins (change your reaction)
        ttl = "24h",
        belongsTo = listOf("story", "comment")
    ),
    "settings" to ModelConfig(
        fields = mapOf("theme" to "string", "notificationsEnabled" to "boolean"),
        sync = "lww",
        isPrivate = true                  // only syncs to your own devices, never to friends
    ),
    "group" to ModelConfig(
        fields = mapOf("name" to "string", "members" to "string"),
        sync = "gset",
        hasMany = listOf("groupMessage")
    ),
    "groupMessage" to ModelConfig(
        fields = mapOf("text" to "string", "mediaUrl" to "string?"),
        sync = "gset",
        ttl = "7d",
        belongsTo = listOf("group")       // auto-targets group members, not all friends
    )
))
```

### CRUD — Feels Like ActiveRecord

```kotlin
// Create — persists, encrypts, fans out, notifies observers. One call.
val story = client.orm.model("story")!!
val entry = story.create(mapOf("content" to "Hello world!", "mediaUrl" to null))

// Find by ID
val found = story.find(entry.id)

// Query with conditions
val mine = story.where(mapOf("authorDeviceId" to client.deviceId)).exec()

// Upsert (LWW models only — timestamp conflict resolution)
client.orm.model("settings")!!.upsert("my_settings", mapOf("theme" to "dark"))

// Delete (LWW models only — tombstone pattern)
client.orm.model("reaction")!!.delete(reactionId)
```

### Sync Behavior Is Config-Driven

The developer never calls "send this to Bob's devices." Sync happens automatically based on model config:

| Config | Behavior | Example |
|--------|----------|---------|
| `isPrivate = true` | Own devices only | Settings, drafts |
| `belongsTo = listOf("group")` | Group members only | GroupMessage targets the group's `members` field |
| _(default)_ | All friends | Stories, posts, profile updates |

```kotlin
// Developer writes this:
story.create(mapOf("content" to "Beach day!"))

// ObscuraKit does this automatically:
// 1. GSet.add() — persist locally
// 2. SyncManager.broadcast() — resolve targets from config
// 3. For each target device: Signal-encrypt → queue
// 4. Batch flush over WebSocket
// 5. Remote devices: decrypt → CRDT merge → UI observers fire
```

### Reactive Observation (Compose-Ready)

```kotlin
// Simple: observe all entries
@Composable
fun StoryFeed(client: ObscuraClient) {
    val stories = client.orm.model("story")!!.observe().collectAsState(emptyList())
    LazyColumn { items(stories.value) { StoryCard(it) } }
}

// Power: observe a query
@Composable
fun GroupChat(client: ObscuraClient, groupId: String) {
    val messages = client.orm.model("groupMessage")!!
        .where(mapOf("data.groupId" to groupId))
        .observe()  // Returns Flow<List<OrmEntry>>
        .collectAsState(emptyList())
    LazyColumn { items(messages.value) { MessageBubble(it) } }
}
```

### Relationships — has_many / belongs_to

```kotlin
// Story has_many comments, reactions
// Comment belongs_to story (and recursively, comment)

// Create a comment on a story
val comment = client.orm.model("comment")!!.create(
    mapOf("text" to "Nice!", "storyId" to story.id)
)
// Automatically: stored with association, synced to story's audience

// Eager load (like ActiveRecord includes)
val storiesWithComments = story.where(mapOf())
    .include("comment", "reaction")
    .exec()
// Each story entry now has .comments and .reactions attached
```

### CRDTs — Conflict Resolution Without a Server

ObscuraKit is peer-to-peer (through a dumb relay). There's no central database to be the source of truth. CRDTs solve this:

- **GSet (Grow-only Set):** For immutable content — stories, comments, messages. Add-only. Merge = union. Two devices that independently add entries converge automatically. No conflicts possible.
- **LWWMap (Last-Writer-Wins Map):** For mutable state — settings, reactions, streaks. On conflict, highest timestamp wins. Deterministic, convergent across all replicas. Tombstone pattern for deletion.

The developer never interacts with CRDTs directly. They call `create()` and `upsert()`. The CRDT is chosen by the `sync` config field.

## Public API

### Auth
```kotlin
val client = ObscuraClient(ObscuraConfig(apiUrl = "https://obscura.barrelmaker.dev"))
client.register(username, password)       // user + device + Signal keys
client.login(username, password)          // restore session
client.loginAndProvision(username, password, "Device 2")  // multi-device
client.logout()
```

### Connection
```kotlin
client.connect()      // WebSocket + decrypt/route/ACK loop + token refresh
client.disconnect()
```

Connection management is designed to be as invisible as possible — reconnect, token refresh, and offline queueing are handled automatically. The app calls `connect()` once at startup and doesn't think about it again.

### Friends (First-Class)
```kotlin
client.befriend(userId, username)       // encrypted FRIEND_REQUEST
client.acceptFriend(userId, username)   // encrypted FRIEND_RESPONSE
```

### Messaging
```kotlin
client.send(friendUsername, "Hello!")
client.sendAttachment(friendUsername, attachmentId, contentKey, nonce, mimeType, size)
```

### Attachments
```kotlin
val (id, expiresAt) = client.uploadAttachment(bytes)
val bytes = client.downloadAttachment(id)
```

### Device Management (First-Class)
```kotlin
client.announceDevices()
client.announceDeviceRevocation(friendUsername, remainingDeviceIds)
client.revokeDevice(recoveryPhrase, targetDeviceId)
client.approveLink(newDeviceId, challengeResponse)
client.takeoverDevice()
```

### Recovery
```kotlin
val phrase = client.generateRecoveryPhrase()  // 12-word BIP39
client.getRecoveryPhrase()                    // one-time read
client.getVerifyCode()                        // 4-digit code
client.announceRecovery(phrase)
```

### Session Management
```kotlin
client.resetSessionWith(userId, "reason")
client.resetAllSessions("reason")
client.requestSync()
client.pushHistoryToDevice(targetDeviceId)
```

### Backup
```kotlin
client.uploadBackup()
client.downloadBackup()
client.checkBackup()
```

### Observable State (Compose-Ready)
```kotlin
client.connectionState   // StateFlow<ConnectionState>  — DISCONNECTED, CONNECTING, CONNECTED
client.authState          // StateFlow<AuthState>        — LOGGED_OUT, AUTHENTICATED
client.friendList         // StateFlow<List<FriendData>>
client.pendingRequests    // StateFlow<List<FriendData>>
client.conversations      // StateFlow<Map<String, List<MessageData>>>
client.events             // SharedFlow<ReceivedMessage> — multi-observer stream
client.waitForMessage()   // suspend, for tests
```

### Compose Example
```kotlin
@Composable
fun ChatScreen(client: ObscuraClient, friend: String) {
    val conversations by client.conversations.collectAsState()
    val msgs = conversations[friend] ?: emptyList()

    LazyColumn { items(msgs) { Text(it.content) } }
    TextField(onSend = { scope.launch { client.send(friend, it) } })
}
```

## Production Setup

### Database Driver Injection

ObscuraKit accepts an external `SqlDriver`. The library never handles encryption — the app module provides the driver.

```kotlin
// Tests / development — in-memory (default)
val client = ObscuraClient(ObscuraConfig(apiUrl = "https://..."))

// File-backed (no encryption) — via config
val client = ObscuraClient(ObscuraConfig(apiUrl = "https://...", databasePath = "obscura.db"))

// Android production — encrypted via SQLCipher
val passphrase = getOrCreatePassphrase() // derive from Android Keystore
val factory = SupportSQLiteOpenHelper.Factory(SQLCipherOpenHelperFactory(passphrase))
val driver = AndroidSqliteDriver(
    schema = ObscuraDatabase.Schema,
    context = applicationContext,
    name = "obscura.db",
    factory = factory
)
val client = ObscuraClient(ObscuraConfig(apiUrl = "https://..."), driver)
```

Android app module dependencies for encrypted storage:
```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":lib"))                              // ObscuraKit
    implementation("net.zetetic:sqlcipher-android:4.6.1")       // SQLCipher
    implementation("androidx.sqlite:sqlite:2.4.0")              // AndroidX SQLite
    implementation("app.cash.sqldelight:android-driver:2.0.2")  // SQLDelight Android driver
}
```

### Security Logger

Set a custom logger to monitor security events in production:

```kotlin
client.logger = object : ObscuraLogger {
    override fun decryptFailed(sourceUserId: String, reason: String) {
        analytics.track("security.decrypt_failed", mapOf("reason" to reason))
    }
    override fun tokenRefreshFailed(attempt: Int, reason: String) {
        if (attempt >= 3) showReLoginPrompt()
    }
    override fun identityChanged(address: String) {
        showSafetyNumberChangedWarning(address)
    }
    // ... other methods
}
```

### Certificate Pinning (optional, recommended)

```kotlin
val pinned = OkHttpClient.Builder()
    .certificatePinner(CertificatePinner.Builder()
        .add("obscura.barrelmaker.dev", "sha256/YOUR_PIN_HASH")
        .build())
    .build()
```

See `SECURITY_AUDIT.md` for the full list of security considerations.

## File Structure

### Source

```
lib/src/main/kotlin/com/obscura/kit/
├── ObscuraClient.kt             Public API facade. StateFlows, envelope loop, message routing
├── ObscuraConfig.kt             Config (apiUrl, deviceName)
├── ObscuraTestClient.kt         Raw E2E test client with direct Signal/WebSocket access
├── crypto/
│   ├── SignalStore.kt           SQLDelight-backed Signal store (6 protocol interfaces)
│   ├── Bip39.kt                 Mnemonic generation + PBKDF2 seed derivation
│   ├── Bip39Wordlist.kt         2048-word BIP39 English wordlist
│   └── RecoveryKeys.kt          Recovery phrase -> Curve25519 keypair, sign/verify
├── network/
│   ├── APIClient.kt             OkHttp REST: auth, devices, messages, attachments, backup
│   └── GatewayConnection.kt     OkHttp WebSocket: ticket auth, envelopes, ACK, reconnect
├── orm/                         ** The developer surface **
│   ├── crdt/
│   │   ├── GSet.kt              Grow-only set. Add, merge (union), filter, sort
│   │   └── LWWMap.kt            Last-writer-wins map. Timestamp conflict, tombstone delete
│   ├── Model.kt                 create/find/where/upsert/delete/handleSync/validate/sign
│   ├── ModelConfig.kt           Schema definition (fields, sync type, private, ttl, relationships)
│   ├── ModelStore.kt            SQLDelight persistence + associations + TTL
│   ├── OrmEntry.kt              Universal entry (id, data, timestamp, author, signature)
│   ├── QueryBuilder.kt          Chainable where().orderBy().limit().include().exec()
│   ├── Schema.kt                Define models from config, wire CRDTs + sync
│   ├── SyncManager.kt           Broadcast targeting: self / private / friends / group members
│   └── TTLManager.kt            Parse "24h", schedule expiration, cleanup
└── stores/
    ├── MessengerDomain.kt       Signal encrypt/decrypt, auto-session, queue/flush, device map
    ├── FriendDomain.kt          Friend CRUD, device lists, fan-out targets, export/import
    ├── DeviceDomain.kt          Device identity, own device list, self-sync targets
    ├── MessageDomain.kt         Messages by conversation, migration, device purge
    └── SchemaDomain.kt          ORM schema definition + MODEL_SYNC handling
```

### Schemas

```
lib/src/main/sqldelight/com/obscura/kit/
├── SignalKey.sq        Signal identity, prekeys, signed prekeys, sessions, sender keys
├── Device.sq           Device table + device identity singleton
├── Friend.sq           Friend list with status and device JSON
├── Message.sq          Messages by conversation with author device tracking
└── ModelEntry.sq       ORM entries + associations + TTL expiration

fixtures/
├── obscura.proto       Server protocol: WebSocket frames, envelopes, send/ack
└── client.proto        Client protocol: 20+ message types (text, friend, device, sync, ORM)
```

### Tests — 40 scenarios against live server

```
lib/src/test/kotlin/scenarios/
├── CoreFlowTests.kt              Register, login, befriend, encrypted text exchange
├── MultiDeviceFanOutTests.kt     2 devices same user, fan-out, SENT_SYNC
├── MultiDeviceLinkingTests.kt    Device provisioning, server list, friend targets
├── OfflineSyncTests.kt           Disconnect, queue offline, reconnect + receive
├── AttachmentTests.kt            Upload, download, CONTENT_REFERENCE to friend
├── EdgeCaseTests.kt              Size limits, verify codes, profile ORM sync
├── ORMTests.kt                   MODEL_SYNC CREATE/UPDATE, bidirectional, LWW
├── PixFlowTests.kt               Image upload + encrypted send + download
├── StoryAttachmentTests.kt       Story with media via MODEL_SYNC
├── DeviceRevocationTests.kt      Server device list, signed revocation announce
├── DeviceTakeoverTests.kt        Key replacement, messaging with new keys
├── RecoveryMessagingTests.kt     Recovery announcement, resume messaging
├── SessionResetTests.kt          SESSION_RESET delivery, bulk reset
└── SyncAndBackupTests.kt         Backup round-trip, BIP39, recovery signing, verify code
```

## Multi-App Architecture

ObscuraKit is a shared data layer. Multiple apps can use the same user identity and friend list. Each app installation registers as a separate **device** with the server — the server doesn't know or care which app it is.

```
┌────────────────┐  ┌────────────────┐  ┌────────────────┐
│   Snap Clone   │  │  Insta Clone   │  │   Chat App     │
│   (app UI)     │  │   (app UI)     │  │   (app UI)     │
├────────────────┤  ├────────────────┤  ├────────────────┤
│  ObscuraKit    │  │  ObscuraKit    │  │  ObscuraKit    │
│  device 1      │  │  device 2      │  │  device 3      │
└───────┬────────┘  └───────┬────────┘  └───────┬────────┘
        └─────────┬─────────┴─────────┬─────────┘
            same user, same friends
            same server (dumb relay)
```

**What's shared across apps (automatic):**
- User account (userId, username)
- Friend list (synced via FRIEND_SYNC)
- Sent messages (synced via SENT_SYNC)

**What differentiates apps — ORM model definitions:**
```kotlin
// Snap clone — ephemeral media
client.orm.define(mapOf(
    "snap" to ModelConfig(
        fields = mapOf("mediaRef" to "string", "recipient" to "string"),
        sync = "gset", ttl = "24h"
    ),
    "story" to ModelConfig(
        fields = mapOf("mediaRef" to "string"),
        sync = "gset", ttl = "24h",
        hasMany = listOf("comment", "reaction")
    )
))

// Insta clone — persistent content
client.orm.define(mapOf(
    "post" to ModelConfig(
        fields = mapOf("mediaRef" to "string", "caption" to "string"),
        sync = "gset",
        hasMany = listOf("comment", "like")
    ),
    "comment" to ModelConfig(
        fields = mapOf("text" to "string", "postId" to "string"),
        sync = "gset", belongsTo = listOf("post")
    ),
    "like" to ModelConfig(
        fields = mapOf("postId" to "string"),
        sync = "lww", belongsTo = listOf("post")
    )
))
```

Each app defines its own models at Layer 3. Layers 1 and 2 (transport + encryption) are identical. When a snap syncs via MODEL_SYNC, all devices receive it. Apps ignore model names they don't recognize.

**Signal ratchets are per-device.** Each app has its own Signal identity keypair, its own session state. When Alice sends to Bob, she encrypts separately for each of Bob's devices (fan-out). There's no way to share ratchet state across apps — that's fundamental to Signal Protocol security.

## Multi-Platform

The three-layer architecture ports 1:1 to any platform. Only the edge drivers change:

```
                  Kotlin/Android         Swift/iOS
                  ──────────────         ─────────

Layer 1           OkHttp                 URLSession
                  OkHttp WebSocket       URLSessionWebSocketTask
                  protobuf-kotlin        SwiftProtobuf

Layer 2           libsignal-client       libsignal-swift
                  SQLDelight             GRDB
                  suspend fun            async/await
                  limitedParallelism(1)  actor

Layer 3           identical              identical
                  (pure logic)           (pure logic)

UI binding        StateFlow              @Observable
                  Channel                AsyncStream
```

**Shared across platforms (identical logic):** ORM (GSet, LWWMap, Model, Schema, SyncManager, TTLManager, QueryBuilder), BIP39 mnemonic generation, message routing, model definitions.

**Changes per platform:** Signal store adapter, HTTP client, WebSocket client, SQLite driver, UI observation pattern.

The `.proto` files and SQL schemas are the shared specification — both platforms generate from the same source of truth. A Snap clone on iOS and Android would define identical ORM schemas and sync seamlessly through the same server.

## Build & Test

```bash
# Prerequisites: JDK 21
export JAVA_HOME=/path/to/jdk-21

# Build
./gradlew build

# Test (all 40 scenarios against live server)
./gradlew test

# Test specific suite
./gradlew test --tests "scenarios.CoreFlowTests"
```

## Dependencies

- `org.signal:libsignal-client` — Signal Protocol (JVM)
- `com.google.protobuf:protobuf-kotlin` — protobuf codegen
- `app.cash.sqldelight:sqlite-driver` — pure JVM SQLite
- `com.squareup.okhttp3:okhttp` — HTTP + WebSocket
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` — coroutines + StateFlow
- `org.json:json` — JSON parsing

No Android dependencies. Pure JVM. Everything runs via `./gradlew test`.
