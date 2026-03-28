# ObscuraKit-Kotlin

E2E encrypted data layer for the Obscura protocol. This is a **library, not an app** — it provides the client state machine that any Android/JVM application links against. Pure JVM, no Android Studio required. Tested against `obscura.barrelmaker.dev`.

## Architecture

Three levels stacked and abstracted on each other. Views never go below `ObscuraClient`.

```
┌─────────────────────────────────────────────────────────────────┐
│                        COMPOSE VIEWS                            │
│  Observes StateFlow<List<Friend>>, StateFlow<Map<Messages>>     │
│  Calls: send(), befriend(), acceptFriend(), sendModelSync()     │
├─────────────────────────────────────────────────────────────────┤
│                     ObscuraClient (facade)                      │
│  Wires levels together. Owns StateFlows. Envelope loop.         │
│  Views never go below this line.                                │
╞═════════════════════════════════════════════════════════════════╡
│                                                                 │
│  LEVEL 3: ORM — Application data models                        │
│  Stories, streaks, profiles, settings on CRDT sync              │
│  GSet (add-only) / LWWMap (timestamp wins)                     │
│  Rides on: ClientMessage.Type.MODEL_SYNC                       │
│                                                                 │
╞═════════════════════════════════════════════════════════════════╡
│                                                                 │
│  LEVEL 2: Client Protocol — Encrypted client-to-client         │
│  Signal Protocol encrypt/decrypt, 20+ message types             │
│  Server never sees contents                                     │
│  Rides on: Envelope.message (opaque bytes to server)            │
│                                                                 │
╞═════════════════════════════════════════════════════════════════╡
│                                                                 │
│  LEVEL 1: Server Protocol — Binary transport                   │
│  WebSocket (EnvelopeBatch/AckMessage) + REST API                │
│  Server is a dumb relay of opaque encrypted blobs               │
│                                                                 │
╞═════════════════════════════════════════════════════════════════╡
│  STORAGE: SQLDelight (Signal keys, friends, messages, ORM)     │
└─────────────────────────────────────────────────────────────────┘
```

**Abstraction boundaries:**
- Level 1 never sees what's inside an encrypted message
- Level 2 never sees ORM models or how CRDTs merge
- Level 3 never sees Signal sessions, WebSocket frames, or HTTP calls
- ObscuraClient is the only thing that crosses all three

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

### Friends
```kotlin
client.befriend(userId, username)       // encrypted FRIEND_REQUEST
client.acceptFriend(userId, username)   // encrypted FRIEND_RESPONSE
```

### Messaging
```kotlin
client.send(friendUsername, "Hello!")
client.sendAttachment(friendUsername, attachmentId, contentKey, nonce, mimeType, size)
client.sendModelSync(friendUsername, "story", entryId, data = mapOf("content" to "..."))
```

### Attachments
```kotlin
val (id, expiresAt) = client.uploadAttachment(bytes)
val bytes = client.downloadAttachment(id)
```

### Device Management
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

### Observable State (Compose-ready)
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

### Source — 5,654 lines

```
lib/src/main/kotlin/com/obscura/kit/
├── ObscuraClient.kt          954  Public API facade. StateFlows, envelope loop, message routing
├── ObscuraConfig.kt            7  Config (apiUrl, deviceName)
├── ObscuraTestClient.kt      508  Raw E2E test client with direct Signal/WebSocket access
├── crypto/
│   ├── SignalStore.kt         225  SQLDelight-backed Signal store (6 protocol interfaces)
│   ├── Bip39.kt                65  Mnemonic generation + PBKDF2 seed derivation
│   ├── Bip39Wordlist.kt     2052  2048-word BIP39 English wordlist
│   └── RecoveryKeys.kt        63  Recovery phrase → Curve25519 keypair, sign/verify
├── network/
│   ├── APIClient.kt           349  OkHttp REST: auth, devices, messages, attachments, backup
│   └── GatewayConnection.kt  145  OkHttp WebSocket: ticket auth, envelopes, ACK, reconnect
├── orm/
│   ├── crdt/
│   │   ├── GSet.kt             84  Grow-only set. Add, merge (union), filter, sort
│   │   └── LWWMap.kt          111  Last-writer-wins map. Timestamp conflict, tombstone delete
│   ├── Model.kt               171  create/find/where/upsert/delete/handleSync/validate/sign
│   ├── ModelConfig.kt          15  Schema definition (fields, sync type, private, ttl)
│   ├── ModelStore.kt          116  SQLDelight persistence + associations + TTL
│   ├── OrmEntry.kt             24  Universal entry (id, data, timestamp, author, signature)
│   ├── QueryBuilder.kt         37  Chainable where().exec()
│   ├── Schema.kt               46  Define models from config, wire CRDTs + sync
│   ├── SyncManager.kt          61  Broadcast targeting: self / private / friends / belongs_to
│   └── TTLManager.kt           62  Parse "24h", schedule expiration, cleanup
└── stores/
    ├── MessengerDomain.kt     237  Signal encrypt/decrypt, auto-session, queue/flush, device map
    ├── FriendDomain.kt        152  Friend CRUD, device lists, fan-out targets, export/import
    ├── DeviceDomain.kt         82  Device identity, own device list, self-sync targets
    ├── MessageDomain.kt        57  Messages by conversation, migration, device purge
    └── SchemaDomain.kt         31  ORM schema definition + MODEL_SYNC handling
```

