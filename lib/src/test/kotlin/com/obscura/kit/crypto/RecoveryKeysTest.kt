package com.obscura.kit.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RecoveryKeys ties BIP39 -> Curve25519 keypair via libsignal. This is
 * the foundation of the "type your 12 words to restore" flow. Tests pin:
 *   - Same phrase -> same keypair (deterministic recovery)
 *   - Different phrases -> different keypairs
 *   - Sign + verify round-trips
 *   - Invalid phrase rejected (no silent fallback to empty/zero key)
 *   - Announce serialization is deterministic regardless of input order
 */
class RecoveryKeysTest {

    // A standard BIP39 test-vector phrase. Validates with the spec.
    private val canonicalPhrase =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun `deriveKeypair is deterministic for the same phrase`() {
        val kp1 = RecoveryKeys.deriveKeypair(canonicalPhrase)
        val kp2 = RecoveryKeys.deriveKeypair(canonicalPhrase)
        assertArrayEquals(kp1.publicKey.serialize(), kp2.publicKey.serialize(),
            "Same phrase must produce the same public key — recovery depends on it")
        assertArrayEquals(kp1.privateKey.serialize(), kp2.privateKey.serialize())
    }

    @Test
    fun `deriveKeypair from a different valid phrase yields a different key`() {
        val another = RecoveryKeys.generatePhrase()
        val a = RecoveryKeys.deriveKeypair(canonicalPhrase)
        val b = RecoveryKeys.deriveKeypair(another)
        assertFalse(a.publicKey.serialize().contentEquals(b.publicKey.serialize()),
            "Two distinct mnemonics must produce different keys")
    }

    @Test
    fun `deriveKeypair rejects an invalid phrase`() {
        // Wrong word count
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryKeys.deriveKeypair("hello world")
        }
        // Bad checksum (last word changed)
        val badChecksum = canonicalPhrase.replace(" about", " abandon")
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryKeys.deriveKeypair(badChecksum)
        }
    }

    @Test
    fun `signWithPhrase and verify round-trip`() {
        val data = "important message".toByteArray()
        val signature = RecoveryKeys.signWithPhrase(canonicalPhrase, data)
        val publicKey = RecoveryKeys.getPublicKey(canonicalPhrase)
        assertTrue(RecoveryKeys.verify(publicKey, data, signature))
    }

    @Test
    fun `verify rejects signature from a different key`() {
        val data = "payload".toByteArray()
        val signature = RecoveryKeys.signWithPhrase(canonicalPhrase, data)
        val otherKey = RecoveryKeys.getPublicKey(RecoveryKeys.generatePhrase())
        assertFalse(RecoveryKeys.verify(otherKey, data, signature),
            "Signature with one key must not verify against another's public key")
    }

    @Test
    fun `verify rejects tampered data`() {
        val data = "payload".toByteArray()
        val signature = RecoveryKeys.signWithPhrase(canonicalPhrase, data)
        val publicKey = RecoveryKeys.getPublicKey(canonicalPhrase)
        val tampered = data.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(RecoveryKeys.verify(publicKey, tampered, signature))
    }

    @Test
    fun `generatePhrase produces a valid 12-word phrase`() {
        val phrase = RecoveryKeys.generatePhrase()
        assertEquals(12, phrase.split(" ").size)
        assertTrue(Bip39.validateMnemonic(phrase))
        // And derivation should work end-to-end on a freshly-generated one
        assertDoesNotThrow { RecoveryKeys.deriveKeypair(phrase) }
    }

    @Test
    fun `serializeAnnounceForSigning is deterministic regardless of device order`() {
        val ts = 1_700_000_000_000L
        val a = RecoveryKeys.serializeAnnounceForSigning(
            deviceIds = listOf("dev-c", "dev-a", "dev-b"),
            timestamp = ts,
            isRevocation = false
        )
        val b = RecoveryKeys.serializeAnnounceForSigning(
            deviceIds = listOf("dev-b", "dev-c", "dev-a"),
            timestamp = ts,
            isRevocation = false
        )
        assertArrayEquals(a, b,
            "Device ordering must not affect the bytes the signature commits to — " +
            "otherwise reorder-sensitive verification would reject legitimate announces")
    }

    @Test
    fun `serializeAnnounceForSigning differs when revocation flag flips`() {
        val ts = 1_700_000_000_000L
        val add = RecoveryKeys.serializeAnnounceForSigning(listOf("a"), ts, false)
        val rev = RecoveryKeys.serializeAnnounceForSigning(listOf("a"), ts, true)
        assertNotEquals(add.toList(), rev.toList(),
            "Add vs revocation must produce distinct signatures so the server can't replay one as the other")
    }
}
