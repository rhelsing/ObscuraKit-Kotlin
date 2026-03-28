package com.obscura.kit.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val CSPRNG = SecureRandom()

/**
 * BIP39 mnemonic generation and seed derivation.
 * 128 bits entropy → 12 words. PBKDF2-HMAC-SHA512 for seed.
 */
object Bip39 {

    fun generateMnemonic(): String {
        val entropy = ByteArray(16)
        CSPRNG.nextBytes(entropy)
        return entropyToMnemonic(entropy)
    }

    fun entropyToMnemonic(entropy: ByteArray): String {
        require(entropy.size == 16) { "Entropy must be 16 bytes" }

        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val bits = bytesToBits(entropy) + bytesToBits(hash).take(4)

        return (0 until 12).map { i ->
            val chunk = bits.substring(i * 11, (i + 1) * 11)
            BIP39_WORDLIST[chunk.toInt(2)]
        }.joinToString(" ")
    }

    fun validateMnemonic(mnemonic: String): Boolean {
        val words = mnemonic.trim().lowercase().split(Regex("\\s+"))
        if (words.size != 12) return false

        val indices = words.map { BIP39_WORDLIST.indexOf(it) }
        if (indices.any { it == -1 }) return false

        val bits = indices.joinToString("") { it.toString(2).padStart(11, '0') }
        val entropyBits = bits.take(128)
        val checksumBits = bits.substring(128, 132)

        val entropy = ByteArray(16) { i ->
            entropyBits.substring(i * 8, (i + 1) * 8).toInt(2).toByte()
        }

        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val expectedChecksum = bytesToBits(hash).take(4)

        return checksumBits == expectedChecksum
    }

    fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val mnemonicChars = java.text.Normalizer.normalize(mnemonic, java.text.Normalizer.Form.NFKD).toCharArray()
        val salt = "mnemonic${java.text.Normalizer.normalize(passphrase, java.text.Normalizer.Form.NFKD)}".toByteArray()

        val spec = PBEKeySpec(mnemonicChars, salt, 2048, 512)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return factory.generateSecret(spec).encoded
    }

    private fun bytesToBits(bytes: ByteArray): String {
        return bytes.joinToString("") { (it.toInt() and 0xFF).toString(2).padStart(8, '0') }
    }
}
