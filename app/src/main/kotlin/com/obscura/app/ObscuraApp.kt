package com.obscura.app

import android.app.Application
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.db.ObscuraDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class ObscuraApp : Application() {

    var client: ObscuraClient? = null
    lateinit var securePrefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")

        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        securePrefs = EncryptedSharedPreferences.create(
            "obscura_secure_prefs", masterKey, applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        // Restore saved session if exists
        val savedUsername = securePrefs.getString("username", null)
        if (savedUsername != null) {
            val c = createClient(savedUsername)
            val token = securePrefs.getString("token", null)
            val userId = securePrefs.getString("userId", null)
            if (token != null && userId != null) {
                c.restoreSession(
                    token = token,
                    refreshToken = securePrefs.getString("refreshToken", null),
                    userId = userId,
                    deviceId = securePrefs.getString("deviceId", null),
                    username = savedUsername,
                    registrationId = securePrefs.getInt("registrationId", 0)
                )
            }
            client = c
        }
    }

    fun createClient(username: String): ObscuraClient {
        val dbSecret = DatabaseSecretProvider.getOrCreate(applicationContext, username)
        val factory = SupportOpenHelperFactory(dbSecret, null, false)
        val driver = AndroidSqliteDriver(
            schema = ObscuraDatabase.Schema,
            context = applicationContext,
            name = "obscura_$username.db",
            factory = factory
        )
        return ObscuraClient(ObscuraConfig(apiUrl = "https://obscura.barrelmaker.dev"), externalDriver = driver)
    }

    fun saveSession() {
        val c = client ?: return
        securePrefs.edit()
            .putString("username", c.username)
            .putString("token", c.token)
            .putString("refreshToken", c.refreshToken)
            .putString("userId", c.userId)
            .putString("deviceId", c.deviceId)
            .putInt("registrationId", c.registrationId)
            .apply()
    }

    fun clearSession() {
        client = null
        securePrefs.edit().clear().apply()
    }
}
