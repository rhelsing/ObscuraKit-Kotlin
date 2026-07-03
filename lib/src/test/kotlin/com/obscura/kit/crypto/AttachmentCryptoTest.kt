package com.obscura.kit.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AttachmentCrypto is AES-256-GCM over JCE. These tests pin the
 * encrypt -> decrypt round-trip and verify the authentication tag is
 * actually enforced — without AEAD checks, a tampered attachment would
 * decrypt to garbage that the app might still render.
 */
class AttachmentCryptoTest {

    @Test
    fun `encrypt decrypt round-trips arbitrary bytes`() {
        val plaintext = ByteArray(4096) { (it % 256).toByte() }
        val enc = AttachmentCrypto.encrypt(plaintext)

        val decoded = AttachmentCrypto.decrypt(enc.ciphertext, enc.contentKey, enc.nonce, enc.contentHash)
        assertArrayEquals(plaintext, decoded)
    }

    @Test
    fun `encrypt produces 32-byte key and 12-byte nonce`() {
        val enc = AttachmentCrypto.encrypt(byteArrayOf(1, 2, 3))
        assertEquals(32, enc.contentKey.size, "AES-256 key must be 32 bytes")
        assertEquals(12, enc.nonce.size, "GCM standard nonce is 12 bytes")
        assertEquals(32, enc.contentHash.size, "SHA-256 hash is 32 bytes")
        assertEquals(3L, enc.sizeBytes)
    }

    @Test
    fun `encrypt produces fresh key and nonce each call (CSPRNG)`() {
        // If two calls produce identical key+nonce, the CSPRNG is broken
        // and any attacker who recovered one key would decrypt every
        // future attachment.
        val a = AttachmentCrypto.encrypt("hi".toByteArray())
        val b = AttachmentCrypto.encrypt("hi".toByteArray())
        assertNotEquals(a.contentKey.toList(), b.contentKey.toList())
        assertNotEquals(a.nonce.toList(), b.nonce.toList())
        assertNotEquals(a.ciphertext.toList(), b.ciphertext.toList())
    }

    @Test
    fun `decrypt with wrong key throws`() {
        val enc = AttachmentCrypto.encrypt("secret".toByteArray())
        val wrongKey = ByteArray(32) { 0x42 }
        assertThrows(Exception::class.java) {
            AttachmentCrypto.decrypt(enc.ciphertext, wrongKey, enc.nonce)
        }
    }

    @Test
    fun `decrypt with wrong nonce throws`() {
        val enc = AttachmentCrypto.encrypt("secret".toByteArray())
        val wrongNonce = ByteArray(12) { 0x01 }
        assertThrows(Exception::class.java) {
            AttachmentCrypto.decrypt(enc.ciphertext, enc.contentKey, wrongNonce)
        }
    }

    @Test
    fun `decrypt of tampered ciphertext throws (AEAD check)`() {
        val enc = AttachmentCrypto.encrypt("important".toByteArray())
        // Flip one bit in the ciphertext middle (skip the auth tag area).
        val tampered = enc.ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertThrows(Exception::class.java) {
            AttachmentCrypto.decrypt(tampered, enc.contentKey, enc.nonce)
        }
    }

    @Test
    fun `decrypt with mismatched expected hash throws SecurityException`() {
        val enc = AttachmentCrypto.encrypt("payload".toByteArray())
        val wrongHash = ByteArray(32) { 0xCC.toByte() }
        val ex = assertThrows(SecurityException::class.java) {
            AttachmentCrypto.decrypt(enc.ciphertext, enc.contentKey, enc.nonce, wrongHash)
        }
        assertTrue(ex.message!!.contains("integrity"),
            "Error message must clearly indicate integrity failure for forensic logs")
    }

    @Test
    fun `decrypt with correct hash succeeds`() {
        val enc = AttachmentCrypto.encrypt("payload".toByteArray())
        val decoded = AttachmentCrypto.decrypt(enc.ciphertext, enc.contentKey, enc.nonce, enc.contentHash)
        assertArrayEquals("payload".toByteArray(), decoded)
    }

    @Test
    fun `handles empty plaintext`() {
        val enc = AttachmentCrypto.encrypt(ByteArray(0))
        val decoded = AttachmentCrypto.decrypt(enc.ciphertext, enc.contentKey, enc.nonce, enc.contentHash)
        assertEquals(0, decoded.size)
    }
}
