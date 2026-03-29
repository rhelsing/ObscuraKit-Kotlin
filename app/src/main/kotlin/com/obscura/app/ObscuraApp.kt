package com.obscura.app

import android.app.Application
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.obscura.kit.AuthState
import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.db.ObscuraDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Application singleton — manages per-user ObscuraClient instances.
 *
 * Each username gets its own encrypted SQLite database (obscura_{username}.db)
 * with its own Keystore-sealed encryption key. Bob can't read Alice's DB.
 *
 * Flow:
 *   1. App opens → check for saved username in prefs
 *   2. If saved → openClientForUser(username) → restoreSession → connect
 *   3. If not → show login screen, wait for username
 *   4. On login/register → openClientForUser(username) → auth → save username
 *   5. On logout → disconnect, drop client, clear saved username
 */
class ObscuraApp : Application() {

    var client: ObscuraClient? = null
        private set

    private val _currentUsername = MutableStateFlow<String?>(null)
    val currentUsername: StateFlow<String?> = _currentUsername

    private lateinit var securePrefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var authObserverJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        securePrefs = EncryptedSharedPreferences.create(
            "obscura_secure_prefs",
            masterKey,
            applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        System.loadLibrary("sqlcipher")

        // Auto-restore last user if saved
        val savedUsername = securePrefs.getString("username", null)
        if (savedUsername != null) {
            openClientForUser(savedUsername)
            val savedToken = securePrefs.getString("token", null)
            val savedUserId = securePrefs.getString("userId", null)
            if (savedToken != null && savedUserId != null) {
                client!!.restoreSession(
                    token = savedToken,
                    refreshToken = securePrefs.getString("refreshToken", null),
                    userId = savedUserId,
                    deviceId = securePrefs.getString("deviceId", null),
                    username = savedUsername,
                    registrationId = securePrefs.getInt("registrationId", 0)
                )
                scope.launch {
                    try { client!!.connect() } catch (_: Exception) {}
                }
            }
        }
    }

    fun openClientForUser(username: String) {
        // Tear down previous client if switching users
        authObserverJob?.cancel()
        client?.disconnect()

        val dbSecret = DatabaseSecretProvider.getOrCreate(applicationContext, username)
        val factory = SupportOpenHelperFactory(dbSecret, null, false)
        val driver = AndroidSqliteDriver(
            schema = ObscuraDatabase.Schema,
            context = applicationContext,
            name = "obscura_${username}.db",
            factory = factory,
            callback = object : AndroidSqliteDriver.Callback(ObscuraDatabase.Schema) {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.query("PRAGMA cipher_default_kdf_iter = 1;").close()
                    db.query("PRAGMA cipher_default_page_size = 4096;").close()
                    super.onOpen(db)
                }
            }
        )

        client = ObscuraClient(
            config = ObscuraConfig(apiUrl = "https://obscura.barrelmaker.dev"),
            externalDriver = driver
        )

        _currentUsername.value = username

        // Auto-save session on auth state changes
        authObserverJob = scope.launch {
            client!!.authState.collectLatest { state ->
                if (state == AuthState.AUTHENTICATED) {
                    saveSession(username)
                }
            }
        }
    }

    private fun saveSession(username: String) {
        val c = client ?: return
        securePrefs.edit()
            .putString("username", username)
            .putString("token", c.token)
            .putString("refreshToken", c.refreshToken)
            .putString("userId", c.userId)
            .putString("deviceId", c.deviceId)
            .putInt("registrationId", c.registrationId)
            .apply()
    }

    fun clearSession() {
        authObserverJob?.cancel()
        client?.disconnect()
        client = null
        _currentUsername.value = null
        securePrefs.edit().clear().apply()
    }
}
