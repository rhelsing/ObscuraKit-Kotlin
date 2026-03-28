# Code Quality Roadmap: LLM Duct Tape → Production Grade

**Reference:** Signal-Android open source codebase patterns
**Goal:** Three levels of refinement, each independently shippable

---

## Level 1: Stop the embarrassment (2-3 hours)

Quick fixes that a reviewer would catch in 5 minutes. No architecture changes. Tests keep passing.

| # | Fix | What Signal does |
|---|-----|-----------------|
| 1 | **Extract `UuidCodec.kt`** — one file, shared by MessengerDomain + TestClient. Currently duplicated with different return types. | Signal has `Hex`, `Base64`, `Util.getSecretBytes()` in `core-util/` |
| 2 | **Extract `Base64Extensions.kt`** — `fun ByteArray.encodeBase64()` / `fun String.decodeBase64()`. Currently 40+ raw `Base64.getEncoder().encodeToString()` calls scattered across 5 files. | Signal has `Base64.encodeWithPadding()` / `decode()` |
| 3 | **Delete duplicate `fetchPreKeyBundlesInternal`** — extract JSON→PreKeyBundle parsing to a helper, call from both `fetchPreKeyBundles` and `ensureSession`. Currently 37 lines copy-pasted. | Signal never duplicates parsing code |
| 4 | **Replace all magic strings with enums** — `"accepted"` → `FriendStatus.ACCEPTED.value`, `"add"` → enum, `"pending_sent"` → enum. The enums exist but aren't used consistently. | Signal uses enums/sealed classes for all state |
| 5 | **Remove `delay(500)`** — replace with explicit `rateLimitDelay()` that's configurable and documented. Currently 3 unexplained delays in auth paths. | Signal uses `Util.sleep()` with explicit backoff constants |
| 6 | **Remove `!!` operators** — use safe calls or restructure to guarantee non-null. Currently ~15 non-null assertions after values were just set. | Signal uses `@NonNull` annotations + `Objects.requireNonNull()` at boundaries |
| 7 | **Close channels on disconnect** — `gateway.envelopes` and `gateway.preKeyStatus` are never closed, leaking coroutines on reconnect. | Signal closes their `LinkedBlockingQueue` on WebSocket close |
| 8 | **Remove audit trail comments** — `// H1 fix:`, `// C2 fix:` → explain WHY not WHAT. Currently reads like a security patch changelog. | Signal comments explain rationale, not ticket numbers |
| 9 | **Use `AtomicReference` for `refreshInProgress`** — currently a bare `var` accessed from multiple coroutines without synchronization. | Signal uses `synchronized` blocks for shared state |

---

## Level 2: Structural refactor (1-2 days)

Break the god class, introduce proper error types, make it testable.

### 2.1 Split ObscuraClient into managers

Currently 1,159 lines, 33 public methods. Extract:

```
ObscuraClient (thin coordinator — ~200 lines)
├── AuthManager        register, login, loginAndProvision, logout, token refresh
├── MessagingManager   send, sendAttachment, sendModelSync, sendRaw
├── FriendshipManager  befriend, acceptFriend
├── DeviceManager      announceDevices, announceDeviceRevocation, revokeDevice, approveLink, takeoverDevice
├── SyncManager        requestSync, pushHistoryToDevice, uploadBackup, downloadBackup
└── RecoveryManager    generateRecoveryPhrase, getRecoveryPhrase, getVerifyCode, announceRecovery
```

ObscuraClient becomes a coordinator that holds references to managers and exposes StateFlows. Each manager gets the dependencies it needs via constructor injection.

**What Signal does:** `ApplicationDependencies` as coordinator with ~30 lazy singletons. Each concern is its own class.

### 2.2 Custom exception hierarchy

```kotlin
sealed class ObscuraException(message: String) : Exception(message)
class NetworkException(message: String, val statusCode: Int? = null) : ObscuraException(message)
class AuthException(message: String) : ObscuraException(message)
class RateLimitException(val retryAfterMs: Long) : ObscuraException("Rate limited")
class ProtocolException(message: String) : ObscuraException(message)
class EncryptionException(message: String) : ObscuraException(message)
```

Replace all bare `catch (e: Exception) {}` with typed catches.