### Schemas — 235 lines SQL, 340 lines proto

```
lib/src/main/sqldelight/com/obscura/kit/
├── SignalKey.sq        78  Signal identity, prekeys, signed prekeys, sessions, sender keys
├── Device.sq           46  Device table + device identity singleton
├── Friend.sq           27  Friend list with status and device JSON
├── Message.sq          27  Messages by conversation with author device tracking
└── ModelEntry.sq       57  ORM entries + associations + TTL expiration

fixtures/
├── obscura.proto       84  Server protocol: WebSocket frames, envelopes, send/ack
└── client.proto       256  Client protocol: 20+ message types (text, friend, device, sync, ORM)
```

### Tests — 1,184 lines, 40 scenarios against live server

```
lib/src/test/kotlin/scenarios/
├── CoreFlowTests.kt              97  Register, login, befriend, encrypted text exchange
├── MultiDeviceFanOutTests.kt    114  2 devices same user, fan-out, SENT_SYNC
├── MultiDeviceLinkingTests.kt    90  Device provisioning, server list, friend targets
├── OfflineSyncTests.kt           82  Disconnect, queue offline, reconnect + receive
├── AttachmentTests.kt            77  Upload, download, CONTENT_REFERENCE to friend
├── EdgeCaseTests.kt              97  Size limits, verify codes, profile ORM sync
├── ORMTests.kt                   88  MODEL_SYNC CREATE/UPDATE, bidirectional, LWW
├── PixFlowTests.kt               82  Image upload + encrypted send + download
├── StoryAttachmentTests.kt       88  Story with media via MODEL_SYNC
├── DeviceRevocationTests.kt      63  Server device list, signed revocation announce
├── DeviceTakeoverTests.kt        75  Key replacement, messaging with new keys
├── RecoveryMessagingTests.kt     75  Recovery announcement, resume messaging
├── SessionResetTests.kt          63  SESSION_RESET delivery, bulk reset
└── SyncAndBackupTests.kt         93  Backup round-trip, BIP39, recovery signing, verify code
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
// Snap clone
client.orm.define(mapOf(
    "snap" to ModelConfig(
        fields = mapOf("mediaRef" to "string", "recipient" to "string"),
        sync = "gset", ttl = "24h"
    ),
    "story" to ModelConfig(
        fields = mapOf("mediaRef" to "string"),
        sync = "gset", ttl = "24h"
    )
))

// Insta clone
client.orm.define(mapOf(
    "post" to ModelConfig(
        fields = mapOf("mediaRef" to "string", "caption" to "string"),
        sync = "gset"
    ),
    "comment" to ModelConfig(
        fields = mapOf("text" to "string", "postId" to "string"),
        sync = "gset", belongsTo = listOf("post")
    ),
    "like" to ModelConfig(
        fields = mapOf("postId" to "string"),
        sync = "lww"
    )
))
```

Each app defines its own models at Level 3. Levels 1 and 2 (transport + encryption) are identical. When a snap syncs via MODEL_SYNC, all devices receive it. Apps ignore model names they don't recognize.

**Signal ratchets are per-device.** Each app has its own Signal identity keypair, its own session state. When Alice sends to Bob, she encrypts separately for each of Bob's devices (fan-out). There's no way to share ratchet state across apps — that's fundamental to Signal Protocol security.

## Multi-Platform

The three-level architecture ports 1:1 to any platform. Only the edge drivers change:

```
                  Kotlin/Android         Swift/iOS
                  ──────────────         ─────────

Level 1           OkHttp                 URLSession
                  OkHttp WebSocket       URLSessionWebSocketTask
                  protobuf-kotlin        SwiftProtobuf

Level 2           libsignal-client       libsignal-swift
                  SQLDelight             GRDB
                  suspend fun            async/await
                  limitedParallelism(1)  actor

Level 3           identical              identical
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
