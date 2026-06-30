package com.obscura.kit.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BIP39 is the foundation of the recovery system. Wire-format compatibility
 * with the standard wordlist + checksum scheme is non-negotiable — a user
 * whose phone was wiped should be able to type their 12 words into any
 * BIP39-compliant tool and recover the same seed. These tests pin that.
 */
class Bip39Test {

    @Test
    fun `generateMnemonic produces 12 distinct-format words`() {
        val mnemonic = Bip39.generateMnemonic()
        val words = mnemonic.split(" ")
        assertEquals(12, words.size, "BIP39 with 128-bit entropy must yield 12 words")
        assertTrue(words.all { it.matches(Regex("[a-z]+")) }, "All words must be lowercase ASCII")
        assertTrue(Bip39.validateMnemonic(mnemonic), "Generated mnemonic must self-validate")
    }

    @Test
    fun `generateMnemonic is non-deterministic (uses CSPRNG)`() {
        val a = Bip39.generateMnemonic()
        val b = Bip39.generateMnemonic()
        assertNotEquals(a, b, "Two fresh mnemonics must differ — if equal, CSPRNG seeding is broken")
    }

    @Test
    fun `entropyToMnemonic is deterministic for fixed entropy`() {
        val entropy = ByteArray(16) { it.toByte() } // 00 01 02 ... 0F
        val a = Bip39.entropyToMnemonic(entropy)
        val b = Bip39.entropyToMnemonic(entropy)
        assertEquals(a, b)
        assertEquals(12, a.split(" ").size)
    }

    @Test
    fun `entropyToMnemonic rejects wrong-sized entropy`() {
        assertThrows(IllegalArgumentException::class.java) {
            Bip39.entropyToMnemonic(ByteArray(15))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Bip39.entropyToMnemonic(ByteArray(17))
        }
    }

    @Test
    fun `validateMnemonic accepts standard BIP39 test vector`() {
        // Canonical BIP39 test vector: all-zero entropy -> "abandon" x11 + "about"
        // https://github.com/trezor/python-mnemonic/blob/master/vectors.json
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertTrue(Bip39.validateMnemonic(mnemonic))
    }

    @Test
    fun `validateMnemonic rejects bad checksum`() {
        // 12 valid words, but the last word is wrong so checksum fails.
        val bad = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"
        assertFalse(Bip39.validateMnemonic(bad))
    }

    @Test
    fun `validateMnemonic rejects unknown word`() {
        val bad = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon zzzz"
        assertFalse(Bip39.validateMnemonic(bad))
    }

    @Test
    fun `validateMnemonic rejects wrong word count`() {
        assertFalse(Bip39.validateMnemonic("abandon abandon abandon"))
        assertFalse(Bip39.validateMnemonic("abandon"))
        assertFalse(Bip39.validateMnemonic(""))
    }

    @Test
    fun `validateMnemonic is case-insensitive and whitespace-tolerant`() {
        val mnemonic = "  ABANDON  abandon\tabandon abandon abandon abandon abandon abandon abandon abandon abandon ABOUT  "
        assertTrue(Bip39.validateMnemonic(mnemonic))
    }

    @Test
    fun `mnemonicToSeed matches BIP39 canonical test vector`() {
        // Standard BIP39 test vector with passphrase "TREZOR":
        // mnemonic: "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        // expected seed (hex): c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = Bip39.mnemonicToSeed(mnemonic, "TREZOR")
        val expectedHex = "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04"
        assertEquals(expectedHex, seed.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `mnemonicToSeed without passphrase produces 64 bytes`() {
        val mnemonic = Bip39.generateMnemonic()
        val seed = Bip39.mnemonicToSeed(mnemonic)
        assertEquals(64, seed.size, "PBKDF2-HMAC-SHA512 must yield 64 bytes")
    }
}
