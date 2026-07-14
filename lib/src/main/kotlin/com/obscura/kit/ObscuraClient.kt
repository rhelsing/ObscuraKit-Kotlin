package com.obscura.kit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.crypto.ParsedSyncBlob
import com.obscura.kit.crypto.SignalStore
import com.obscura.kit.crypto.toBase64
import com.obscura.kit.db.ObscuraDatabase
import com.obscura.kit.managers.*
import com.obscura.kit.managers.SignalKeyUtils.toApiJson
import com.obscura.kit.network.APIClient
import com.obscura.kit.network.GatewayConnection
import com.obscura.kit.network.GatewayState
import com.obscura.kit.network.UploadDeviceKeysRequest
import com.obscura.kit.orm.ModelStore
import com.obscura.kit.orm.SignalManager
import com.obscura.kit.orm.ModelSyncData
import com.obscura.kit.orm.SyncManager
import com.obscura.kit.orm.TTLManager
import com.obscura.kit.stores.*
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.obscura.kit.orm.ModelConfig
import com.obscura.kit.orm.SyncStrategy
import com.obscura.kit.persistence.NoOpSessionStorage
import com.obscura.kit.persistence.SessionStorage
import obscura.client.v1.Client.ClientMessage
import org.json.JSONObject
import org.signal.libsignal.protocol.ecc.Curve
import java.util.*

data class ReceivedMessage(
    val type: String,
    val text: String = "",
    val username: String = "",
    val accepted: Boolean = false,
    val sourceUserId: String = "",
    val senderDeviceId: String? = null,
    val raw: ClientMessage? = null
)

/**
 * Public connection state. 1:1 with the network layer's [GatewayState] and with
 * the Swift kit's ConnectionState (disconnected/connecting/reconnecting/connected):
 *   CONNECTING   — first connection attempt in progress
 *   RECONNECTING — a dropped connection is being retried (backoff)
 *   CONNECTED    — websocket open
 */
enum class ConnectionState { DISCONNECTED, CONNECTING, RECONNECTING, CONNECTED }

enum class AuthState { LOGGED_OUT, PENDING_APPROVAL, AUTHENTICATED }

/**
 * Result of [ObscuraClient.processPendingMessages] — counts of envelopes drained.
 *
 * [modelCounts] maps each ORM model name that appeared in the drained batch to its
 * envelope count (e.g. `{"pix" → 3, "directMessage" → 1}`). Bridges use these to
 * post model-specific local notifications without the kit embedding any model names.
 * [otherCount] covers non-MODEL_SYNC envelopes (TEXT, IMAGE, etc.) and is exposed
 * for debugging only.
 *
 * Note: Unlike the earlier integer-field shape, this API is intentionally generic —
 * the kit never sniffs application model names in production code.
 */
data class ProcessedCounts(
    val modelCounts: Map<String, Int> = emptyMap(),
    val otherCount: Int = 0
)

/**
 * Create with default JVM in-memory driver (for tests).
 * For Android production, pass an encrypted AndroidSqliteDriver:
 *
 *   val driver = AndroidSqliteDriver(
 *       ObscuraDatabase.Schema,
 *       context,
 *       "obscura.db",
 *       factory = SupportSQLiteOpenHelper.Factory(SQLCipherOpenHelperFactory(passphrase))
 *   )
 *   val client = ObscuraClient(config, driver)
 *
 * Implements [AutoCloseable] — call [close] to cancel all coroutines, close the
 * gateway connection, and release driver resources. [close] is idempotent and
 * safe to call multiple times.
 */
