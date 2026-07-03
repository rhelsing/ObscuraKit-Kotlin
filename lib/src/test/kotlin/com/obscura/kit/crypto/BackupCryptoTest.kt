package com.obscura.kit.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BackupCrypto implements ECDH(ephemeral, recovery) + HKDF + AES-256-GCM.
 * The public API is encrypt(plaintext, recoveryPublicKey) and
 * decrypt(blob, recoveryPhrase).
 *
 * Tests pin: round-trip; version byte is checked; AEAD tag prevents
 * silent corruption; wrong phrase fails loudly; tampering invalidates.
 */
class BackupCryptoTest {

    private val canonicalPhrase =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun `encrypt then decrypt round-trips with phrase`() {
        val publicKey = RecoveryKeys.getPublicKey(canonicalPhrase)
        val plaintext = "user backup data".toByteArray()

        val blob = BackupCrypto.encrypt(plaintext, publicKey)
        val decoded = BackupCrypto.decrypt(blob, canonicalPhrase)

        assertArrayEquals(plaintext, decoded)
    }

    @Test
    fun `encrypt then decrypt round-trips with raw private key`() {
        val kp = RecoveryKeys.deriveKeypair(canonicalPhrase)
        val plaintext = "secret".toByteArray()

        val blob = BackupCrypto.encrypt(plaintext, kp.publicKey)
        val decoded = BackupCrypto.decryptWithPrivateKey(blob, kp.privateKey)

        assertArrayEquals(plaintext, decoded)
    }

    @Test
    fun `encrypt produces fresh ephemeral key each call`() {
        val publicKey = RecoveryKeys.getPublicKey(canonicalPhrase)
        val a = BackupCrypto.encrypt("data".toByteArray(), publicKey)
        val b = BackupCrypto.encrypt("data".toByteArray(), publicKey)
        assertFalse(a.contentEquals(b),
            "Two encrypts of identical plaintext must differ — fresh ephemeral keys are the whole point")
    }

    @Test
    fun `blob version byte is 1`() {
        val publicKey = RecoveryKeys.getPublicKey(canonicalPhrase)
        val blob = BackupCrypto.encrypt(byteArrayOf(0x01, 0x02), publicKey)
        assertTrue(blob.isNotEmpty())
        assertEquals(1.toByte(), blob[0], "Version byte must be 0x01 for v1 format")
    }

    @Test
    fun `decrypt rejects unsupported version`() {
        val publicKey = RecoveryKeys.getPublicKey(canonicalPhrase)
        val blob = BackupCrypto.encrypt(byteArrayOf(0x01), publicKey)
        blob[0] = 0x99.toByte() // pretend it's some future version

        val ex = assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(blob, canonicalPhrase)
        }
        assertTrue(ex.message!!.contains("Unsupported backup version"),
            "Wrong version must reject explicitly so the user knows the file is from a newer release")
    }

    @Test
    fun `decrypt with wrong phrase throws`() {
        val publicKey = RecoveryKeys.getPublicKey(canonicalPhrase)
        val blob = BackupCrypto.encrypt("data".toByteArray(), publicKey)
        val wrongPhrase = RecoveryKeys.generatePhrase()
        assertThrows(Exception::class.java) {
            BackupCrypto.decrypt(blob, wrongPhrase)
        }
    }

    @Test
    fun `decrypt of tampered ciphertext throws (AEAD enforced)`() {
        val publicKey = RecoveryKeys.getPublicKey(canonicalPhrase)
        val blob = BackupCrypto.encrypt("data".toByteArray(), publicKey)
        // Header is version(1) + pub(32) + salt(32) + iv(12) = 77 bytes;
        // flip a bit in the ciphertext body.
        blob[80] = (blob[80].toInt() xor 0x01).toByte()
        assertThrows(Exception::class.java) {
            BackupCrypto.decrypt(blob, canonicalPhrase)
        }
    }

    @Test
    fun `decrypt of too-short blob rejects with require message`() {
        // Below header size means we can't even parse; must reject loudly
        // instead of silently producing empty plaintext.
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(ByteArray(50), canonicalPhrase)
        }
    }

    @Test
    fun `blob from one phrase cannot be decrypted by another`() {
        val alicePub = RecoveryKeys.getPublicKey(canonicalPhrase)
        val bobPhrase = RecoveryKeys.generatePhrase()

        val blob = BackupCrypto.encrypt("alice's data".toByteArray(), alicePub)
        assertThrows(Exception::class.java) {
            BackupCrypto.decrypt(blob, bobPhrase)
        }
    }

    @Test
    fun `large payload round-trips correctly`() {
        // Some backup data could include image attachments.
        val publicKey = RecoveryKeys.getPublicKey(canonicalPhrase)
        val large = ByteArray(64 * 1024) { (it % 256).toByte() }
        val blob = BackupCrypto.encrypt(large, publicKey)
        assertArrayEquals(large, BackupCrypto.decrypt(blob, canonicalPhrase))
    }

    // Helper because assertEquals on bytes is verbose
    private fun assertEquals(expected: Byte, actual: Byte, message: String) {
        if (expected != actual) {
            throw AssertionError("$message; expected 0x%02X but was 0x%02X".format(expected, actual))
        }
    }
}
