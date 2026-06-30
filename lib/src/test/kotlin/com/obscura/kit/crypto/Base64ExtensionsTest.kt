package com.obscura.kit.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Pure-logic tests for Base64Extensions and UuidCodec.
 * No network, no DB. These exist to lock in wire-format invariants —
 * anything serialized to or from these helpers crosses the network or
 * shows up in QR codes, so breakages here cause downstream parser failures.
 */
class Base64ExtensionsTest {

    @Test
    fun `round-trips ASCII bytes`() {
        val input = "hello world".toByteArray()
        val encoded = input.toBase64()
        assertArrayEquals(input, encoded.fromBase64())
    }

    @Test
    fun `round-trips arbitrary binary including 0xFF and 0x00`() {
        val input = ByteArray(256) { it.toByte() }
        assertArrayEquals(input, input.toBase64().fromBase64())
    }

    @Test
    fun `empty array round-trips`() {
        assertEquals("", ByteArray(0).toBase64())
        assertArrayEquals(ByteArray(0), "".fromBase64())
    }

    @Test
    fun `standard encoder produces padding`() {
        // 1 byte -> 4 chars with 2 = padding (canonical RFC 4648)
        assertEquals("AQ==", byteArrayOf(0x01).toBase64())
    }

    @Test
    fun `url decoder accepts dash and underscore variants`() {
        // bytes that would encode to a string containing + and /
        val payload = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())
        val urlSafe = java.util.Base64.getUrlEncoder().encodeToString(payload)
        assertArrayEquals(payload, urlSafe.fromBase64Url())
    }

    @Test
    fun `fromBase64 on garbage throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            "this is not base64!".fromBase64()
        }
    }
}