class ObscuraClient(
    val config: ObscuraConfig,
    externalDriver: app.cash.sqldelight.db.SqlDriver? = null,
    val sessionStorage: SessionStorage = NoOpSessionStorage
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * True once [close] has been called. A closed client is inert and MUST NOT be reused —
     * its coroutine scope is cancelled and, if it owned the driver, the database is shut.
     *
     * [connect] enforces this explicitly (see [checkNotClosed]) because a cancelled scope
     * otherwise surfaces as a confusing downstream failure rather than a clear one. Other
     * methods are not individually guarded: use a fresh client after [close].
     */
    @Volatile private var isClosed = false

    /** @throws IllegalStateException if this client has already been [close]d. */
    private fun checkNotClosed() {
        check(!isClosed) { "ObscuraClient has been closed and cannot be reused; construct a new one." }
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _authState = MutableStateFlow(AuthState.LOGGED_OUT)
    val authState: StateFlow<AuthState> = _authState

    private val _friendList = MutableStateFlow<List<FriendData>>(emptyList())
    val friendList: StateFlow<List<FriendData>> = _friendList

    private val _pendingRequests = MutableStateFlow<List<FriendData>>(emptyList())
    val pendingRequests: StateFlow<List<FriendData>> = _pendingRequests

    private val _conversations = MutableStateFlow<Map<String, List<MessageData>>>(emptyMap())
    val conversations: StateFlow<Map<String, List<MessageData>>> = _conversations

    private val _events = MutableSharedFlow<ReceivedMessage>(extraBufferCapacity = 64)
    @Deprecated("Use typedEvents instead — single typed event stream for bridges")
    val events: SharedFlow<ReceivedMessage> = _events

    // Typed event stream — bridges subscribe to this instead of observing 5 separate flows
    private val _typedEvents = MutableSharedFlow<ObscuraEvent>(extraBufferCapacity = 64)
    val typedEvents: SharedFlow<ObscuraEvent> = _typedEvents

    private val driver = externalDriver ?: run {
        if (config.databasePath != null) {
            if (!config.allowUnencryptedDatabase) {
                System.err.println(
                    "ObscuraKit WARNING: using an UNENCRYPTED SQLite database at '${config.databasePath}'. " +
                    "For production use, provide an encrypted AndroidSqliteDriver (e.g. SQLCipher) " +
                    "and leave databasePath = null. " +
                    "To suppress this warning, set ObscuraConfig(allowUnencryptedDatabase = true)."
                )
            }
            JdbcSqliteDriver("jdbc:sqlite:${config.databasePath}")
        } else {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        }
    }
    private val driverIsOwned = externalDriver == null
    internal val db: ObscuraDatabase

    internal val signalStore: SignalStore
    internal val api = APIClient(config.apiUrl)
    internal val gateway: GatewayConnection

    private val friends: FriendDomain
    private val messagesDomain: MessageDomain
    internal val devices: DeviceDomain
    internal val messenger: MessengerDomain
    val orm: SchemaDomain

    private val modelStore: ModelStore
    private val syncManager: SyncManager
    private val ttlManager: TTLManager
    private val signalManager: SignalManager

    // Session — shared mutable state
    private val session = ClientSession()

    // Managers
    private val authManager: AuthManager
    private val messageSender: MessageSender
    private val recoveryManager: RecoveryManager
    private val friendshipManager: FriendshipManager
    private val messagingManager: MessagingManager
    private val deviceManager: DeviceManager
    private val clientSyncManager: ClientSyncManager

    // Identity — delegate to session
    var userId: String?
        get() = session.userId
        private set(value) { session.userId = value }
    var deviceId: String?
        get() = session.deviceId
        private set(value) { session.deviceId = value }
    var username: String?
        get() = session.username
        private set(value) { session.username = value }
    var refreshToken: String?
        get() = session.refreshToken
        private set(value) { session.refreshToken = value }
    var registrationId: Int
        get() = session.registrationId
        private set(value) { session.registrationId = value }
    val token: String? get() = api.token

    // recoveryPhrase and recoveryPublicKey are managed via session + RecoveryManager

    /** Structured logger for security events. Set to a custom implementation for production. */
    var logger: ObscuraLogger = NoOpLogger

    /**
     * The kit's inbound-message stream and the intended public consumer API:
     * exactly one consumer should drain this channel (e.g. the app's
     * process-scoped session), classify each [ReceivedMessage], and fan out to
     * UI/notifications. Buffered so messages that arrive before a consumer
     * attaches (e.g. an FCM cold-start) are not dropped.
     */
    val incomingMessages = Channel<ReceivedMessage>(capacity = 1000)

    /** Debug log — ring buffer of last 200 events. Thread-safe. */
    val debugLog = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private fun log(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        debugLog.addFirst("[$ts] $msg")
        while (debugLog.size > 200) debugLog.removeLast()
    }

    /** Dump debug log + current status as a single string for clipboard/paste. */
    fun dumpDebugLog(): String {
        val status = buildString {
            appendLine("=== ObscuraKit Debug Dump ===")
            appendLine("user: $username ($userId)")
            appendLine("device: $deviceId")
            appendLine("auth: ${_authState.value}")
            appendLine("connection: ${_connectionState.value}")
            appendLine("friends: ${friendList.value.size} (${friendList.value.count { it.status == com.obscura.kit.stores.FriendStatus.ACCEPTED }} accepted)")
            appendLine("prekeys: ${try { signalStore.getPreKeyCount() } catch (_: Exception) { "?" }}")
            appendLine("---")
        }
        return status + debugLog.joinToString("\n")
    }

    private var envelopeJob: Job? = null

    // M13: Decrypt rate limiting per sender
    private val decryptFailures = mutableMapOf<String, Pair<Int, Long>>() // senderId -> (count, windowStart)
    private val MAX_DECRYPT_FAILURES = 10
    private val DECRYPT_FAILURE_WINDOW_MS = 60_000L

    // Prekey replenishment
    private val PREKEY_MIN_COUNT = 20L
    private val PREKEY_REPLENISH_COUNT = 50

    init {
        if (externalDriver == null) {
            ObscuraDatabase.Schema.create(driver)
            try { driver.execute(null, "PRAGMA secure_delete = ON", 0) } catch (e: Exception) { log("PRAGMA secure_delete failed: ${e.message}") }
        }
        // Run schema migrations once the tables exist (Schema.create above, for a driver we
        // own) but before ObscuraDatabase wraps the driver, so no generated query ever runs
        // against a stale shape. Migrations are idempotent and introspection-guarded, so this
        // is also correct for an external driver whose schema the caller created — notably
        // AndroidSqliteDriver, which already owns PRAGMA user_version. See DatabaseMigrations.
        DatabaseMigrations.migrate(driver)
        db = ObscuraDatabase(driver)

        signalStore = SignalStore(db)
        signalStore.onIdentityChanged = { address, _, _ ->
            logger.identityChanged(address)
        }
        friends = FriendDomain(db)
        messagesDomain = MessageDomain(db)
        devices = DeviceDomain(db)
        messenger = MessengerDomain(signalStore, api)

        modelStore = ModelStore(db)
        syncManager = SyncManager(modelStore)
        ttlManager = TTLManager(modelStore)
        signalManager = SignalManager()
        gateway = GatewayConnection(api, scope)

        orm = SchemaDomain(
            modelStore, syncManager, ttlManager,
            // Read the deviceId fresh on each entry create — it's null at
            // construction time and only becomes available after register /
            // loginAndProvision / restoreSession.
            deviceIdProvider = { session.deviceId ?: "" },
            signalManager = signalManager,
        )

        // Create ClientContext — shared dependencies for all managers
        val ctx = ClientContext(
            session = session,
            api = api,
            signalStore = signalStore,
            messenger = messenger,
            friends = friends,
            devices = devices,
            messages = messagesDomain,
            db = db
        )

        // Create managers — order matters: AuthManager before MessageSender
        authManager = AuthManager(
            ctx = ctx,
            config = config,
            gateway = gateway,
            scope = scope,
            setAuthState = { _authState.value = it },
            setDisconnected = { disconnect() },
            loggerProvider = { logger },
            onLogout = {
                envelopeJob?.cancel()
                gateway.disconnect() // fires onStateChanged → _connectionState = DISCONNECTED
                // Data stays — logout is not a wipe. Login again restores full state.
            },
            onWipeDevice = {
                envelopeJob?.cancel()
                gateway.disconnect() // fires onStateChanged → _connectionState = DISCONNECTED
                db.friendQueries.deleteAll()
                db.messageQueries.deleteAll()
                db.deviceQueries.deleteAllDevices()
                db.deviceQueries.deleteIdentity()
                db.signalKeyQueries.deleteLocalIdentity()
                db.signalKeyQueries.deleteAllSignalData()
                db.signalKeyQueries.deleteAllPreKeys()
                db.signalKeyQueries.deleteAllSignedPreKeys()
                db.signalKeyQueries.deleteAllSessions()
                db.signalKeyQueries.deleteAllSenderKeys()
                db.modelEntryQueries.deleteAllEntries()
                db.modelEntryQueries.deleteAllAssociations()
            },
            onSessionChanged = { persistSession() }
        )

        messageSender = MessageSender(messenger, authManager)
        ctx.messageSender = messageSender

        // Wire gateway reconnect token refresh
        gateway.ensureFreshToken = { authManager.ensureFreshToken() }

        // connectionState is a pure projection of the real socket state. The
        // gateway owns the socket lifecycle (connect, background drop, auto-
        // reconnect); this callback is the SINGLE writer of _connectionState, so
        // there's exactly one source of truth. It fires synchronously from the
        // gateway (see GatewayConnection.setState), which is why connect() — after
        // awaiting the open signal — observes CONNECTED without a race.
        gateway.onStateChanged = { gs ->
            _connectionState.value = when (gs) {
                GatewayState.DISCONNECTED -> ConnectionState.DISCONNECTED
                GatewayState.CONNECTING   -> ConnectionState.CONNECTING
                GatewayState.RECONNECTING -> ConnectionState.RECONNECTING
                GatewayState.CONNECTED    -> ConnectionState.CONNECTED
            }
        }

        // Wire ORM auto-sync: model.create() → encrypt → fan out → flush
        syncManager.getSelfSyncTargets = { devices.getSelfSyncTargets() }
        syncManager.getFriendTargets = {
            val accepted = friends.getAccepted()
            val targets = mutableListOf<String>()
            for (f in accepted) {
                var deviceIds = messenger.getDeviceIdsForUser(f.userId)
                if (deviceIds.isEmpty()) {
                    // Discover devices via prekey bundle fetch (same as MessageSender)
                    try { messenger.fetchPreKeyBundles(f.userId) } catch (e: Exception) { log("prekey bundle fetch failed: ${e.message}") }
                    deviceIds = messenger.getDeviceIdsForUser(f.userId)
                }
                targets.addAll(deviceIds)
            }
            targets
        }
        syncManager.queueModelSync = { targetDeviceId, modelSync ->
            val msg = ClientMessage.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setModelSync(obscura.client.v1.modelSync {
                    model = modelSync.model; id = modelSync.id
                    op = com.obscura.kit.orm.WireCodec.encodeOp(modelSync.op)
                    timestamp = modelSync.timestamp
                    data = com.google.protobuf.ByteString.copyFrom(modelSync.data)
                    authorDeviceId = modelSync.authorDeviceId
                }).build()
            val mapped = messenger.deviceMap(targetDeviceId)
            messenger.queueMessage(targetDeviceId, msg, mapped?.first)
        }
        syncManager.getDevicesForUsername = { username ->
            val friend = friends.getAccepted().find { it.username == username }
            if (friend != null) {
                var deviceIds = messenger.getDeviceIdsForUser(friend.userId)
                if (deviceIds.isEmpty()) {
                    try { messenger.fetchPreKeyBundles(friend.userId) } catch (e: Exception) { log("prekey bundle fetch failed: ${e.message}") }
                    deviceIds = messenger.getDeviceIdsForUser(friend.userId)
                }
                deviceIds
            } else emptyList()
        }
        syncManager.getDevicesForUserId = { userId ->
            // Resolve ONLY accepted friends. A 1:1 conversationId ("selfId_friendId") contains
            // the sender's own id too, but self-sync is handled separately by getSelfSyncTargets
            // (which excludes the current device). Resolving self here would target the sender's
            // OWN current device and echo the message back into the sender's own inbox. Excluding
            // self also blocks sending a scoped payload to an arbitrary (e.g. tampered) userId.
            val isFriend = friends.getAccepted().any { it.userId == userId }
            if (isFriend) {
                var deviceIds = messenger.getDeviceIdsForUser(userId)
                if (deviceIds.isEmpty()) {
                    try { messenger.fetchPreKeyBundles(userId) } catch (e: Exception) { log("prekey bundle fetch failed: ${e.message}") }
                    deviceIds = messenger.getDeviceIdsForUser(userId)
                }
                deviceIds
            } else emptyList()
        }
        syncManager.flushQueue = {
            authManager.ensureFreshToken()
            messenger.flushMessages()
        }

        // Wire ephemeral signal sending — typed MODEL_SIGNAL payload (no JSON).
        // Sender identity + timestamp ride on the ClientMessage envelope, not the payload.
        // The contextKey is an opaque application-chosen string (e.g. a conversation ID);
        // the kit treats it as an opaque string and never interprets its format.
        signalManager.sendSignal = { modelName, signalName, contextKey ->
            val kind = com.obscura.kit.orm.WireCodec.encodeSignalKind(signalName)
            val signalMsg = ClientMessage.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setModelSignal(obscura.client.v1.modelSignal {
                    model = modelName
                    this.kind = kind
                    contextId = contextKey
                })
                .build()
            authManager.ensureFreshToken()
            val accepted = friends.getAccepted()
            for (f in accepted) {
                var deviceIds = messenger.getDeviceIdsForUser(f.userId)
                if (deviceIds.isEmpty()) {
                    try { messenger.fetchPreKeyBundles(f.userId) } catch (e: Exception) { log("prekey bundle fetch failed: ${e.message}") }
                    deviceIds = messenger.getDeviceIdsForUser(f.userId)
                }
                for (devId in deviceIds) {
                    messenger.queueMessage(devId, signalMsg, f.userId)
                }
            }
            messenger.flushMessages()
        }

        recoveryManager = RecoveryManager(ctx = ctx, config = config)

        friendshipManager = FriendshipManager(ctx = ctx)

        messagingManager = MessagingManager(ctx = ctx)

        clientSyncManager = ClientSyncManager(ctx = ctx)

        deviceManager = DeviceManager(
            ctx = ctx,
            clientSyncManager = { clientSyncManager },
            announceDevicesCallback = { announceDevices() }
        )

        // Reactive observation — auto-updates StateFlows when DB changes
        startDatabaseObservation()
    }

    private fun startDatabaseObservation() {
        scope.launch {
            db.friendQueries.selectAll()
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { rows -> rows.map { it.toFriendData() } }
                .collect { _friendList.value = it }
        }

        scope.launch {
            db.friendQueries.selectByStatus(FriendStatus.PENDING_RECEIVED.value)
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { rows -> rows.map { it.toFriendData() } }
                .collect { _pendingRequests.value = it }
        }
    }

    private fun com.obscura.kit.Friend.toFriendData(): FriendData {
        return FriendData(
            userId = user_id,
            username = username,
            status = FriendStatus.entries.find { it.value == status } ?: FriendStatus.PENDING_SENT,
            devices = try {
                val arr = org.json.JSONArray(devices)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    FriendDeviceInfo(
                        deviceUuid = obj.optString("deviceUuid", ""),
                        deviceId = obj.optString("deviceId", ""),
                        deviceName = obj.optString("deviceName", "")
                    )
                }
            } catch (e: Exception) { emptyList() }
        )
    }

    suspend fun register(username: String, password: String) {
        authManager.register(username, password)
        orm.setUsername(username)
    }
    suspend fun login(username: String, password: String): com.obscura.kit.network.LoginResult {
        val result = authManager.login(username, password)
        orm.setUsername(username)
        return result
    }
    suspend fun loginAndProvision(username: String, password: String, deviceName: String = "Device 2") =
        authManager.loginAndProvision(username, password, deviceName)

    fun restoreSession(
        token: String,
        refreshToken: String?,
        userId: String,
        deviceId: String?,
        username: String?,
        registrationId: Int = 0
    ) {
        authManager.restoreSession(token, refreshToken, userId, deviceId, username, registrationId)
        if (username != null) orm.setUsername(username)
    }

    fun hasSession(): Boolean = authManager.hasSession()

    /**
     * Log out: tears down the connection and forgets the session, INCLUDING the
     * persisted [sessionStorage] blob, so the app won't try to restore it next
     * launch. Local data (friends, messages, ORM) is kept — see [wipeDevice] /
     * [fullLogout] to also erase that. Symmetric with [persistSession].
     */
    suspend fun logout() {
        authManager.logout()
        sessionStorage.clear()
    }
    suspend fun wipeDevice() = authManager.wipeDevice()
    suspend fun ensureFreshToken(): Boolean = authManager.ensureFreshToken()

    // ─── Session Persistence (kit-owned) ──────────────────

    /** Persist current session to storage. Auto-called on auth/connect. */
    fun persistSession() {
        // Merge onto existing storage so non-session metadata (e.g. cachedSchema,
        // written by defineModelsFromJson) survives a session-only save regardless
        // of whether the SessionStorage impl patches keys or replaces the blob.
        val data = (sessionStorage.load()?.toMutableMap() ?: mutableMapOf()).apply {
            put("token", token)
            put("refreshToken", refreshToken)
            put("userId", userId)
            put("deviceId", deviceId)
            put("username", username)
            put("registrationId", registrationId)
        }
        sessionStorage.save(data)
        log("SESSION persisted user=$username")
    }

    /**
     * Restore session from storage, define cached models, connect.
     * Returns true if session was restored and connected.
     */
    suspend fun restorePersistedSession(): Boolean {
        val saved = sessionStorage.load() ?: return false
        val savedToken = saved["token"] as? String ?: return false
        val savedUserId = saved["userId"] as? String ?: return false
        if (savedToken.isBlank() || savedUserId.isBlank()) return false

        log("SESSION restoring user=${saved["username"]}")
        restoreSession(
            token = savedToken,
            refreshToken = saved["refreshToken"] as? String,
            userId = savedUserId,
            deviceId = saved["deviceId"] as? String,
            username = saved["username"] as? String,
            registrationId = (saved["registrationId"] as? Number)?.toInt() ?: 0
        )

        // Define models from cached schema if available
        val cachedSchema = saved["cachedSchema"] as? String
        if (cachedSchema != null) {
            try {
                defineModelsFromJson(cachedSchema)
                log("SESSION models defined from cache")
            } catch (e: Exception) {
                log("SESSION cached schema invalid: ${e.message}")
            }
        }

        // Refresh token + connect
        try {
            val fresh = ensureFreshToken()
            if (!fresh) {
                log("SESSION token refresh failed — clearing")
                sessionStorage.clear()
                return false
            }
            connect()
            persistSession() // save refreshed tokens
            log("SESSION restored and connected")
            return true
        } catch (e: Exception) {
            log("SESSION restore connect failed: ${e.message}")
            return false
        }
    }

    // ─── Facade Methods (bridges call these 1:1) ────────────

    /**
     * Parse ORM schema from JSON (matches schema.ts format) and define models.
     * JSON shape:
     *   {"modelName": {"fields": {"name": "string"}, "sync": "gset", "ttl": "24h",
     *                  "audience": {"kind": "conversation", "field": "conversationId"}}}
     * `audience` is optional (defaults to broadcast-to-friends). Supported kinds:
     * "friends", "self", "recipient" (+field), "conversation" (+field).
     */
    suspend fun defineModelsFromJson(jsonString: String) {
        val schema = JSONObject(jsonString)
        val models = mutableMapOf<String, ModelConfig>()
        for (name in schema.keys()) {
            models[name] = com.obscura.kit.orm.ModelConfig.fromWire(schema.getJSONObject(name))
        }
        orm.define(models)
        // Cache schema for cold-start restore
        val existing = sessionStorage.load()?.toMutableMap() ?: mutableMapOf()
        existing["cachedSchema"] = jsonString
        sessionStorage.save(existing)
        log("MODELS defined + cached: ${models.keys.joinToString()}")
    }

    /**
     * Decode a friend code (base64 JSON) and befriend the user.
     * Code format: Base64({"n":"username","u":"userId"}) — matches iOS FriendCode.swift
     */
    suspend fun addFriendByCode(code: String) {
        val cleaned = code.trim()
            .replace("\u00AD", "") // strip soft hyphens from iOS copy
            .replace("\\s".toRegex(), "")
        val decoded = String(Base64.getDecoder().decode(cleaned))
        val json = JSONObject(decoded)
        val friendUserId = json.getString("u")
        val friendUsername = json.getString("n")
        log("ADD_FRIEND_BY_CODE $friendUsername ($friendUserId)")
        befriend(friendUserId, friendUsername)
    }

    /**
     * Generate a friend code for sharing. Returns base64-encoded JSON.
     */
    fun friendCode(): String {
        val uid = userId ?: throw com.obscura.kit.ObscuraError.NotAuthenticated()
        val uname = username ?: throw com.obscura.kit.ObscuraError.NotAuthenticated()
        val json = JSONObject().apply { put("n", uname); put("u", uid) }
        return Base64.getEncoder().encodeToString(json.toString().toByteArray())
    }

    /**
     * Full logout — tears down the connection, clears the session, AND wipes all
     * local data (friends, messages, ORM, Signal state). Bridges call this single
     * method instead of orchestrating cleanup.
     *
     * Use [logout] instead to keep local data and only clear the session token.
     */
    suspend fun fullLogout() {
        log("FULL_LOGOUT start")
        envelopeJob?.cancel()
        eventForwardingJob?.cancel()
        authManager.tokenRefreshJob?.cancel()
        gateway.disconnect() // fires onStateChanged → _connectionState = DISCONNECTED
        try { authManager.logout() } catch (e: Exception) { log("logout during fullLogout failed: ${e.message}") }
        _authState.value = AuthState.LOGGED_OUT
        _friendList.value = emptyList()
        _pendingRequests.value = emptyList()
        _conversations.value = emptyMap()
        // Wipe all local data so a fresh login starts clean.
        //
        // One transaction, deliberately: a crash partway through a sequence of bare deletes
        // leaves a *coherent-looking* but half-wiped database — e.g. friends gone while this
        // device's Signal sessions and identity keys survive. That state is worse than either
        // extreme, because nothing downstream can detect it. All-or-nothing instead.
        db.transaction {
            db.friendQueries.deleteAll()
            db.messageQueries.deleteAll()
            db.deviceQueries.deleteAllDevices()
            db.deviceQueries.deleteIdentity()
            db.signalKeyQueries.deleteLocalIdentity()
            db.signalKeyQueries.deleteAllSignalData()
            db.signalKeyQueries.deleteAllPreKeys()
            db.signalKeyQueries.deleteAllSignedPreKeys()
            db.signalKeyQueries.deleteAllSessions()
            db.signalKeyQueries.deleteAllSenderKeys()
            db.modelEntryQueries.deleteAllEntries()
            db.modelEntryQueries.deleteAllAssociations()
            db.attachmentCacheQueries.deleteAll()
        }
        sessionStorage.clear()
        log("FULL_LOGOUT complete")
    }

    /** Start forwarding StateFlows → typedEvents stream for bridge consumption */
    private var eventForwardingJob: Job? = null
    private fun startEventForwarding() {
        eventForwardingJob?.cancel()
        eventForwardingJob = scope.launch {
            // Friends
            launch {
                friendList.collect { friends ->
                    _typedEvents.emit(ObscuraEvent.FriendsUpdated(friends))
                }
            }
            // Connection state
            launch {
                connectionState.collect { state ->
                    _typedEvents.emit(ObscuraEvent.ConnectionChanged(state))
                }
            }
            // Auth state
            launch {
                authState.collect { state ->
                    _typedEvents.emit(ObscuraEvent.AuthChanged(state))
                }
            }
        }
    }

    // ─── Connect / Disconnect ───────────────────────────────

    // Serializes connect() so a foreground ensureConnected() and a bridge connect()
    // (or overlapping lifecycle events) can't run the connect body concurrently.
    private val connectMutex = Mutex()

    suspend fun connect() = connectMutex.withLock {
        checkNotClosed()
        if (_connectionState.value == ConnectionState.CONNECTED) return@withLock
        log("CONNECT start")
        try {
            ensureFreshToken()
            messenger.rebuildDeviceMap(friends.getAccepted())
            // gateway.connect() drives _connectionState via onStateChanged (the sole
            // writer): CONNECTING now, then CONNECTED on open — set synchronously
            // before this suspends-return — or DISCONNECTED if the open fails (throws).
            gateway.connect()
        } catch (e: Exception) {
            log("CONNECT failed — ${e.message}")
            throw e
        }
        log("CONNECT ok — websocket open")
        startEnvelopeLoop()
        startEventForwarding()
        authManager.startTokenRefresh()
        startPreKeyStatusListener()
        startTTLCleanup()
        persistSession() // auto-save refreshed tokens
    }

    /**
     * Idempotent reconnect entrypoint for app lifecycle events (e.g. foreground
     * resume). Reconnects only when authenticated and fully disconnected, so the
     * app can call it unconditionally on resume: it no-ops while a connect or the
     * gateway's own auto-reconnect (CONNECTING) is already in flight, and only
     * kicks off a fresh connect when the socket is genuinely down.
     */
    suspend fun ensureConnected() {
        if (authState.value != AuthState.AUTHENTICATED) return
        if (connectionState.value != ConnectionState.DISCONNECTED) return
        connect()
    }

    fun disconnect() {
        log("DISCONNECT")
        authManager.tokenRefreshJob?.cancel()
        envelopeJob?.cancel()
        eventForwardingJob?.cancel()
        ttlCleanupJob?.cancel()
        gateway.disconnect() // fires onStateChanged → _connectionState = DISCONNECTED
    }

    /**
     * Fully tear down this instance. Idempotent — safe to call multiple times.
     *
     * Cancels all coroutines, closes the gateway WebSocket and its OkHttp client,
     * closes the [incomingMessages] channel, and closes the database driver if
     * this instance owns it (i.e. no external driver was passed to the constructor).
     *
     * After [close] returns, the client is inert and must not be reused.
     */
    override fun close() {
        if (isClosed) return
        isClosed = true
        log("CLOSE")
        envelopeJob?.cancel()
        preKeyStatusJob?.cancel()
        eventForwardingJob?.cancel()
        authManager.tokenRefreshJob?.cancel()
        ttlCleanupJob?.cancel()
        gateway.close()           // closes OkHttp client + channels
        signalManager.close()     // cancels signal timer scope
        scope.cancel()
        incomingMessages.close()
        if (driverIsOwned) {
            try { driver.close() } catch (_: Exception) {}
        }
    }

    // ─── Push Notifications ─────────────────────────────────

    /**
     * Register FCM/APNS push token with server. Requires device-scoped JWT.
     * Safe to call multiple times — server upserts by deviceId.
     */
    suspend fun registerPushToken(token: String) {
        api.registerPushToken(token)
    }

    /**
     * Drain queued envelopes after a silent push wake. Connects if needed, waits up to
     * [timeoutMs] ms (returning early when the queue stays empty for 500ms), categorises
     * all MODEL_SYNC envelopes by their model name, and returns the counts. Does NOT
     * disconnect afterwards — the OS will freeze the app when done.
     *
     * The bridge layer uses the returned [ProcessedCounts.modelCounts] to post generic
     * local notifications (e.g. `modelCounts["pix"]` → "N new items"). Kit must NEVER
     * post OS notifications itself, and it must NEVER sniff model names internally —
     * the application declares which models it cares about via [ObscuraConfig.conversationModel].
     */
    suspend fun processPendingMessages(timeoutMs: Long): ProcessedCounts {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            try { connect() } catch (_: Exception) { return ProcessedCounts() }
        }

        val modelTotals = mutableMapOf<String, Int>()
        var other = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        val idleThresholdMs = 500L
        var lastEnvelopeAt = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            val received = incomingMessages.tryReceive().getOrNull()
            if (received != null) {
                val (modelName, isModelSync) = classifyForPushCounts(received)
                if (isModelSync && modelName != null) {
                    modelTotals[modelName] = (modelTotals[modelName] ?: 0) + 1
                } else {
                    other++
                }
                lastEnvelopeAt = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastEnvelopeAt > idleThresholdMs) {
                break
            } else {
                delay(50)
            }
        }

        return ProcessedCounts(modelCounts = modelTotals, otherCount = other)
    }

    /**
     * Classify a single envelope: returns (modelName, isModelSync).
     * Only MODEL_SYNC envelopes contribute to named counts; all other types
     * go to [ProcessedCounts.otherCount]. No app-model names are embedded here.
     */
    private fun classifyForPushCounts(msg: ReceivedMessage): Pair<String?, Boolean> {
        if (msg.type == "MODEL_SYNC" && msg.raw != null) {
            val modelName = msg.raw.modelSync.model
            return Pair(modelName.ifBlank { null }, true)
        }
        return Pair(null, false)
    }

    private var preKeyStatusJob: Job? = null
    private var ttlCleanupJob: Job? = null

    private fun startPreKeyStatusListener() {
        preKeyStatusJob?.cancel()
        preKeyStatusJob = scope.launch {
            for (status in gateway.preKeyStatus) {
                if (status.oneTimePreKeyCount < status.minThreshold) {
                    replenishPreKeys()
                }
            }
        }
    }

    /**
     * Background job that purges expired ORM entries once per minute.
     * TTL expiry is best-effort — entries are soft-deleted (marked deleted) so
     * CRDT merge remains consistent. Restarted on each [connect] call.
     */
    private fun startTTLCleanup() {
        ttlCleanupJob?.cancel()
        ttlCleanupJob = scope.launch {
            while (isActive) {
                delay(60_000)
                try {
                    ttlManager.cleanup { orm.modelOrNull(it) }
                } catch (e: Exception) {
                    log("TTL cleanup error: ${e.message}")
                }
            }
        }
    }

    private fun checkAndReplenishPreKeys() {
        scope.launch {
            try {
                if (signalStore.getPreKeyCount() < PREKEY_MIN_COUNT) {
                    replenishPreKeys()
                }
            } catch (e: Exception) { /* non-fatal */ }
        }
    }

    private suspend fun replenishPreKeys() {
        try {
            val highestId = signalStore.getHighestPreKeyId().toInt()
            val newPreKeys = SignalKeyUtils.generateOneTimePreKeys(signalStore, highestId + 1, PREKEY_REPLENISH_COUNT)

            val spkRecord = signalStore.loadSignedPreKey(1)

            api.uploadDeviceKeys(UploadDeviceKeysRequest(
                identityKey = signalStore.getIdentityKeyPair().publicKey.serialize().toBase64(),
                registrationId = signalStore.getLocalRegistrationId(),
                signedPreKey = spkRecord.toApiJson(),
                oneTimePreKeys = newPreKeys.toApiJson()
            ))
        } catch (e: Exception) {
            logger.preKeyReplenishFailed(e.message ?: "unknown")
        }
    }

    private fun startEnvelopeLoop() {
        envelopeJob?.cancel()
        envelopeJob = scope.launch {
            for (envelope in gateway.envelopes) {
                val senderId = try {
                    val bb = java.nio.ByteBuffer.wrap(envelope.senderId.toByteArray())
                    java.util.UUID(bb.getLong(), bb.getLong()).toString()
                } catch (_: Exception) { "unknown" }

                if (isDecryptRateLimited(senderId)) {
                    log("RECV BLOCKED rate-limited sender=$senderId")
                    try { gateway.ack(listOf(envelope.id)) } catch (e: Exception) { log("envelope ack failed: ${e.message}") }
                    continue
                }

                try {
                    val decrypted = messenger.decrypt(envelope)
                    val msg = decrypted.clientMessage
                    log("RECV ${msg.payloadCase.name} from=${decrypted.sourceUserId.take(8)} text=${msg.text.text.take(40)}")
                    routeMessage(msg, decrypted.sourceUserId, decrypted.senderDeviceId)

                    decryptFailures.remove(decrypted.sourceUserId)

                    val username = when (msg.payloadCase) {
                        ClientMessage.PayloadCase.FRIEND_REQUEST -> msg.friendRequest.username
                        ClientMessage.PayloadCase.FRIEND_RESPONSE -> msg.friendResponse.username
                        else -> ""
                    }
                    val received = ReceivedMessage(
                        type = com.obscura.kit.orm.WireCodec.decodeType(msg.payloadCase),
                        text = msg.text.text,
                        username = username,
                        accepted = msg.payloadCase == ClientMessage.PayloadCase.FRIEND_RESPONSE && msg.friendResponse.accepted,
                        sourceUserId = decrypted.sourceUserId,
                        senderDeviceId = decrypted.senderDeviceId,
                        raw = msg
                    )

                    incomingMessages.trySend(received).also { result ->
                        if (result.isFailure) {
                            log("RECV CHANNEL FULL — dropping ${received.type} from=${received.sourceUserId.take(8)}")
                        }
                    }
                    _events.tryEmit(received)

                    checkAndReplenishPreKeys()
                } catch (e: Exception) {
                    log("RECV FAIL decrypt sender=$senderId err=${e.message?.take(60)}")
                    trackDecryptFailure(senderId)
                    logger.decryptFailed(senderId, e.message ?: "unknown")
                }

                try { gateway.ack(listOf(envelope.id)) } catch (e: Exception) { log("envelope ack failed: ${e.message}") }
            }
        }
    }

    private fun isDecryptRateLimited(senderId: String): Boolean {
        val (count, windowStart) = decryptFailures[senderId] ?: return false
        val now = System.currentTimeMillis()
        if (now - windowStart > DECRYPT_FAILURE_WINDOW_MS) {
            decryptFailures.remove(senderId)
            return false
        }
        return count >= MAX_DECRYPT_FAILURES
    }

    private fun trackDecryptFailure(senderId: String) {
        val now = System.currentTimeMillis()
        val (count, windowStart) = decryptFailures[senderId] ?: Pair(0, now)
        if (now - windowStart > DECRYPT_FAILURE_WINDOW_MS) {
            decryptFailures[senderId] = Pair(1, now)
        } else {
            decryptFailures[senderId] = Pair(count + 1, windowStart)
        }
    }

    private suspend fun routeMessage(msg: ClientMessage, sourceUserId: String, senderDeviceId: String?) {
        when (msg.payloadCase) {
            ClientMessage.PayloadCase.FRIEND_REQUEST -> handleFriendRequest(msg, sourceUserId)
            ClientMessage.PayloadCase.FRIEND_RESPONSE -> handleFriendResponse(msg, sourceUserId)
            ClientMessage.PayloadCase.TEXT -> handleTextMessage(msg, sourceUserId, senderDeviceId)
            ClientMessage.PayloadCase.DEVICE_ANNOUNCE -> handleDeviceAnnounce(msg, sourceUserId)
            ClientMessage.PayloadCase.MODEL_SYNC -> handleModelSync(msg, sourceUserId)
            ClientMessage.PayloadCase.SYNC_BLOB -> handleSyncBlob(msg, sourceUserId)
            ClientMessage.PayloadCase.SENT_SYNC -> handleSentSync(msg)
            ClientMessage.PayloadCase.SESSION_RESET -> signalStore.deleteAllSessions(sourceUserId)
            ClientMessage.PayloadCase.FRIEND_SYNC -> handleFriendSync(msg, sourceUserId)
            ClientMessage.PayloadCase.DEVICE_LINK_APPROVAL -> handleLinkApproval(msg, sourceUserId)
            ClientMessage.PayloadCase.MODEL_SIGNAL -> handleModelSignal(msg, sourceUserId, senderDeviceId)
            else -> { }
        }
    }

    private suspend fun handleFriendRequest(msg: ClientMessage, sourceUserId: String) {
        friends.add(sourceUserId, msg.friendRequest.username, FriendStatus.PENDING_RECEIVED)
    }

    private suspend fun handleFriendResponse(msg: ClientMessage, sourceUserId: String) {
        if (msg.friendResponse.accepted) friends.add(sourceUserId, msg.friendResponse.username, FriendStatus.ACCEPTED)
    }

    private suspend fun handleTextMessage(msg: ClientMessage, sourceUserId: String, senderDeviceId: String?) {
        val msgId = UUID.randomUUID().toString()
        val msgData = MessageData(
            id = msgId, conversationId = sourceUserId,
            authorDeviceId = senderDeviceId ?: "unknown",
            content = msg.text.text, timestamp = msg.timestamp,
            type = com.obscura.kit.orm.WireCodec.decodeType(msg.payloadCase).lowercase()
        )
        messagesDomain.add(sourceUserId, msgData)
        refreshConversation(sourceUserId)
    }

    private suspend fun handleDeviceAnnounce(msg: ClientMessage, sourceUserId: String) {
        val announce = msg.deviceAnnounce
        if (announce.signature.size() > 0 && announce.recoveryPublicKey.size() > 0) {
            val payload = com.obscura.kit.crypto.RecoveryKeys.serializeAnnounceForSigning(
                announce.devicesList.map { it.deviceId }, announce.timestamp, announce.isRevocation
            )
            try {
                val pubKey = Curve.decodePoint(announce.recoveryPublicKey.toByteArray(), 0)
                if (!Curve.verifySignature(pubKey, payload, announce.signature.toByteArray())) {
                    logger.decryptFailed(sourceUserId, "device announce signature invalid")
                    return
                }
            } catch (e: Exception) {
                logger.decryptFailed(sourceUserId, "device announce signature verify error: ${e.message}")
                return
            }
        }

        // M4: Replay protection — reject announces whose timestamp is not strictly newer than the
        // last accepted announce from this peer. Strict inequality (≤) also drops retransmits of
        // the exact same announce, which is fine: the device list is idempotent.
        //
        // The high-water mark alone is NOT sufficient. It is monotonic and peer-supplied, so a
        // single announce bearing a far-future timestamp would raise the bar above every
        // legitimate announce that follows — permanently wedging this peer's device list with no
        // recovery path (they could never rotate or revoke a device again). An unbounded future
        // timestamp is a permanent weapon, not a one-off. So clamp first, exactly as the CRDT
        // merge path does for the same reason (SPEC §2.4), then compare.
        val maxAcceptable = System.currentTimeMillis() + com.obscura.kit.orm.MonotonicClock.CLOCK_SKEW_TOLERANCE_MS
        if (announce.timestamp > maxAcceptable) {
            logger.decryptFailed(sourceUserId,
                "device announce rejected: timestamp ${announce.timestamp} is more than " +
                "${com.obscura.kit.orm.MonotonicClock.CLOCK_SKEW_TOLERANCE_MS}ms in the future " +
                "(now=${System.currentTimeMillis()})")
            return
        }

        val lastAnnounceAt = friends.getLastAnnounceAt(sourceUserId)
        // lastAnnounceAt == 0 means no announce has been accepted for this peer yet
        // (fresh friend or database migration); skip the replay check in that case.
        val hasSeenPreviousAnnounce = lastAnnounceAt > 0
        if (hasSeenPreviousAnnounce && announce.timestamp <= lastAnnounceAt) {
            logger.decryptFailed(sourceUserId,
                "device announce replay rejected: ts=${announce.timestamp} lastSeen=$lastAnnounceAt")
            return
        }

        val deviceList = announce.devicesList.map { d ->
            FriendDeviceInfo(d.deviceUuid, d.deviceId, d.deviceName)
        }
        // Update devices and stamp the new announce time atomically within the
        // FriendDomain's confined dispatcher so no other operation can interleave.
        friends.updateDevicesAndAnnounceTime(sourceUserId, deviceList, announce.timestamp)
    }

    private suspend fun handleModelSignal(msg: ClientMessage, sourceUserId: String, senderDeviceId: String?) {
        try {
            val sig = msg.modelSignal
            if (sig.model.isBlank()) return

            val signalName = com.obscura.kit.orm.WireCodec.decodeSignalKind(sig.kind)
                ?: return // unknown/unspecified — ignore

            // Identity comes from the authenticated envelope, never the payload.
            // The contextKey is the server-relayed contextId from the signal wire frame.
            val authorDeviceId = senderDeviceId ?: sourceUserId

            if (signalName == "stoppedTyping") {
                signalManager.clear(sig.model, "typing", sig.contextId, authorDeviceId)
            } else {
                signalManager.receive(sig.model, signalName, sig.contextId, authorDeviceId)
            }
        } catch (e: Exception) {
            // Never let signal handling crash the envelope loop.
            log("model signal handling failed: ${e.message}")
        }
    }

    private suspend fun handleModelSync(msg: ClientMessage, sourceUserId: String) {
        val sync = msg.modelSync
        val syncData = ModelSyncData(
            model = sync.model, id = sync.id, op = com.obscura.kit.orm.WireCodec.decodeOp(sync.op),
            timestamp = sync.timestamp, data = sync.data.toByteArray(),
            authorDeviceId = sync.authorDeviceId
        )
        orm.handleSync(syncData, sourceUserId)

        // Emit typed event for bridges
        try {
            val dataMap = mutableMapOf<String, Any?>()
            val dataJson = org.json.JSONObject(String(sync.data.toByteArray()))
            for (key in dataJson.keys()) dataMap[key] = if (dataJson.isNull(key)) null else dataJson.get(key)
            val entry = com.obscura.kit.orm.OrmEntry(
                id = sync.id, data = dataMap,
                timestamp = sync.timestamp, authorDeviceId = sync.authorDeviceId
            )
            _typedEvents.tryEmit(ObscuraEvent.MessageReceived(sync.model, entry))
        } catch (e: Exception) { log("typed-event emit for '${sync.model}' failed: ${e.message}") }

        // If a conversation model is declared in config, mirror incoming entries into
        // the conversations StateFlow so chat UIs get live updates without field-name
        // sniffing inside the kit. The application opts into this by setting
        // ObscuraConfig.conversationModel; the kit never inspects model names itself.
        val convModelName = config.conversationModel
        if (convModelName != null && sync.model == convModelName) {
            try {
                val json = org.json.JSONObject(sync.data.toStringUtf8())
                val content = json.optString("content", "")
                val msgData = MessageData(
                    id = sync.id, conversationId = sourceUserId,
                    authorDeviceId = sync.authorDeviceId,
                    content = content, timestamp = sync.timestamp,
                    type = "text"
                )
                messagesDomain.add(sourceUserId, msgData)
                refreshConversation(sourceUserId)
            } catch (e: Exception) { log("conversation routing for '${sync.model}' failed: ${e.message}") }
        }
    }

    private suspend fun handleSyncBlob(msg: ClientMessage, sourceUserId: String) {
        if (sourceUserId != userId) return
        clientSyncManager.processSyncBlob(msg)
    }

    private suspend fun handleSentSync(msg: ClientMessage) {
        val ss = msg.sentSync
        messagesDomain.add(ss.recipientUsername, MessageData(
            id = ss.messageId, conversationId = ss.recipientUsername,
            authorDeviceId = deviceId ?: "self", content = String(ss.content.toByteArray()),
            timestamp = ss.timestamp, type = "text"
        ))
        refreshConversation(ss.recipientUsername)
    }

    private suspend fun handleFriendSync(msg: ClientMessage, sourceUserId: String) {
        if (sourceUserId != userId) return
        val fs = msg.friendSync
        val status = if (fs.status == FriendStatus.ACCEPTED.value) FriendStatus.ACCEPTED else FriendStatus.PENDING_RECEIVED
        if (fs.action == FriendSyncAction.ADD.value) {
            friends.add(sourceUserId, fs.username, status,
                fs.devicesList.map { FriendDeviceInfo(it.deviceUuid, it.deviceId, it.deviceName) })
        } else if (fs.action == FriendSyncAction.REMOVE.value) {
            friends.remove(sourceUserId)
        }
    }

    private suspend fun handleLinkApproval(msg: ClientMessage, sourceUserId: String) {
        // Only accept approval from our own account
        if (sourceUserId != userId) return
        // Only process if we're actually waiting for approval
        if (_authState.value != AuthState.PENDING_APPROVAL) return

        val approval = msg.deviceLinkApproval

        // Verify challenge matches the one we generated in our link code
        val pendingChallenge = session.pendingLinkChallenge
        if (pendingChallenge != null && approval.challengeResponse.size() > 0) {
            val received = approval.challengeResponse.toByteArray()
            if (!com.obscura.kit.crypto.LinkCode.verifyChallenge(pendingChallenge, received)) {
                logger.decryptFailed(sourceUserId, "Link approval challenge mismatch")
                return
            }
        }

        // Import device list from approval
        val approvedDevices = approval.ownDevicesList.map { d ->
            FriendDeviceInfo(d.deviceUuid, d.deviceId, d.deviceName)
        }
        if (approvedDevices.isNotEmpty()) {
            devices.setOwnDevices(approvedDevices)
        }

        // Store identity keys from approval
        val identity = devices.getIdentity()
        if (identity != null) {
            devices.storeIdentity(identity.copy(
                p2pPublicKey = approval.p2PPublicKey?.toByteArray()?.takeIf { it.isNotEmpty() },
                recoveryPublicKey = approval.recoveryPublicKey?.toByteArray()?.takeIf { it.isNotEmpty() }
            ))
        }

        // Import friend data from approval
        if (approval.friendsExport.size() > 0) {
            try {
                friends.importAll(String(approval.friendsExport.toByteArray()))
            } catch (e: Exception) { log("friend import from link approval failed: ${e.message}") }
        }

        session.pendingLinkChallenge = null
        _authState.value = AuthState.AUTHENTICATED
    }

    private suspend fun refreshConversation(conversationId: String) {
        val msgs = messagesDomain.getMessages(conversationId)
        val current = _conversations.value.toMutableMap()
        current[conversationId] = msgs
        _conversations.value = current
    }

    suspend fun getMessages(conversationId: String, limit: Int = 50): List<MessageData> {
        val msgs = messagesDomain.getMessages(conversationId, limit)
        val current = _conversations.value.toMutableMap()
        current[conversationId] = msgs
        _conversations.value = current
        return msgs
    }

    /**
     * Send a text message to a friend over the ORM (MODEL_SYNC), using the model named by
     * [ObscuraConfig.conversationModel]. That model must already be defined via
     * [defineModelsFromJson] or [com.obscura.kit.stores.SchemaDomain.define].
     *
     * Besides `content`, the kit populates exactly one more field: the routing field the
     * model's own [Audience] *names*. For an [Audience.Conversation] that is the canonical
     * `"userIdA_userIdB"` id; for an [Audience.Recipient] it is the friend's username. The
     * field *name* always comes from the schema config, never from a literal in the kit, so
     * this does not reintroduce the application-field-name hardcoding that SPEC §1 forbids.
     *
     * That is not optional: without the routing field, [com.obscura.kit.orm.SyncManager]
     * would (correctly) refuse the write with `DIRECT_ROUTING_UNRESOLVED` rather than risk
     * broadcasting a 1:1 payload. A `friends`/`self` audience needs no routing field.
     *
     * Any *other* field the app's schema declares is the app's own business — call
     * `orm.model(name).create(fields)` directly when you need full control.
     *
     * There is deliberately **no legacy-TEXT fallback**. Silently downgrading to a TEXT
     * envelope when the config is missing would produce a message the recipient never gets
     * notified about: per SPEC §6, only MODEL_SYNC contributes to push counts, so a TEXT
     * envelope lands in `otherCount`, which bridges ignore. A loud failure here beats a
     * message that appears sent and arrives silently.
     *
     * @throws IllegalStateException if [ObscuraConfig.conversationModel] is unset, or names a
     *   model that has not been defined.
     */
    suspend fun send(friendUsername: String, text: String) {
        log("SEND to=$friendUsername text=${text.take(40)}")
        val convModelName = config.conversationModel
            ?: throw IllegalStateException(
                "send() requires ObscuraConfig.conversationModel to name the ORM model that " +
                "carries conversations. Set it, or drive the ORM directly with " +
                "orm.model(name).create(fields).")
        val convModel = orm.modelOrNull(convModelName)
            ?: throw IllegalStateException(
                "ObscuraConfig.conversationModel is '$convModelName' but no such model is " +
                "defined. Define it via defineModelsFromJson() before calling send().")

        val friendData = friends.getAccepted().find { it.username == friendUsername }
            ?: throw com.obscura.kit.ObscuraError.NotFriends(friendUsername)
        val selfUserId = userId
            ?: throw IllegalStateException("send() requires an authenticated session (userId is null)")

        // Populate the routing field the model's audience declares — by config-supplied
        // name, never a hardcoded one. See the KDoc above.
        val fields = mutableMapOf<String, Any?>("content" to text)
        when (val audience = convModel.config.audience) {
            is com.obscura.kit.orm.Audience.Conversation ->
                fields[audience.conversationField] =
                    com.obscura.kit.orm.Audience.canonicalConversationId(selfUserId, friendData.userId)
            is com.obscura.kit.orm.Audience.Recipient ->
                fields[audience.usernameField] = friendUsername
            is com.obscura.kit.orm.Audience.Friends,
            is com.obscura.kit.orm.Audience.Self -> { /* no routing field required */ }
        }

        // Create via ORM — auto-syncs to friend via MODEL_SYNC.
        val entry = convModel.create(fields)
        // Mirror locally into conversations StateFlow (keyed by friend userId)
        messagesDomain.add(friendData.userId, MessageData(
            id = entry.id, conversationId = friendData.userId,
            authorDeviceId = deviceId ?: "self",
            content = text, timestamp = entry.timestamp, type = "text"
        ))
        refreshConversation(friendData.userId)
    }

    /**
     * Send a legacy TEXT (type 0) envelope, bypassing the ORM entirely.
     *
     * Prefer [send]. This exists for two reasons: interop with peers that only speak the legacy
     * TEXT message type, and as a deliberate *non-ORM* control — a TEXT arriving proves the
     * channel works without itself producing a MODEL_SYNC, which is what lets a test assert that
     * no ORM entry leaked.
     *
     * It is exposed explicitly rather than reached by [send] silently falling back, because a
     * caller who *meant* to send a conversation message should not get a TEXT envelope by
     * accident: per SPEC §6, TEXT contributes nothing to push counts, so a backgrounded recipient
     * is never notified. Choosing TEXT must be deliberate.
     */
    suspend fun sendText(friendUsername: String, text: String) =
        messagingManager.send(friendUsername, text)

    suspend fun sendAttachment(friendUsername: String, attachmentId: String, contentKey: ByteArray, nonce: ByteArray, mimeType: String, sizeBytes: Long) =
        messagingManager.sendAttachment(friendUsername, attachmentId, contentKey, nonce, mimeType, sizeBytes)
    suspend fun sendEncryptedAttachment(friendUsername: String, plaintext: ByteArray, mimeType: String = "application/octet-stream") =
        messagingManager.sendEncryptedAttachment(friendUsername, plaintext, mimeType)
    suspend fun sendModelSync(friendUsername: String, model: String, entryId: String, op: String = "CREATE", data: Map<String, Any?>) =
        messagingManager.sendModelSync(friendUsername, model, entryId, op, data)
    suspend fun sendRaw(targetUserId: String, msg: ClientMessage) = messagingManager.sendRaw(targetUserId, msg)
    suspend fun uploadAttachment(data: ByteArray): Pair<String, Long> = messagingManager.uploadAttachment(data)
    suspend fun downloadAttachment(id: String): ByteArray = messagingManager.downloadAttachment(id)
    suspend fun downloadDecryptedAttachment(id: String, contentKey: ByteArray, nonce: ByteArray, expectedHash: ByteArray? = null): ByteArray =
        messagingManager.downloadDecryptedAttachment(id, contentKey, nonce, expectedHash)

    suspend fun befriend(targetUserId: String, targetUsername: String) = friendshipManager.befriend(targetUserId, targetUsername)
    suspend fun acceptFriend(targetUserId: String, targetUsername: String) = friendshipManager.acceptFriend(targetUserId, targetUsername)

    suspend fun announceDevices() = deviceManager.announceDevices()
    suspend fun announceDeviceRevocation(friendUsername: String, remainingDeviceIds: List<String>) =
        deviceManager.announceDeviceRevocation(friendUsername, remainingDeviceIds)
    suspend fun revokeDevice(recoveryPhrase: String, targetDeviceId: String) {
        requireRecoveryEnabled("revokeDevice")
        deviceManager.revokeDevice(recoveryPhrase, targetDeviceId)
    }
    /**
     * Generate a link code for this device. Display as QR code or copyable text.
     * The existing device scans this and calls validateAndApproveLink().
     */
    fun generateLinkCode(): String {
        val did = deviceId ?: throw com.obscura.kit.ObscuraError.NotProvisioned("Not provisioned — call loginAndProvision first")
        val identityKey = signalStore.getIdentityKeyPair().publicKey.serialize()
        val generated = com.obscura.kit.crypto.LinkCode.generate(did, did, identityKey)
        session.pendingLinkChallenge = generated.challenge
        return generated.code
    }

    /**
     * Validate a link code and approve the new device. Called by the EXISTING device
     * after scanning QR or receiving pasted code from the new device.
     */
    suspend fun validateAndApproveLink(linkCode: String) {
        val result = com.obscura.kit.crypto.LinkCode.validate(linkCode)
        require(result.valid) { result.error ?: "Invalid link code" }
        val data = result.data!!
        deviceManager.approveLink(data.deviceId, data.challenge)
    }

    /**
     * Low-level approve — use validateAndApproveLink() instead for the full flow.
     */
    suspend fun approveLink(newDeviceId: String, challengeResponse: ByteArray) =
        deviceManager.approveLink(newDeviceId, challengeResponse)
    suspend fun takeoverDevice() = deviceManager.takeoverDevice()

    fun generateRecoveryPhrase(): String {
        requireRecoveryEnabled("generateRecoveryPhrase")
        return recoveryManager.generateRecoveryPhrase()
    }
    fun getRecoveryPhrase(): String? {
        requireRecoveryEnabled("getRecoveryPhrase")
        return recoveryManager.getRecoveryPhrase()
    }
    fun getVerifyCode(): String? = recoveryManager.getVerifyCode()
    suspend fun announceRecovery(recoveryPhrase: String, isFullRecovery: Boolean = true) {
        requireRecoveryEnabled("announceRecovery")
        recoveryManager.announceRecovery(recoveryPhrase, isFullRecovery)
    }
    suspend fun uploadBackup(): String? = recoveryManager.uploadBackup()
    suspend fun downloadBackup(recoveryPhrase: String? = null): ParsedSyncBlob? = recoveryManager.downloadBackup(recoveryPhrase)
    suspend fun checkBackup(): Triple<Boolean, String?, Long?> = recoveryManager.checkBackup()

    private fun requireRecoveryEnabled(method: String) {
        require(config.enableRecoveryPhrase) {
            "$method requires ObscuraConfig(enableRecoveryPhrase = true)"
        }
    }

    suspend fun resetSessionWith(targetUserId: String, reason: String = "manual") =
        clientSyncManager.resetSessionWith(targetUserId, reason)
    suspend fun resetAllSessions(reason: String = "manual") = clientSyncManager.resetAllSessions(reason)
    suspend fun requestSync() = clientSyncManager.requestSync()
    suspend fun pushHistoryToDevice(targetDeviceId: String) = clientSyncManager.pushHistoryToDevice(targetDeviceId)
}
