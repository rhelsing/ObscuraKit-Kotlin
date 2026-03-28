package com.obscura.app

import android.app.Application
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.*

/**
 * Application singleton — owns ObscuraClient for the process lifetime.
 * Session credentials stored in EncryptedSharedPreferences (Android Keystore-backed).
 * Signal protocol state stored in SQLite database (survives restarts).
 */
class ObscuraApp : Application() {

    lateinit var client: ObscuraClient
        private set

    private lateinit var securePrefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Encrypted storage for auth credentials
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        securePrefs = EncryptedSharedPreferences.create(
            "obscura_secure_prefs",
            masterKey,
            applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        // Persistent SQLite database for Signal keys, messages, friends
        val driver = AndroidSqliteDriver(
            schema = ObscuraDatabase.Schema,
            context = applicationContext,
            name = "obscura.db"
        )

        client = ObscuraClient(
            config = ObscuraConfig(apiUrl = "https://obscura.barrelmaker.dev"),
            externalDriver = driver
        )

        // Restore session if saved
        val savedToken = securePrefs.getString("token", null)
        val savedUserId = securePrefs.getString("userId", null)

        if (savedToken != null && savedUserId != null) {
            client.restoreSession(
                token = savedToken,
                refreshToken = securePrefs.getString("refreshToken", null),
                userId = savedUserId,
                deviceId = securePrefs.getString("deviceId", null),
                username = securePrefs.getString("username", null),
                registrationId = securePrefs.getInt("registrationId", 0)
            )
            // Auto-reconnect WebSocket
            scope.launch {
                try { client.connect() } catch (_: Exception) {}
            }
        }
    }

    fun saveSession() {
        securePrefs.edit()
            .putString("token", client.token)
            .putString("refreshToken", client.refreshToken)
            .putString("userId", client.userId)
            .putString("deviceId", client.deviceId)
            .putString("username", client.username)
            .putInt("registrationId", client.registrationId)
            .apply()
    }

    fun clearSession() {
        securePrefs.edit().clear().apply()
    }
}
