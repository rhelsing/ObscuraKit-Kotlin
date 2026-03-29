package com.obscura.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages the SQLCipher database encryption key.
 * Follows Signal Android's DatabaseSecretProvider pattern:
 *
 * 1. Generate a 32-byte random key (high entropy, not a passphrase)
 * 2. Seal it with Android Keystore (AES-256-GCM)
 * 3. Store the sealed blob in SharedPreferences
 * 4. On launch, unseal with Keystore and pass to SQLCipher
 *
 * Because the key is already 256 bits of entropy, SQLCipher KDF iterations
 * are set to 1 (same as Signal) to avoid unnecessary CPU cost.
 */
object DatabaseSecretProvider {

    private const val KEYSTORE_ALIAS_PREFIX = "ObscuraDB_"
    private const val PREFS_NAME = "obscura_db_secrets"

    /**
     * Per-user DB encryption key. Each username gets its own random key,
     * sealed with its own Keystore alias. Bob can't derive Alice's key.
     */
    fun getOrCreate(context: Context, username: String): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ivKey = "${username}_iv"
        val dataKey = "${username}_data"
        val sealedIv = prefs.getString(ivKey, null)
        val sealedData = prefs.getString(dataKey, null)

        if (sealedIv != null && sealedData != null) {
            return unseal(
                username,
                Base64.getDecoder().decode(sealedIv),
                Base64.getDecoder().decode(sealedData)
            )
        }

        val secret = ByteArray(32)
        SecureRandom().nextBytes(secret)

        val (iv, encrypted) = seal(username, secret)
        prefs.edit()
            .putString(ivKey, Base64.getEncoder().encodeToString(iv))
            .putString(dataKey, Base64.getEncoder().encodeToString(encrypted))
            .apply()

        return secret
    }

    private fun getOrCreateKeystoreKey(username: String): SecretKey {
        val alias = KEYSTORE_ALIAS_PREFIX + username
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        keyStore.getEntry(alias, null)?.let {
            return (it as KeyStore.SecretKeyEntry).secretKey
        }

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(spec)
        return generator.generateKey()
    }

    private fun seal(username: String, data: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey(username))
        return Pair(cipher.iv, cipher.doFinal(data))
    }

    private fun unseal(username: String, iv: ByteArray, encrypted: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(username), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }
}
