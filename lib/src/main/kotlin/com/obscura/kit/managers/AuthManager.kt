package com.obscura.kit.managers

import com.obscura.kit.AuthState
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.ObscuraLogger
import com.obscura.kit.crypto.toBase64
import com.obscura.kit.managers.SignalKeyUtils.toApiJson
import com.obscura.kit.network.GatewayConnection
import com.obscura.kit.network.HttpException
import com.obscura.kit.network.LoginResult
import com.obscura.kit.network.LoginScenario
import com.obscura.kit.network.ProvisionDeviceRequest
import com.obscura.kit.stores.DeviceIdentityData
import kotlinx.coroutines.*

/**
 * Handles register, login, loginAndProvision, logout, session restore, and token refresh.
 */
internal class AuthManager(
    private val ctx: ClientContext,
    private val config: ObscuraConfig,
    private val gateway: GatewayConnection,
    private val scope: CoroutineScope,
    private val setAuthState: (AuthState) -> Unit,
    private val setDisconnected: () -> Unit,
    private val loggerProvider: () -> ObscuraLogger,
    private val onLogout: suspend () -> Unit,
    private val onWipeDevice: suspend () -> Unit
) {
    private val session get() = ctx.session
    private val api get() = ctx.api
    private val signalStore get() = ctx.signalStore
    private val messenger get() = ctx.messenger
    private val devices get() = ctx.devices
    private val refreshInProgress = java.util.concurrent.atomic.AtomicReference<Deferred<Boolean>?>(null)
    private var consecutiveRefreshFailures = 0
    private val MAX_REFRESH_FAILURES = 5
    var tokenRefreshJob: Job? = null

    suspend fun register(username: String, password: String) {
        session.username = username
        val (identityKeyPair, regId) = signalStore.generateIdentity()
        session.registrationId = regId
        val signedPreKey = SignalKeyUtils.generateSignedPreKey(signalStore, identityKeyPair, 1)
        val oneTimePreKeys = SignalKeyUtils.generateOneTimePreKeys(signalStore, 1, 100)
        val regResult = api.registerUser(username, password)
        api.token = regResult.token
        val provResult = api.provisionDevice(ProvisionDeviceRequest(
            name = config.deviceName,
            identityKey = identityKeyPair.publicKey.serialize().toBase64(),
            registrationId = regId,
            signedPreKey = signedPreKey.toApiJson(),
            oneTimePreKeys = oneTimePreKeys.toApiJson()
        ))
        val deviceToken = provResult.token
        api.token = deviceToken
        session.refreshToken = provResult.refreshToken
        session.userId = api.getUserId(deviceToken)
        session.deviceId = provResult.deviceId.ifEmpty { null } ?: api.getDeviceId(deviceToken)

        messenger.mapDevice(
            requireNotNull(session.deviceId) { "deviceId not set - register failed to provision device" },
            requireNotNull(session.userId) { "userId not set - register failed to resolve user" },
            regId
        )

        devices.storeIdentity(DeviceIdentityData(
            deviceId = requireNotNull(session.deviceId) { "deviceId not set - register failed to provision device" },
            userId = requireNotNull(session.userId) { "userId not set - register failed to resolve user" },
            username = username,
            token = deviceToken
        ))

        setAuthState(AuthState.AUTHENTICATED)
        delay(config.authRateLimitDelayMs)
    }

    /**
     * Login with scenario detection.
     * Returns LoginResult so the app knows what to show:
     * - EXISTING_DEVICE → authenticated, data preserved
     * - NEW_DEVICE → need to call loginAndProvision()
     * - DEVICE_MISMATCH → local device was revoked, need wipeDevice() + loginAndProvision()
     * - INVALID_CREDENTIALS → wrong password
     * - USER_NOT_FOUND → need to register()
     */
    suspend fun login(username: String, password: String): LoginResult {
        val identity = devices.getIdentity()

        // Try login with local deviceId if we have one
        if (identity?.deviceId != null) {
            try {
                val result = api.loginWithDevice(username, password, identity.deviceId)
                val token = result.token
                api.token = token
                session.refreshToken = result.refreshToken
                session.userId = api.getUserId(token)
                session.deviceId = result.deviceId ?: api.getDeviceId(token)
                session.username = username
                session.registrationId = signalStore.getLocalRegistrationId()

                devices.storeIdentity(identity.copy(token = token))
                messenger.mapDevice(session.deviceId!!, session.userId!!, session.registrationId)

                setAuthState(AuthState.AUTHENTICATED)
                delay(config.authRateLimitDelayMs)

                return LoginResult(
                    scenario = LoginScenario.EXISTING_DEVICE,
                    token = token,
                    refreshToken = result.refreshToken,
                    deviceId = session.deviceId,
                    userId = session.userId
                )
            } catch (e: HttpException) {
                // Device login failed — check why
                if (e.statusCode == 401 || e.statusCode == 403) {
                    // Could be wrong password OR device was revoked
                    // Try login without deviceId to distinguish
                } else if (e.statusCode == 404) {
                    return LoginResult(scenario = LoginScenario.USER_NOT_FOUND)
                } else {
                    throw e
                }
            }
        }

        // No local device, or device login failed — try without deviceId
        try {
            val result = api.loginWithDevice(username, password, null)
            // Login succeeded but we either had no local device or it was rejected
            val scenario = if (identity?.deviceId != null) {
                LoginScenario.DEVICE_MISMATCH // had local device but server rejected it
            } else {
                LoginScenario.NEW_DEVICE // no local device
            }

            return LoginResult(
                scenario = scenario,
                token = result.token,
                userId = api.getUserId(result.token)
            )
        } catch (e: HttpException) {
            return when (e.statusCode) {
                404 -> LoginResult(scenario = LoginScenario.USER_NOT_FOUND)
                401, 403 -> LoginResult(scenario = LoginScenario.INVALID_CREDENTIALS)
                else -> throw e
            }
        }
    }

    suspend fun loginAndProvision(username: String, password: String, deviceName: String = "Device 2") {
        session.username = username

        val loginResult = api.loginWithDevice(username, password, null)
        api.token = loginResult.token
        session.userId = api.getUserId(loginResult.token)

        val (identityKeyPair, regId) = signalStore.generateIdentity()
        session.registrationId = regId

        val signedPreKey = SignalKeyUtils.generateSignedPreKey(signalStore, identityKeyPair, 1)
        val oneTimePreKeys = SignalKeyUtils.generateOneTimePreKeys(signalStore, 1, 100)

        val provResult = api.provisionDevice(ProvisionDeviceRequest(
            name = deviceName,
            identityKey = identityKeyPair.publicKey.serialize().toBase64(),
            registrationId = regId,
            signedPreKey = signedPreKey.toApiJson(),
            oneTimePreKeys = oneTimePreKeys.toApiJson()
        ))

        val deviceToken = provResult.token
        api.token = deviceToken
        session.refreshToken = provResult.refreshToken
        session.deviceId = provResult.deviceId.ifEmpty { null } ?: api.getDeviceId(deviceToken)

        messenger.mapDevice(
            requireNotNull(session.deviceId) { "deviceId not set - loginAndProvision failed to provision device" },
            requireNotNull(session.userId) { "userId not set - loginAndProvision failed to resolve user" },
            regId
        )

        devices.storeIdentity(DeviceIdentityData(
            deviceId = requireNotNull(session.deviceId) { "deviceId not set - loginAndProvision failed to provision device" },
            userId = requireNotNull(session.userId) { "userId not set - loginAndProvision failed to resolve user" },
            username = username,
            token = deviceToken
        ))

        // Device is provisioned on the server but NOT approved by an existing device yet.
        // The app must call generateLinkCode(), display it, and wait for approval.
        setAuthState(AuthState.PENDING_APPROVAL)
        delay(config.authRateLimitDelayMs)
    }

    fun restoreSession(
        token: String,
        refreshToken: String?,
        userId: String,
        deviceId: String?,
        username: String?,
        registrationId: Int = 0
    ) {
        api.token = token
        session.refreshToken = refreshToken
        session.userId = userId
        session.deviceId = deviceId
        session.username = username
        session.registrationId = registrationId

        if (deviceId != null) {
            messenger.mapDevice(deviceId, userId, registrationId)
        }

        setAuthState(AuthState.AUTHENTICATED)
    }

    fun hasSession(): Boolean = api.token != null && session.userId != null

    suspend fun logout() {
        tokenRefreshJob?.cancel()
        onLogout()
        api.token = null
        session.userId = null
        session.deviceId = null
        session.username = null
        session.refreshToken = null
        setAuthState(AuthState.LOGGED_OUT)
    }

    suspend fun wipeDevice() {
        tokenRefreshJob?.cancel()
        onWipeDevice()
        api.token = null
        session.userId = null
        session.deviceId = null
        session.username = null
        session.refreshToken = null
        session.recoveryPhrase = null
        session.recoveryPublicKey = null
        setAuthState(AuthState.LOGGED_OUT)
    }

    fun startTokenRefresh() {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = scope.launch {
            while (isActive) {
                val delayMs = getTokenRefreshDelay()
                delay(delayMs)
                refreshTokens()
            }
        }
    }

    suspend fun refreshTokens(): Boolean {
        refreshInProgress.get()?.let { return it.await() }

        val deferred = scope.async {
            try {
                val rt = session.refreshToken ?: return@async false
                val result = api.refreshSession(rt)
                api.token = result.token
                session.refreshToken = result.refreshToken
                consecutiveRefreshFailures = 0
                true
            } catch (e: Exception) {
                consecutiveRefreshFailures++
                loggerProvider().tokenRefreshFailed(consecutiveRefreshFailures, e.message ?: "unknown")
                if (consecutiveRefreshFailures >= MAX_REFRESH_FAILURES) {
                    setDisconnected()
                }
                false
            }
        }

        refreshInProgress.set(deferred)
        try {
            return deferred.await()
        } finally {
            refreshInProgress.set(null)
        }
    }

    suspend fun ensureFreshToken(): Boolean {
        if (!isTokenExpired(60)) return true
        return refreshTokens()
    }

    private fun isTokenExpired(bufferSeconds: Long = 0): Boolean {
        val token = api.token ?: return true
        val payload = api.decodeToken(token) ?: return true
        val exp = payload.optLong("exp", 0)
        if (exp == 0L) return true
        val now = System.currentTimeMillis() / 1000
        return (exp - now) <= bufferSeconds
    }

    private fun getTokenRefreshDelay(): Long {
        val token = api.token ?: return 30_000
        val payload = api.decodeToken(token) ?: return 30_000
        val exp = payload.optLong("exp", 0)
        if (exp == 0L) return 30_000
        val now = System.currentTimeMillis() / 1000
        val ttl = exp - now
        if (ttl <= 0) return 5_000
        return (ttl * 800).coerceAtLeast(5_000)
    }
}
