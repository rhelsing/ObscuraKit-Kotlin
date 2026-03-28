package com.obscura.kit.crypto

import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey

/**
 * Recovery key operations: derive keypair from BIP39 phrase, sign, verify.
 * Uses libsignal's Curve25519 + XEdDSA (same as Signal Protocol).
 */
object RecoveryKeys {

    /**
     * Derive a Curve25519 keypair from a BIP39 recovery phrase.
     */
    fun deriveKeypair(phrase: String): ECKeyPair {
        require(Bip39.validateMnemonic(phrase)) { "Invalid recovery phrase" }
        val seed = Bip39.mnemonicToSeed(phrase)
        val privateKeyBytes = seed.copyOfRange(0, 32)
        val privateKey = Curve.decodePrivatePoint(privateKeyBytes)
        val publicKey = privateKey.publicKey()
        return ECKeyPair(publicKey, privateKey)
    }

    /**
     * Sign data with a recovery phrase (derives key, signs, discards private key).
     */
    fun signWithPhrase(phrase: String, data: ByteArray): ByteArray {
        val keypair = deriveKeypair(phrase)
        return Curve.calculateSignature(keypair.privateKey, data)
    }

    /**
     * Verify a signature against a recovery public key.
     */
    fun verify(publicKey: ECPublicKey, data: ByteArray, signature: ByteArray): Boolean {
        return Curve.verifySignature(publicKey, data, signature)
    }

    /**
     * Get the public key from a recovery phrase (for sharing/storage).
     */
    fun getPublicKey(phrase: String): ECPublicKey {
        return deriveKeypair(phrase).publicKey
    }

    /**
     * Generate a new recovery phrase.
     */
    fun generatePhrase(): String {
        return Bip39.generateMnemonic()
    }

    /**
     * Serialize announce data for signing (deterministic JSON).
     */
    fun serializeAnnounceForSigning(deviceIds: List<String>, timestamp: Long, isRevocation: Boolean): ByteArray {
        // Deterministic: sorted keys, sorted device list, no whitespace
        val sorted = deviceIds.sorted()
        val devicesArr = org.json.JSONArray(sorted.map { id ->
            org.json.JSONObject().apply { put("deviceId", id) }
        })
        val obj = org.json.JSONObject()
        obj.put("devices", devicesArr)
        obj.put("isRevocation", isRevocation)
        obj.put("timestamp", timestamp)
        // JSONObject sorts keys alphabetically by default in org.json
        return obj.toString().toByteArray()
    }
}