**What Signal does:** `PushNetworkException`, `AuthorizationFailedException`, `RateLimitException`, `ProofRequiredException` — each with relevant metadata.

### 2.3 Dependency injection via constructor

```kotlin
class ObscuraClient(
    val config: ObscuraConfig,
    val api: APIClient = APIClient(config.apiUrl),
    val gateway: GatewayConnection = GatewayConnection(api, scope),
    val signalStore: SignalStore = SignalStore(db),
    // ...
)
```

For testing: swap any dependency with a fake. No more duplicated logic in TestClient.

**What Signal does:** `Provider` interface with factory methods for each dependency, swappable for tests.

### 2.4 Extract PreKeyBundleParser

```kotlin
object PreKeyBundleParser {
    fun parse(bundlesJson: JSONArray, deviceMap: MutableMap<String, Pair<String, Int>>, userId: String): List<PreKeyBundle>
}
```

One place, called from both `fetchPreKeyBundles` and `ensureSession`.

### 2.5 Typed ParsedSyncBlob

Replace `Map<String, Any?>` with:

```kotlin
data class ParsedFriend(val userId: String, val username: String, val status: String)
data class ParsedMessage(val messageId: String, val conversationId: String, val content: String, val timestamp: Long, val type: String, val authorDeviceId: String)
data class ParsedSyncBlob(val friends: List<ParsedFriend>, val messages: List<ParsedMessage>)
```

No more unsafe casts in `processSyncBlob`.

### 2.6 Rate limiter as a proper concern

```kotlin
class RateLimiter(private val delayMs: Long = 500, private val jitterMs: Long = 200) {
    suspend fun delay() {
        delay(delayMs + Random.nextLong(jitterMs))
    }
}
```

Injected into auth paths. Configurable. Testable (set to 0 for tests).

---

## Level 3: Production grade (1 week)

What Signal ships. The difference between "works" and "maintainable at scale."

### 3.1 Job queue for reliability

Every send/sync is a persisted job with retry. If the app crashes mid-send, the job resumes on restart. Currently `queueMessage → flushMessages` is fire-and-forget.

**What Signal does:** `PushTextSendJob`, `PushMediaSendJob`, `AttachmentUploadJob` — all WorkManager jobs with `Result.SUCCESS/FAILURE/RETRY`.

### 3.2 Recipient cache

Cache user/contact info in memory with Flow observation. Currently friend list re-queries SQLite on every access.

**What Signal does:** `Recipient.resolved(id)` returns cached object. `Recipient.live()` returns observable `LiveData`.

### 3.3 Database migration framework

Currently `ObscuraDatabase.Schema.create(driver)` creates tables from scratch. No way to add columns or change schema without wiping data.

**What Signal does:** Database version 215. Each migration is a numbered class. SQLDelight supports this via `.sqm` migration files.

### 3.4 Multi-module Gradle

Separate `core-util`, `lib-signal`, `lib-orm`, `app`. Clean dependency boundaries enforced by module visibility.

**What Signal does:** 15+ Gradle modules with clear dependency graph.

### 3.5 Sealed class message routing

Replace the `when(msg.type)` chain with a sealed class hierarchy that the compiler enforces completeness on:

```kotlin
sealed class IncomingMessage {
    data class Text(val text: String, val from: String) : IncomingMessage()
    data class FriendRequest(val username: String, val from: String) : IncomingMessage()
    data class FriendResponse(val accepted: Boolean, val from: String) : IncomingMessage()
    data class DeviceAnnounce(val devices: List<DeviceInfo>, val isRevocation: Boolean) : IncomingMessage()
    data class ModelSync(val model: String, val id: String, val data: Map<String, Any?>) : IncomingMessage()
    // ...
}
```

Compiler error if you add a new type but forget to handle it.

### 3.6 Proguard/R8 rules + instrumentation tests

Keep rules for protobuf, Signal, reflection. Strip debug info from release. Run tests on real Android device to verify SQLite behavior, Keystore, lifecycle.

---

## Priority

Level 1 is a prerequisite for code review by anyone. Do it before showing the code.

Level 2 is a prerequisite for adding features. Without it, every new feature makes ObscuraClient bigger.

Level 3 is a prerequisite for shipping to users. Without it, the app works but can't be maintained.
