package com.obscura.kit.crypto

import java.security.MessageDigest

/**
 * Generate deterministic 4-digit verification codes from public keys.
 * SHA-256(key) → first 2 bytes as uint16 → mod 10000 → zero-padded.
 * Matches src/v2/crypto/signatures.js generateVerifyCode()
 */
object VerificationCode {

    /**
     * Generate a 4-digit code from any public key bytes.
     */
    fun fromKey(key: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(key)
        val code = ((hash[0].toInt() and 0xFF) shl 8 or (hash[1].toInt() and 0xFF)) % 10000
        return code.toString().padStart(4, '0')
    }

    /**
     * Generate code from a recovery public key (per-user, stable across devices).
     */
    fun fromRecoveryKey(recoveryPublicKey: ByteArray): String = fromKey(recoveryPublicKey)

    /**
     * Generate code from sorted device identity keys.
     * Concatenates all keys (sorted by deviceUUID) and hashes the result.
     * Changes if any device is added/removed.
     */
    fun fromDevices(devices: List<Pair<String, ByteArray>>): String {
        val sorted = devices.sortedBy { it.first }
        val concatenated = sorted.fold(ByteArray(0)) { acc, (_, key) -> acc + key }
        return fromKey(concatenated)
    }
}
