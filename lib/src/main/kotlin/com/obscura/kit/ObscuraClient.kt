package com.obscura.kit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.crypto.ParsedSyncBlob
import com.obscura.kit.crypto.SignalStore
import com.obscura.kit.crypto.UuidCodec
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
 * Result of [ObscuraClient.processPendingMessages] — counts of envelopes drained, by ORM model.
 *
 * The bridge uses these to pick generic notification text ("New pix" / "New message").
 * [otherCount] is debug-only; the bridge ignores it. Shape matches Swift's ProcessedCounts
 * so both platforms implement identical notification logic.
 */
data class ProcessedCounts(
    val pixCount: Int = 0,
    val messageCount: Int = 0,
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
 */
class ObscuraClient(
    val config: ObscuraConfig,
    externalDriver: app.cash.sqldelight.db.SqlDriver? = null,
    val sessionStorage: SessionStorage = NoOpSessionStorage
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    private val driver = externalDriver ?: if (config.databasePath != null) {
        JdbcSqliteDriver("jdbc:sqlite:${config.databasePath}")
    } else {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    }
    internal val db: ObscuraDatabase

    internal val signalStore: SignalStore
    internal val api = APIClient(config.apiUrl)
    internal val gateway: GatewayConnection

    private val friends: FriendDomain
    private val messagesDomain: MessageDomain
    internal val devices: DeviceDomain
    internal val messenger: MessengerDomain
    val orm: SchemaDomain

    /**
     * The durable inbox (`obscura-proto/KIT_API.md` §3) — the thin kit's receive API.
     *
     * **Runs ALONGSIDE `orm` on purpose, and that is a migration step, not a design.** `KIT_API.md`
     * §10 orders the reset so the deletion comes last: both kits gain `inbox` before obscura-pix
     * switches to it, and only then is the old surface removed. Deleting first would break pix on
     * both platforms — the kits are consumed from source with no published-version buffer — for the
     * whole duration of the port, with no way to tell a real regression from expected breakage.
     *
     * The cost of that ordering is that a MODEL_SYNC is currently persisted **twice**: once as a
     * model entry by the ORM, and once as an inbox row here. That is deliberate and temporary. It
     * ends at §10 step 4, when the ORM comes out.
     */
    val inbox: InboxDomain

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

    // F10: backoff between the two connect attempts in processPendingMessages. Short on purpose —
    // the push path runs inside a tight OS budget (iOS gives an NSE ~30s), so a retry that costs
    // seconds is worse than no retry at all.
    private val PUSH_DRAIN_RECONNECT_RETRY_MS = 250L

    // Prekey replenishment
    private val PREKEY_MIN_COUNT = 20L
    private val PREKEY_REPLENISH_COUNT = 50

    init {
        if (externalDriver == null) {
            ObscuraDatabase.Schema.create(driver)
            try { driver.execute(null, "PRAGMA secure_delete = ON", 0) } catch (e: Exception) { log("PRAGMA secure_delete failed: ${e.message}") }
        }
        db = ObscuraDatabase(driver)

        signalStore = SignalStore(db)
        signalStore.onIdentityChanged = { address, _, _ ->
            logger.identityChanged(address)
        }
        friends = FriendDomain(db)
        messagesDomain = MessageDomain(db)
        devices = DeviceDomain(db)
        messenger = MessengerDomain(signalStore, api)
        inbox = InboxDomain(db)
        // A discard is data loss the app chose deliberately, and §3.3 rule 5 requires it be logged
        // as a security-relevant event rather than being the quiet path.
        inbox.onDiscard = { ids, reason ->
            logger.log("INBOX DISCARD ${ids.size} row(s) reason=\"$reason\" ids=$ids")
            log("INBOX DISCARD ${ids.size} row(s): $reason")
        }

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
        //
        // The signal goes to the CONVERSATION, never to the friend list. `contextId` is a
        // canonical two-party "userIdA_userIdB" value, so broadcasting it to every accepted
        // friend tells all of them, in real time, that you are typing to a named third party.
        // That is what this used to do. It is the same rule `SyncManager`'s
        // `Audience.Conversation` already enforces for entries, applied here too — and it
        // fails CLOSED: an id that does not resolve to exactly two participants sends nothing.
        signalManager.sendSignal = { modelName, signalName, signalData ->
            val kind = com.obscura.kit.orm.WireCodec.encodeSignalKind(signalName)
            val ctxId = signalData["conversationId"] as? String ?: ""
            val participants = ctxId.split("_").filter { it.isNotBlank() }

            if (participants.size != 2) {
                // Refusing to broadcast a 1:1 signal. Dropping an ephemeral typing indicator
                // costs nothing; guessing an audience for it leaks the conversation.
                log("SIGNAL DROPPED: model=$modelName kind=$signalName contextId=\"$ctxId\" is not a canonical two-party id")
                logger.log("signal dropped: contextId is not a canonical two-party value — refusing to broadcast a 1:1 signal")
            } else {
                val signalMsg = ClientMessage.newBuilder()
                    .setTimestamp(System.currentTimeMillis())
                    .setModelSignal(obscura.client.v1.modelSignal {
                        model = modelName
                        this.kind = kind
                        contextId = ctxId
                    })
                    .build()
                authManager.ensureFreshToken()
                // Everyone in the conversation except this user. Own devices are deliberately
                // excluded: a typing indicator is for the other party, and echoing it to your
                // own devices is noise the app has never asked for.
                val recipients = participants.filter { it != session.userId }
                for (userId in recipients) {
                    var deviceIds = messenger.getDeviceIdsForUser(userId)
                    if (deviceIds.isEmpty()) {
                        try { messenger.fetchPreKeyBundles(userId) } catch (e: Exception) { log("prekey bundle fetch failed: ${e.message}") }
                        deviceIds = messenger.getDeviceIdsForUser(userId)
                    }
                    for (devId in deviceIds) {
                        messenger.queueMessage(devId, signalMsg, userId)
                    }
                }
                messenger.flushMessages()
            }
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
     * Full logout — handles ALL teardown in correct order.
     * Bridges call this single method instead of orchestrating cleanup.
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
        db.attachmentCacheQueries.deleteAll()
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
        gateway.disconnect() // fires onStateChanged → _connectionState = DISCONNECTED
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
     * [timeoutMs] ms (returning early when the queue stays empty for 500ms), categorizes
     * by ORM model, and returns counts. Does NOT disconnect afterwards — the OS will
     * freeze the app when done.
     *
     * The bridge layer uses the returned counts to post a generic local notification
     * ("New pix" / "New message"). Kit must NEVER post OS notifications itself.
     *
     * Categorization (per the locked cross-platform contract):
     *   MODEL_SYNC with sync.model == "pix"           → pixCount
     *   MODEL_SYNC with sync.model == "directMessage" → messageCount
     *   Legacy TEXT / IMAGE ClientMessage              → messageCount
     *   Everything else                                → otherCount (debug only)
     */
    suspend fun processPendingMessages(timeoutMs: Long): ProcessedCounts {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            // F10 (PLAN.md). This used to be `try { connect() } catch (_: Exception) { return
            // ProcessedCounts() }` — a failed connect returned all-zero counts, which is
            // indistinguishable from "connected fine, nothing waiting". On the PUSH-WAKE path that
            // means: woken by a push, silently report no messages, leave them on the server, no
            // error anywhere. It also made `PushTests` ~25% flaky (a failing run returned in ~0.1s,
            // well under the 500ms idle threshold a real drain must reach).
            //
            // The failure was transient in every observed case, so: retry once, and if it still
            // fails, say so. The zero-count return is unchanged — callers see today's contract —
            // but the failure is no longer invisible. Making zeros distinguishable from
            // "could not connect" is a deliberate API change and belongs to Phase 4, where the iOS
            // NSE forces the question.
            try {
                connect()
            } catch (e: Exception) {
                log("PUSH DRAIN connect attempt 1 failed: ${e.message}")
                logger.log("push drain: connect failed (attempt 1/2): ${e.message}")
                delay(PUSH_DRAIN_RECONNECT_RETRY_MS)
                try {
                    connect()
                } catch (e2: Exception) {
                    log("PUSH DRAIN connect attempt 2 failed — returning empty counts: ${e2.message}")
                    logger.log("push drain ABORTED: could not connect after 2 attempts: ${e2.message}")
                    return ProcessedCounts()
                }
            }
        }

        var pix = 0
        var message = 0
        var other = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        val idleThresholdMs = 500L
        var lastEnvelopeAt = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            val received = incomingMessages.tryReceive().getOrNull()
            if (received != null) {
                classifyForPushCounts(received).let { (p, m, o) ->
                    pix += p; message += m; other += o
                }
                lastEnvelopeAt = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastEnvelopeAt > idleThresholdMs) {
                break
            } else {
                delay(50)
            }
        }

        return ProcessedCounts(pixCount = pix, messageCount = message, otherCount = other)
    }

    /** Classify a single envelope into (pix, message, other) buckets. */
    private fun classifyForPushCounts(msg: ReceivedMessage): Triple<Int, Int, Int> {
        // MODEL_SYNC carries the ORM model name — the authoritative categorization.
        if (msg.type == "MODEL_SYNC" && msg.raw != null) {
            val modelName = msg.raw.modelSync.model
            when (modelName) {
                "pix" -> return Triple(1, 0, 0)
                "directMessage" -> return Triple(0, 1, 0)
            }
        }
        // Legacy TEXT / IMAGE counts as message (unused by current app, but contract mandates)
        if (msg.type == "TEXT" || msg.type == "IMAGE") {
            return Triple(0, 1, 0)
        }
        return Triple(0, 0, 1)
    }

    private var preKeyStatusJob: Job? = null
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
                // Phase 2 / Option B: the envelope carries BOTH the sending USER (sender_id, a
                // routing/attribution HINT) and the sending DEVICE (sender_device_id, which selects
                // the inbound Signal session). We take the message's user from sender_id and key the
                // decrypt-failure rate limiter on it. We do NOT resolve the user from the friend
                // graph — the graph supplies only the DISPLAY NAME (SPEC §0.5).
                val senderId = try {
                    val bytes = envelope.senderId.toByteArray()
                    if (bytes.size != 16) null
                    else UuidCodec.bytesToUuid(bytes).toString()
                } catch (_: Exception) { null }

                if (senderId == null) {
                    // No sending user on the wire — a malformed/unroutable envelope. We do NOT guess.
                    // Leave it on the server (persist-then-ack); do not ack.
                    log("RECV FAIL envelope carries no sender_id (left on server, not acked)")
                    continue
                }

                // PERSIST-THEN-ACK. An ACK is a DELETE on the server (gateway AckBatcher ->
                // message_service.delete_batch -> DELETE FROM messages). So we ACK ONLY WHAT WE
                // HAVE DURABLY PERSISTED. Every path below that has not persisted the message must
                // skip the ack and leave it on the server, where a fresh MessagePump redelivers it
                // on the next reconnect. There is exactly one ack in this loop, and it is the last
                // thing that happens after a successful decrypt + persist.

                // F3: a rate-limited sender (keyed on the sending user) is NOT processed and NOT
                // persisted -> do NOT ack. It is retried once the failure window expires.
                if (isDecryptRateLimited(senderId)) {
                    log("RECV BLOCKED rate-limited sender=$senderId (left on server, not acked)")
                    continue
                }

                try {
                    // 1. DECRYPT. Throws on a bad MAC / missing session -> falls to catch -> no ack.
                    // The session is selected by the sending DEVICE (sender_device_id); a valid MAC
                    // then authenticates the sender. decrypted.sourceUserId == envelope.sender_id.
                    val decrypted = messenger.decrypt(envelope)
                    val msg = decrypted.clientMessage
                    val sourceUserId = decrypted.sourceUserId

                    log("RECV ${msg.payloadCase.name} from=${sourceUserId.take(8)} device=${decrypted.senderDeviceId.take(8)} text=${msg.text.text.take(40)}")

                    // 2. PERSIST (durable). routeMessage's handlers write to the SQLDelight store
                    // (e.g. handleTextMessage -> messagesDomain.add -> messageQueries.insert;
                    // friends.add; orm.handleSync). This is the source of truth. If it throws, we
                    // fall to catch and do NOT ack, so the message survives on the server.
                    // `Envelope.id` is 16 raw UUID bytes on the wire. The inbox's dedupe key must be
                    // a STABLE text form of them — the same canonical encoding the rest of the kit
                    // uses for user and device ids, so two encodings of one envelope can never
                    // produce two rows.
                    routeMessage(
                        msg, sourceUserId, decrypted.senderDeviceId,
                        UuidCodec.bytesToUuid(envelope.id.toByteArray()).toString(),
                    )

                    decryptFailures.remove(senderId)

                    // DISPLAY NAME (SPEC §0.5): for a friend REQUEST/RESPONSE — first contact, sender
                    // not yet a friend — the display username is the legitimate payload bootstrap.
                    // For every other payload the display name is NOT read here; it comes from the
                    // friend graph keyed on sourceUserId when the app renders the conversation.
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
                        sourceUserId = sourceUserId,
                        senderDeviceId = decrypted.senderDeviceId,
                        raw = msg
                    )

                    // 3. NOTIFY (best-effort). These emits are wake-up notifications over data that
                    // step 2 has ALREADY durably persisted -- the app model is "event -> refetch
                    // everything from the store", and the store, not this channel/flow, is the
                    // durable delivery path. So a dropped emit loses a NOTIFICATION, never a
                    // message, precisely because persistence happened-before the ack below. We keep
                    // them droppable rather than a suspending send() on purpose: incomingMessages is
                    // a 1000-capacity channel the app does not always drain, and a blocking send
                    // would stall the whole receive loop (and thus all acking) behind a full buffer.
                    // We log a drop so it is observable and never silent.
                    if (!incomingMessages.trySend(received).isSuccess) {
                        log("RECV NOTE incomingMessages full; dropped a wake-up (data already persisted)")
                    }
                    if (!_events.tryEmit(received)) {
                        log("RECV NOTE events buffer full; dropped a wake-up (data already persisted)")
                    }

                    checkAndReplenishPreKeys()

                    // 4. ACK. Reached only when decrypt AND persist both succeeded. This is the sole
                    // ack in the loop; the rate-limit early-return and the catch below both skip it.
                    try { gateway.ack(listOf(envelope.id)) } catch (e: Exception) { log("envelope ack failed: ${e.message}") }
                } catch (e: Exception) {
                    // Decrypt failed OR persistence (routeMessage) threw. The message is NOT durably
                    // stored -> do NOT ack (F2). It stays on the server and redelivers on reconnect.
                    log("RECV FAIL sender=$senderId err=${e.message?.take(60)} (left on server, not acked)")
                    trackDecryptFailure(senderId)
                    logger.decryptFailed(senderId, e.message ?: "unknown")
                }
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

    /**
     * Persist a decrypted message, by class (`obscura-proto/KIT_API.md` §4).
     *
     * Called from the envelope loop **before** the ack, and it throws on a failed durable write so
     * the ack is skipped and the message survives on the server (SPEC §0.9 rule 3).
     *
     * The `when` below is no longer the whole story: [classify] decides what each arm is *allowed*
     * to do, and this routes accordingly. The old `else -> { }` swallowed seven arms and then let
     * the caller ack them, which destroyed them silently.
     */
    private suspend fun routeMessage(
        msg: ClientMessage,
        sourceUserId: String,
        senderDeviceId: String?,
        envelopeId: String,
    ) {
        when (classify(msg.payloadCase)) {
            PayloadClass.INBOXED -> inboxMessage(msg, sourceUserId, senderDeviceId, envelopeId)

            PayloadClass.UNIMPLEMENTED ->
                // Classified in §4 but implemented by neither kit. Today's behaviour (drop, then
                // ack) is preserved deliberately — §4.2 keys the inbox fallback on absence from the
                // classification TABLE, not absence from the handler, or the inbox becomes where
                // unimplemented kit work goes to be forgotten. What changes is that it is no longer
                // silent. Four of these are Phase 3 deletions; DEVICE_RECOVERY_ANNOUNCE is a
                // deliberate deferral and cannot currently fire at all.
                log("RECV UNIMPLEMENTED arm=${msg.payloadCase.name} from=${sourceUserId.take(8)} " +
                    "(dropped and acked — see KIT_API.md §4.2)")

            PayloadClass.DROPPABLE, PayloadClass.KIT_INTERNAL -> when (msg.payloadCase) {
                ClientMessage.PayloadCase.FRIEND_REQUEST -> handleFriendRequest(msg, sourceUserId)
                ClientMessage.PayloadCase.FRIEND_RESPONSE -> handleFriendResponse(msg, sourceUserId)
                ClientMessage.PayloadCase.TEXT -> handleTextMessage(msg, sourceUserId, senderDeviceId)
                ClientMessage.PayloadCase.DEVICE_ANNOUNCE -> handleDeviceAnnounce(msg, sourceUserId)
                ClientMessage.PayloadCase.SYNC_BLOB -> handleSyncBlob(msg, sourceUserId)
                ClientMessage.PayloadCase.SENT_SYNC -> handleSentSync(msg)
                ClientMessage.PayloadCase.SESSION_RESET ->
                    // Sessions are keyed on the DEVICE UUID (Phase 2), so reset every session we
                    // hold with any of this user's devices.
                    messenger.getDeviceIdsForUser(sourceUserId).forEach { signalStore.deleteAllSessions(it) }
                ClientMessage.PayloadCase.FRIEND_SYNC -> handleFriendSync(msg, sourceUserId)
                ClientMessage.PayloadCase.DEVICE_LINK_APPROVAL -> handleLinkApproval(msg, sourceUserId)
                ClientMessage.PayloadCase.MODEL_SIGNAL -> handleModelSignal(msg, sourceUserId, senderDeviceId)
                else -> error("classified ${msg.payloadCase.name} as kit-internal with no handler")
            }
        }
    }

    /**
     * Write an inboxed payload to the durable inbox, then hand it to the ORM as well.
     *
     * **Both, during §10 steps 2–3.** The ORM still owns what obscura-pix reads, so removing that
     * call here would break the app long before it has anywhere else to read from. The duplication
     * ends when the ORM is deleted in step 4.
     *
     * Order matters: the inbox row commits first. If it throws, nothing is acked and the message
     * stays on the server — which is the whole point of persist-then-ack and the reason this is not
     * an event stream.
     */
    private suspend fun inboxMessage(
        msg: ClientMessage,
        sourceUserId: String,
        senderDeviceId: String?,
        envelopeId: String,
    ) {
        val isModelSync = msg.payloadCase == ClientMessage.PayloadCase.MODEL_SYNC
        val sync = msg.modelSync

        val inserted = inbox.put(
            InboxRecord(
                id = 0, // assigned by the database
                envelopeId = envelopeId,
                kind = msg.payloadCase.name,
                receivedAt = System.currentTimeMillis(),
                senderUserId = sourceUserId,
                senderDeviceId = senderDeviceId,
                // SPEC §0.5: the name comes from OUR friend graph, keyed on the authenticated
                // sender, never from the payload. Null when they are not a friend — §7 covers what
                // the app should show then, and it is not a peer-chosen string.
                senderDisplayName = friends.get(sourceUserId)?.username,
                // ModelSync-derived, so null for an unknown arm — there is nothing to derive from.
                modelKey = if (isModelSync) sync.model else null,
                entryId = if (isModelSync) sync.id else null,
                // `WireCodec.decodeOp`, not the raw enum name: the inbox is read by the app
                // across a bridge, so this must be the app-facing CREATE/UPDATE/DELETE that
                // KIT_API.md §3.1 specifies and that the ORM path already emits. `sync.op.name`
                // gives the PROTO spelling `OP_CREATE`, which no other surface uses.
                op = if (isModelSync) com.obscura.kit.orm.WireCodec.decodeOp(sync.op).name else null,
                sentAt = if (isModelSync) clampFutureTimestamp(sync.timestamp) else null,
                // Opaque bytes. For an unknown arm this is the whole serialized message, because the
                // kit cannot know which sub-field would have been the payload.
                payload = if (isModelSync) sync.data.toByteArray() else msg.toByteArray(),
            )
        )

        if (!inserted) {
            // A redelivered envelope. Not an error: persist-then-ack guarantees this happens, and
            // absorbing it here is what keeps depth() and the app's counts honest. Still ack.
            log("RECV DUPLICATE envelope=${envelopeId.take(12)} kind=${msg.payloadCase.name} (already inboxed)")
        }

        if (isModelSync) handleModelSync(msg, sourceUserId)
    }

    /**
     * SPEC §2.4: a peer-supplied timestamp is clamped before it is stored, not after.
     *
     * Without this a peer can set `sentAt` far in the future and win every REPLACE conflict forever
     * — the tie-break can only order writes it can compare honestly.
     */
    private fun clampFutureTimestamp(sentAt: Long): Long =
        minOf(sentAt, System.currentTimeMillis() + 60_000L)

    private suspend fun handleFriendRequest(msg: ClientMessage, sourceUserId: String) {
        // sourceUserId is envelope.sender_id — the requester's USER id, server-stamped and
        // authenticated by the Signal session that decrypted this message (TOFU: libsignal pins the
        // sender's identity key on first contact, exactly as Signal does).
        //
        // The payload username is a FIRST-CONTACT bootstrap only (SPEC §0.10 rule 5). This used to
        // call friends.add() unconditionally, and Friend.sq's INSERT OR REPLACE meant an
        // already-accepted friend could re-send a FriendRequest to (a) rename themselves — including
        // on the lock screen — and (b) reset their own status to PENDING_RECEIVED, silently dropping
        // themselves out of getAccepted() and out of fan-out. The old comment asserted "this is
        // first contact, so the sender is not yet in our friend graph" and nothing enforced it.
        val existing = friends.get(sourceUserId)
        if (existing != null) {
            // Already known. The name now comes from our graph, never from their payload. Refresh
            // the device list (that IS ours to learn) and change nothing else.
            friends.updateDevices(sourceUserId, messenger.knownDevicesFor(sourceUserId))
            logger.log(
                "friend request from already-known peer $sourceUserId (status=${existing.status.value}); " +
                    "keeping stored name and status"
            )
            return
        }
        // Genuine first contact: the payload username is the only name that exists.
        friends.add(sourceUserId, msg.friendRequest.username, FriendStatus.PENDING_RECEIVED,
            messenger.knownDevicesFor(sourceUserId))
    }

    private suspend fun handleFriendResponse(msg: ClientMessage, sourceUserId: String) {
        if (!msg.friendResponse.accepted) return

        // A FRIEND_RESPONSE is only meaningful as the answer to a request WE sent. This used to call
        // friends.add(..., ACCEPTED) unconditionally, which meant ANY authenticated user who can
        // reach us — friendship is not required to send, the server relays to any device — could
        // insert themselves into the friend graph as an ACCEPTED friend under a name they chose,
        // with no interaction from us at all. Requiring a matching PENDING_SENT closes that.
        val existing = friends.get(sourceUserId)
        if (existing == null || existing.status != FriendStatus.PENDING_SENT) {
            logger.log(
                "ignoring unsolicited FRIEND_RESPONSE from $sourceUserId " +
                    "(local status=${existing?.status?.value ?: "none"}; expected pending_sent)"
            )
            return
        }

        // Promote in place. The name stays the one WE recorded when we sent the request; the
        // payload's username is not consulted (SPEC §0.5 — the graph names the peer, not the peer).
        friends.updateStatus(sourceUserId, FriendStatus.ACCEPTED)
        friends.updateDevices(sourceUserId, messenger.knownDevicesFor(sourceUserId))
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
        friends.updateDevices(sourceUserId, announce.devicesList.map { d ->
            FriendDeviceInfo(d.deviceUuid, d.deviceId, d.deviceName)
        })
    }

    private suspend fun handleModelSignal(msg: ClientMessage, sourceUserId: String, senderDeviceId: String?) {
        try {
            val sig = msg.modelSignal
            if (sig.model.isBlank()) return

            val signalName = com.obscura.kit.orm.WireCodec.decodeSignalKind(sig.kind)
                ?: return // unknown/unspecified — ignore

            // Identity comes from the authenticated envelope, never the payload:
            // the device from the decrypted session, the display name from the friend graph.
            // authorDeviceId is the sending device's UUID (proven by the session MAC); it MUST
            // NOT fall back to a user id — a user id in a device field is a false claim (F4).
            val authorDeviceId = senderDeviceId ?: "unknown"
            val senderUsername = friends.getAccepted().find { it.userId == sourceUserId }?.username ?: sourceUserId
            val data = mapOf<String, Any?>(
                "conversationId" to sig.contextId,
                "senderUsername" to senderUsername,
            )

            if (signalName == "stoppedTyping") {
                signalManager.clear(sig.model, "typing", data, authorDeviceId)
            } else {
                signalManager.receive(sig.model, signalName, data, authorDeviceId)
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

        // DirectMessage MODEL_SYNC → also route to conversations for chat UI
        if (sync.model == "directMessage") {
            try {
                val json = org.json.JSONObject(String(sync.data.toByteArray()))
                val content = json.optString("content", "")
                // File under the sender's userId — that's who we're chatting with
                val conversationWith = sourceUserId
                val msgData = MessageData(
                    id = sync.id, conversationId = conversationWith,
                    authorDeviceId = sync.authorDeviceId,
                    content = content, timestamp = sync.timestamp,
                    type = "text"
                )
                messagesDomain.add(conversationWith, msgData)
                refreshConversation(conversationWith)
            } catch (e: Exception) { log("directMessage conversation routing failed: ${e.message}") }
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
     * Send a text message via ORM (MODEL_SYNC). Interoperable with iOS DirectMessage.
     * Falls back to legacy TEXT if directMessage model is not defined.
     */
    suspend fun send(friendUsername: String, text: String) {
        log("SEND to=$friendUsername text=${text.take(40)}")
        val directMessage = orm.modelOrNull("directMessage")
        if (directMessage != null) {
            val friendData = friends.getAccepted().find { it.username == friendUsername }
                ?: throw com.obscura.kit.ObscuraError.NotFriends(friendUsername)
            val convId = listOf(userId ?: "", friendData.userId).sorted().joinToString("_")
            // Create via ORM — auto-syncs to friend via MODEL_SYNC
            val entry = directMessage.create(mapOf(
                "conversationId" to convId,
                "content" to text,
                "senderUsername" to (username ?: "")
            ))
            // Also persist locally to conversations (keyed by friend userId for StateFlow compat)
            messagesDomain.add(friendData.userId, MessageData(
                id = entry.id, conversationId = friendData.userId,
                authorDeviceId = deviceId ?: "self",
                content = text, timestamp = entry.timestamp, type = "text"
            ))
            refreshConversation(friendData.userId)
        } else {
            // Legacy path — sends TEXT (type 0) instead of MODEL_SYNC
            messagingManager.send(friendUsername, text)
        }
    }
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
