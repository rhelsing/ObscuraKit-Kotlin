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
import com.obscura.kit.wire.SignalManager
import com.obscura.kit.wire.WireCodec
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
import com.obscura.kit.persistence.NoOpSessionStorage
import com.obscura.kit.persistence.SessionStorage
import obscura.client.v1.Client.ClientMessage
import org.json.JSONObject
import org.signal.libsignal.protocol.ecc.Curve
import java.util.*
import java.util.concurrent.atomic.AtomicLong

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

    // `events` / `_events` were here: a deprecated second ReceivedMessage stream with no consumer
    // in either the kit, the bridge or obscura-pix, whose `tryEmit` sat in the hot receive loop.
    // `typedEvents` below and the `incomingMessages` channel are the two that are actually read.

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

    /**
     * The durable inbox (`obscura-proto/KIT_API.md` §3) — the thin kit's receive API.
     *
     * Four methods: peek / consume / discard / depth. The kit writes rows before it acks; the app
     * drains them. There is no insert, because the kit is the only writer.
     */
    val inbox: InboxDomain

    /**
     * Raw storage for application entries (`obscura-proto/KIT_API.md` §8.1) — the other half of the
     * thin kit's app-facing surface. `inbox` is how messages arrive; this is where the app keeps
     * what it made of them.
     */
    val entries: EntryStore

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

    private var envelopeJob: Job? = null

    // M13: Decrypt rate limiting per sender
    private val decryptFailures = mutableMapOf<String, Pair<Int, Long>>() // senderId -> (count, windowStart)
    private val MAX_DECRYPT_FAILURES = 10
    private val DECRYPT_FAILURE_WINDOW_MS = 60_000L

    // F10: backoff between the two connect attempts in processPendingMessages. Short on purpose —
    // the push path runs inside a tight OS budget (iOS gives an NSE ~30s), so a retry that costs
    // seconds is worse than no retry at all.
    private val PUSH_DRAIN_RECONNECT_RETRY_MS = 250L
    private val pushDrainMutex = Mutex()
    private val processedEnvelopeCount = AtomicLong()
    private val lastProcessedEnvelopeAtMs = AtomicLong()

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
        entries = EntryStore(db)
        // A discard is data loss the app chose deliberately, and §3.3 rule 5 requires it be logged
        // as a security-relevant event rather than being the quiet path.
        inbox.onDiscard = { ids, reason ->
            logger.log("INBOX DISCARD ${ids.size} row(s) reason=\"$reason\" ids=$ids")
            log("INBOX DISCARD ${ids.size} row(s): $reason")
        }

        signalManager = SignalManager()
        gateway = GatewayConnection(api, scope)


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
                // The §3.3 rule 2 carve-out, and the reason it is worded as a MUST: the inbox holds
                // DECRYPTED plaintext — full payloads, the resolved sender name, the model key. A
                // wipe that spared it would leave exactly the content a revocation is meant to
                // destroy, and on Android the database is still plaintext SQLite, so it would be
                // readable with no key at all. Note this destroys the whole table rather than
                // selecting rows: that is what keeps it a security operation and not the eviction
                // policy §3.4 refuses to add.
                //
                // Routed through inbox.wipe() rather than the query directly so that the test which
                // pins this carve-out exercises the shipping path. It previously tested a method
                // production never called.
                inbox.wipe()
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

        // The ORM's SyncManager wiring lived here: getSelfSyncTargets / getFriendTargets /
        // queueModelSync / getDevicesForUsername / getDevicesForUserId / flushQueue. That was the
        // audience-routing engine, and it is gone — SPEC §0.4 puts audience resolution in the
        // caller, and obscura-pix now does it (`src/domain/audience.ts`) with the five
        // `routing.json` leak guards vendored alongside it.
        //
        // The signal wiring below is NOT part of that and stays: ephemeral signals are on HISTORY.md's
        // Keep list, and their audience is the two-party conversation id, resolved fail-CLOSED.
        signalManager.sendSignal = { modelName, signalName, signalData ->
            val kind = WireCodec.encodeSignalKind(signalName)
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
    }
    suspend fun login(username: String, password: String): com.obscura.kit.network.LoginResult {
        val result = authManager.login(username, password)
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
    }

    fun hasSession(): Boolean = authManager.hasSession()

    /**
     * Log out: tears down the connection and forgets the session, INCLUDING the
     * persisted [sessionStorage] blob, so the app won't try to restore it next
     * launch. Local data (friends, messages, entries, inbox) is kept — see [wipeDevice] /
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
        // Merge onto existing storage rather than replacing it, so any non-session key the host app
        // keeps in the same blob survives a session-only save regardless of whether the
        // SessionStorage impl patches keys or overwrites wholesale.
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
     * Restore session from storage and connect.
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
     * Decode a friend code and befriend the user. See [FriendCode] for the format.
     *
     * Both this and [friendCode] used to inline their own copy of the codec, and the copies had
     * DIVERGED from the tested one: the inline decode did not map URL-safe base64 (`-`/`_`), which
     * some QR scanners hand back, and it accepted empty `u`/`n` fields, so a code decoding to `{}`
     * befriended the empty-string user. `FriendCodeTest`'s seven tests covered only the object
     * nobody called. Delegating makes the tested code the shipping code.
     *
     * The soft-hyphen and whitespace strip is kept here — it is about text that survived a copy out
     * of an iOS share sheet, not about the encoding.
     */
    suspend fun addFriendByCode(code: String) {
        val cleaned = code
            .replace("\u00AD", "") // strip soft hyphens from iOS copy
            .replace("\\s".toRegex(), "")
        val decoded = FriendCode.decode(cleaned)
        log("ADD_FRIEND_BY_CODE ${decoded.username} (${decoded.userId})")
        befriend(decoded.userId, decoded.username)
    }

    /** Generate a friend code for sharing. See [FriendCode]. */
    fun friendCode(): String {
        val uid = userId ?: throw com.obscura.kit.ObscuraError.NotAuthenticated()
        val uname = username ?: throw com.obscura.kit.ObscuraError.NotAuthenticated()
        return FriendCode.encode(uid, uname)
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
        preKeyStatusJob?.cancel()
        // SignalManager's own scope outlives this object otherwise: every `receive` launches a
        // 3.1s expiry coroutine, and after a logout those keep running (and keep mutating typing
        // state) for a user who is gone.
        signalManager.shutdown()
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
        // Without this it keeps consuming gateway.preKeyStatus and calling replenishPreKeys() —
        // which POSTs /v1/devices/keys with whatever token is left, i.e. a null one after a logout.
        preKeyStatusJob?.cancel()
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
     * [timeoutMs] ms (returning early when the receive path stays idle for 500ms), and returns
     * the number of successfully processed envelopes. Does NOT disconnect afterwards — the OS will
     * freeze the app when done.
     *
     * This observes successful receive-path persistence without consuming [incomingMessages].
     * The app remains that channel's single consumer and owns all notification classification.
     */
    suspend fun processPendingMessages(timeoutMs: Long): Int =
        pushDrainMutex.withLock { performPendingMessageDrain(timeoutMs) }

    private suspend fun performPendingMessageDrain(timeoutMs: Long): Int {
        val processedAtStart = processedEnvelopeCount.get()

        if (_connectionState.value != ConnectionState.CONNECTED) {
            // F10 (HISTORY.md). This used to be `try { connect() } catch (_: Exception) { return
            // 0 }` — a failed connect returned zero, which is
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
                    log("PUSH DRAIN connect attempt 2 failed — returning zero: ${e2.message}")
                    logger.log("push drain ABORTED: could not connect after 2 attempts: ${e2.message}")
                    return 0
                }
            }
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        val idleThresholdMs = 500L
        var lastActivityAt = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            val observedAt = lastProcessedEnvelopeAtMs.get()
            if (observedAt > lastActivityAt) {
                lastActivityAt = observedAt
            }
            if (System.currentTimeMillis() - lastActivityAt > idleThresholdMs) {
                break
            } else {
                delay(50)
            }
        }

        val processed = (processedEnvelopeCount.get() - processedAtStart)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
        return processed.toInt()
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

    /**
     * At most one replenishment in flight at a time.
     *
     * [checkAndReplenishPreKeys] fires once per received envelope, so draining a backlog of N
     * messages launched N coroutines that all observed a count below the threshold, all computed
     * the same `highestId + 1` range, and all POSTed 50 keys — N uploads of the same key ids.
     */
    private val replenishInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun checkAndReplenishPreKeys() {
        if (!replenishInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (signalStore.getPreKeyCount() < PREKEY_MIN_COUNT) {
                    replenishPreKeys()
                }
            } catch (e: Exception) { /* non-fatal */ }
            finally { replenishInFlight.set(false) }
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

                // `Envelope.id` is now the inbox's DEDUPE KEY, so it gets the same length check
                // `sender_id` above and `sender_device_id` in MessengerDomain already get — and for
                // the same reason: SPEC §0.10 treats everything the relay stamps as untrusted.
                //
                // Without this, `UuidCodec.bytesToUuid` returns the NIL UUID for anything shorter
                // than 16 bytes (proto3's default for an unset `bytes` field is empty). Every such
                // envelope would then hash to one key: the first inserts and is acked, and every
                // one after it is suppressed by INSERT OR IGNORE, reported as a duplicate, and
                // ACKED — the server deletes messages that were never stored. Silent, permanent,
                // and remotely triggerable by anything upstream that emits a short id.
                val envelopeIdBytes = envelope.id.toByteArray()
                if (envelopeIdBytes.size != 16) {
                    log("RECV FAIL envelope id is ${envelopeIdBytes.size} bytes, expected 16 " +
                        "(left on server, not acked)")
                    continue
                }
                val envelopeId = UuidCodec.bytesToUuid(envelopeIdBytes).toString()

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
                    // friends.add; inbox.put). This is the source of truth. If it throws, we
                    // fall to catch and do NOT ack, so the message survives on the server.
                    // `envelopeId` is the canonical text form of the 16 validated bytes above —
                    // one encoding, so two spellings of one envelope can never produce two rows.
                    val isNew = routeMessage(msg, sourceUserId, decrypted.senderDeviceId, envelopeId)

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
                        type = WireCodec.decodeType(msg.payloadCase),
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
                    //
                    // Skipped entirely for a redelivery (`isNew == false`). The app already has that
                    // message; announcing it again can make the app post a duplicate notification.
                    lastProcessedEnvelopeAtMs.set(System.currentTimeMillis())
                    processedEnvelopeCount.incrementAndGet()
                    if (isNew && !incomingMessages.trySend(received).isSuccess) {
                        log("RECV NOTE incomingMessages full; dropped a wake-up (data already persisted)")
                    }

                    checkAndReplenishPreKeys()

                    // 4. ACK. Reached only when decrypt AND persist both succeeded — including the
                    // redelivery case, where "persisted" means the row was already there. This is
                    // the sole ack in the loop; the rate-limit early-return and the catch below both
                    // skip it.
                    //
                    // An ack failure is the event the whole persist-then-ack design pivots on: the
                    // server keeps its copy and redelivers, which is safe but is also the first
                    // symptom of a wedged queue. It goes to the structured logger, not only to the
                    // 200-entry in-memory ring buffer where nobody will ever see it.
                    try {
                        gateway.ack(listOf(envelope.id))
                    } catch (e: Exception) {
                        log("envelope ack failed: ${e.message}")
                        logger.ackFailed(envelopeId, e.message ?: "unknown")
                    }
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
     *
     * Returns false when this envelope was a REDELIVERY the inbox absorbed, so the caller can skip
     * the wake-up emits. It still gets acked — see [inboxMessage].
     */
    internal suspend fun routeMessage(
        msg: ClientMessage,
        sourceUserId: String,
        senderDeviceId: String?,
        envelopeId: String,
    ): Boolean {
        when (classify(msg.payloadCase)) {
            PayloadClass.INBOXED -> return inboxMessage(msg, sourceUserId, senderDeviceId, envelopeId)

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
                ClientMessage.PayloadCase.SENT_SYNC -> handleSentSync(msg, sourceUserId)
                ClientMessage.PayloadCase.SESSION_RESET ->
                    // Sessions are keyed on the DEVICE UUID (Phase 2), so reset every session we
                    // hold with any of this user's devices.
                    messenger.getDeviceIdsForUser(sourceUserId).forEach { signalStore.deleteAllSessions(it) }
                ClientMessage.PayloadCase.DEVICE_LINK_APPROVAL -> handleLinkApproval(msg, sourceUserId)
                ClientMessage.PayloadCase.MODEL_SIGNAL -> handleModelSignal(msg, sourceUserId, senderDeviceId)
                else -> error("classified ${msg.payloadCase.name} as kit-internal with no handler")
            }
        }
        return true
    }

    /**
     * Write an inboxed payload to the durable inbox.
     *
     * If the write throws, nothing is acked and the message stays on the server — that is the whole
     * point of persist-then-ack and the reason this is not an event stream.
     *
     * Returns **false when the row was already there**, i.e. this envelope is a redelivery the
     * `envelope_id UNIQUE` key absorbed. The caller still acks (acking is what stops a third copy
     * arriving) but must not notify: `Inbox.sq` says in as many words that the dedupe exists so a
     * duplicate cannot "inflate the app's processing counts, and post a second notification for a
     * message the user already has" — and until now the emits below fired unconditionally, so it
     * did both. Persist-then-ack GUARANTEES redelivery, so this is a normal path, not an edge case.
     */
    internal suspend fun inboxMessage(
        msg: ClientMessage,
        sourceUserId: String,
        senderDeviceId: String?,
        envelopeId: String,
    ): Boolean {
        val isModelSync = msg.payloadCase == ClientMessage.PayloadCase.MODEL_SYNC
        val sync = msg.modelSync

        val inserted = inbox.put(
            InboxRecord(
                id = 0, // assigned by the database
                envelopeId = envelopeId,
                // Must match Swift byte for byte — the app reads one `kind` column from two
                // kits, and §4.1 has pix's drain BRANCH on it (an unrecognised kind is discarded).
                // `payloadCase.name` gives Kotlin's "PAYLOAD_NOT_SET" where Swift's WireCodec gives
                // "", so a drain keying on one silently fails on the other platform: rows pile up,
                // depth never returns to zero, and with the `after:` cursor deferred the head of the
                // queue wedges. Both kits now go through WireCodec and share the UNKNOWN sentinel —
                // an empty string is a poor value for a NOT NULL column read across a bridge.
                kind = WireCodec.decodeType(msg.payloadCase).ifEmpty { "UNKNOWN" },
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
                // KIT_API.md §3.1 specifies. `sync.op.name`
                // gives the PROTO spelling `OP_CREATE`, which no other surface uses.
                op = if (isModelSync) WireCodec.decodeOp(sync.op).name else null,
                sentAt = if (isModelSync) clampFutureTimestamp(sync.timestamp) else null,
                // Opaque bytes. For an unknown arm this is the whole serialized message, because the
                // kit cannot know which sub-field would have been the payload.
                payload = if (isModelSync) sync.data.toByteArray() else msg.toByteArray(),
            )
        )

        if (!inserted) {
            // A redelivered envelope. Not an error: persist-then-ack guarantees this happens, and
            // absorbing it here is what keeps depth() and the app's counts honest. Still acked by
            // the caller; just not announced a second time.
            log("RECV DUPLICATE envelope=${envelopeId.take(12)} kind=${msg.payloadCase.name} (already inboxed)")
            return false
        }

        // The typed event stream's only app-data event. It deliberately carries NO payload: the
        // bytes are in the inbox and the app drains them, so an event carrying data would be a
        // second delivery path competing with the store (KIT_API §2). Emitting it here — after the
        // row has committed — keeps it a wake-up about something already durable.
        if (isModelSync) {
            _typedEvents.tryEmit(ObscuraEvent.MessageReceived(sync.model))
        }
        return true
    }

    /**
     * SPEC §2.4: a peer-supplied timestamp is clamped before it is stored, not after.
     *
     * Without this a peer can set `sentAt` far in the future and win every REPLACE conflict forever
     * — the tie-break can only order writes it can compare honestly.
     */
    internal fun clampFutureTimestamp(sentAt: Long): Long {
        val cap = System.currentTimeMillis() + 60_000L
        // `ModelSync.timestamp` is proto3 `uint64`, which protobuf-java surfaces as a SIGNED Long —
        // so a peer sending >= 2^63 arrives here NEGATIVE and sails under any `minOf` cap. Swift
        // does the same comparison in UInt64 space and correctly yields the cap, so the unguarded
        // version stored roughly -9.2e18 on Android and now+60s on iOS for identical wire bytes.
        // Clamping both ends keeps §2.4 honest and the two kits in agreement.
        if (sentAt < 0) return cap
        return minOf(sentAt, cap)
    }

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
            type = WireCodec.decodeType(msg.payloadCase).lowercase()
        )
        messagesDomain.add(sourceUserId, msgData)
        refreshConversation(sourceUserId)
    }

    /**
     * DEVICE_ANNOUNCE — learn a peer's device list, and hold them to a pinned recovery key.
     *
     * **This used to verify `announce.signature` against `announce.recoveryPublicKey`** — field 5 of
     * the same peer-supplied message. That authenticated nothing whatsoever: generate a keypair,
     * sign anything, ship both halves, pass. And the `&&` made it skippable, so omitting the key ran
     * no verification at all.
     *
     * `client.proto` calls field 5 the key "for friend to verify FUTURE revocation signatures",
     * which is the whole design: it is LEARNED once (trust-on-first-use) and used to check later
     * announces. So the key is pinned in `Friend.recovery_public_key` on the first announce that
     * carries one, and every signature after that is checked against the STORED key, never the
     * offered one.
     */
    internal suspend fun handleDeviceAnnounce(msg: ClientMessage, sourceUserId: String) {
        val announce = msg.deviceAnnounce
        val offered = announce.recoveryPublicKey.toByteArray()
        val pinned = friends.get(sourceUserId)?.recoveryPublicKey

        val trusted = when {
            pinned != null -> {
                if (offered.isNotEmpty() && !offered.contentEquals(pinned)) {
                    // A peer rotating its recovery key mid-stream is indistinguishable from an
                    // attacker replacing it, so this is refused rather than resolved. Re-pinning is
                    // a re-friend, not a message.
                    logger.signatureVerificationFailed(sourceUserId, "DEVICE_ANNOUNCE")
                    log("DEVICE_ANNOUNCE rejected: offered recovery key differs from the pinned one")
                    return
                }
                pinned
            }
            offered.isNotEmpty() -> {
                friends.pinRecoveryPublicKey(sourceUserId, offered)
                offered
            }
            else -> null
        }

        // A revocation is the announce that needs the key — it is how a peer says "these devices of
        // mine are gone", and it is exactly what a compromised device would forge. So it must carry
        // a signature verified against the pin. An ordinary (non-revocation) announce is just a
        // device-list refresh from an already-Signal-authenticated user and stays unsigned, which is
        // what `DeviceManager.announceDevices` sends.
        if (announce.isRevocation || announce.signature.size() > 0) {
            if (trusted == null || announce.signature.size() == 0) {
                logger.signatureVerificationFailed(sourceUserId, "DEVICE_ANNOUNCE")
                log("DEVICE_ANNOUNCE rejected: revocation/signed announce with no key pinned to check it against")
                return
            }
            val payload = com.obscura.kit.crypto.RecoveryKeys.serializeAnnounceForSigning(
                announce.devicesList.map { it.deviceId }, announce.timestamp, announce.isRevocation
            )
            val ok = try {
                Curve.verifySignature(Curve.decodePoint(trusted, 0), payload, announce.signature.toByteArray())
            } catch (e: Exception) {
                log("DEVICE_ANNOUNCE signature verify error: ${e.message}")
                false
            }
            if (!ok) {
                logger.signatureVerificationFailed(sourceUserId, "DEVICE_ANNOUNCE")
                log("DEVICE_ANNOUNCE rejected: signature does not verify under the pinned recovery key")
                return
            }
        }

        friends.updateDevices(sourceUserId, announce.devicesList.map { d ->
            FriendDeviceInfo(d.deviceUuid, d.deviceId, d.deviceName)
        })
    }

    internal suspend fun handleModelSignal(msg: ClientMessage, sourceUserId: String, senderDeviceId: String?) {
        try {
            val sig = msg.modelSignal
            if (sig.model.isBlank()) return

            val signalName = WireCodec.decodeSignalKind(sig.kind)
                ?: return // unknown/unspecified — ignore

            // The SEND side already fails CLOSED on a contextId that does not name exactly two
            // participants (see `signalManager.sendSignal`), for the leak fixed on 2026-07-25. The
            // RECEIVE side applied no check at all, so a peer could put any string here — including
            // a conversation id it is not part of — and have a typing indicator appear in it.
            // `observeTyping` keys on the contextId verbatim, so that is a real UI write.
            //
            // The audience of a two-party signal is derivable, so derive it: the id must split into
            // exactly two non-empty participants, and the AUTHENTICATED sender must be one of them.
            val participants = sig.contextId.split("_").filter { it.isNotBlank() }
            if (participants.size != 2 || sourceUserId !in participants) {
                log("SIGNAL DROPPED (inbound): contextId=\"${sig.contextId}\" is not a two-party id " +
                    "containing the sender ${sourceUserId.take(8)}")
                logger.log("inbound model signal dropped: contextId does not name the authenticated sender")
                return
            }

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

    private suspend fun handleSyncBlob(msg: ClientMessage, sourceUserId: String) {
        if (sourceUserId != userId) return
        clientSyncManager.processSyncBlob(msg)
    }

    /**
     * SENT_SYNC — an echo of a message THIS user sent from another of their own devices.
     *
     * The `sourceUserId != userId` guard is the whole point and it was missing. Friendship is not
     * required to deliver a message, so without it ANY account could send a SentSync and have this
     * write a row with an attacker-chosen conversationId, content, timestamp and messageId, stamped
     * with OUR device id — rendering as a message we sent. `Message.sq`'s INSERT OR REPLACE keys on
     * `messageId`, so a chosen id also overwrites a real message. Both siblings
     * ([handleSyncBlob], and the deleted friend-sync arm) opened with this line; this one did not.
     * ObscuraKit-swift has always had it (`case .sentSync?: guard sourceUserId == self.userId`).
     */
    internal suspend fun handleSentSync(msg: ClientMessage, sourceUserId: String) {
        if (sourceUserId != userId) return
        val ss = msg.sentSync
        messagesDomain.add(ss.recipientUsername, MessageData(
            id = ss.messageId, conversationId = ss.recipientUsername,
            authorDeviceId = deviceId ?: "self", content = String(ss.content.toByteArray()),
            timestamp = ss.timestamp, type = "text"
        ))
        refreshConversation(ss.recipientUsername)
    }

    // THE FRIEND_SYNC ARM IS DELETED, sender and receiver, and it was writing corrupt rows.
    //
    // `FriendSync` in client.proto has no `user_id` field, so `handleFriendSync` had nothing to key
    // the friend record on but `sourceUserId` — which its own `sourceUserId != userId` guard had
    // just proven is OUR OWN user id. Every befriend/acceptFriend on a multi-device account
    // therefore wrote, on the user's OTHER device, a Friend row with `user_id = <own userId>`: the
    // user in their own friends list, in `getAccepted()`, and so in every fan-out.
    //
    // FUNCTIONAL CONSEQUENCE, recorded because it is a real loss and not merely a deletion: a second
    // device no longer learns about friends added after it was linked. DEVICE_LINK_APPROVAL still
    // carries the full friends export at link time (`handleLinkApproval` -> `friends.importAll`), so
    // a freshly linked device starts correct; it just does not stay in step. obscura-pix never
    // referenced FRIEND_SYNC, so nothing observable to the app changes today.
    //
    // The proto field is NOT removed — the arm stays declared and is now classified UNIMPLEMENTED,
    // so an inbound FriendSync is dropped and acked loudly rather than acted on.

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

    // `send(friendUsername, text)` is gone (HISTORY.md: "legacy TEXT path", "sendText"). It resolved
    // a friend from a USERNAME and created an ORM entry — the kit deciding an audience from an
    // application concept, which SPEC §0.4 forbids. The replacement is `send(recipientUserIds, ...)`
    // above, with obscura-pix naming the recipients.

    suspend fun sendAttachment(friendUsername: String, attachmentId: String, contentKey: ByteArray, nonce: ByteArray, mimeType: String, sizeBytes: Long) =
        messagingManager.sendAttachment(friendUsername, attachmentId, contentKey, nonce, mimeType, sizeBytes)
    suspend fun sendEncryptedAttachment(friendUsername: String, plaintext: ByteArray, mimeType: String = "application/octet-stream") =
        messagingManager.sendEncryptedAttachment(friendUsername, plaintext, mimeType)
    suspend fun sendModelSync(friendUsername: String, model: String, entryId: String, op: String = "CREATE", data: Map<String, Any?>) =
        messagingManager.sendModelSync(friendUsername, model, entryId, op, data)
    /**
     * Send an application entry (`obscura-proto/KIT_API.md` §5) — the outbox half of the thin kit,
     * paired with [inbox] on the receive side and [entries] for local storage.
     *
     * **The caller names the recipients.** The kit fans out to every device of every listed userId
     * plus this user's own *other* devices, and resolves no audience of its own (SPEC §0.4). The
     * sender receives no inbox row, so the app writes its own outgoing entry to [entries].
     *
     * Prefer this over `sendModelSync`, which takes a `friendUsername` and looks it up — that is the
     * kit deciding an audience from an application concept, which SPEC §0.4 forbids.
     */
    suspend fun send(
        recipientUserIds: List<String>,
        modelKey: String,
        entryId: String,
        op: String = "CREATE",
        sentAt: Long = System.currentTimeMillis(),
        payload: ByteArray,
    ) = messagingManager.sendEntry(recipientUserIds, modelKey, entryId, op, sentAt, payload)

    // ── Ephemeral signals (typing, read receipts) ────────────────────────────────────────────
    //
    // `HISTORY.md` KEEPS ephemeral signals while deleting the ORM around them, and `SignalManager` was
    // always keep-forever code that merely happened to live in `orm/` (it is in `wire/` now). These
    // three methods are the door that let it stay after the package went.
    //
    // `modelKey` is opaque, exactly as it is on the inbox and the entry store: it names the app's
    // conversation namespace and the kit neither parses nor validates it.

    /**
     * Announce that this user is typing in a conversation.
     *
     * Throttled by `SignalManager` to at most once every 2s. Delivered to the conversation's
     * participants only — never broadcast; the audience comes from the canonical two-party
     * `conversationId`, and a value that does not name exactly two participants is DROPPED rather
     * than widened (the leak fixed on 2026-07-25).
     */
    suspend fun sendTyping(modelKey: String, conversationId: String) =
        signalManager.emit(
            modelKey, "typing",
            mapOf("conversationId" to conversationId, "senderUsername" to (username ?: "")),
            deviceId ?: "",
        )

    /** Explicitly stop typing. */
    suspend fun stopTyping(modelKey: String, conversationId: String) =
        signalManager.emit(
            modelKey, "stoppedTyping",
            mapOf("conversationId" to conversationId, "senderUsername" to (username ?: "")),
            deviceId ?: "",
        )

    /**
     * Who is currently typing in a conversation, by display name.
     *
     * Auto-expires; a signal with no refresh disappears on its own, which is what makes signals
     * droppable (`KIT_API.md` §4) rather than something the inbox has to carry.
     */
    fun observeTyping(modelKey: String, conversationId: String): kotlinx.coroutines.flow.Flow<List<String>> =
        signalManager.observe(modelKey, "typing", conversationId)

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
    suspend fun pushHistoryToDevice(targetDeviceId: String) = clientSyncManager.pushHistoryToDevice(targetDeviceId)
}
