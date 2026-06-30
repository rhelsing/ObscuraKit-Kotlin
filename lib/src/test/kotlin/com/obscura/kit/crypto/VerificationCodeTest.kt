package com.obscura.kit.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * VerificationCode generates the 4-digit display number that users compare
 * during friend verification ("does my screen show 1234?"). It must be:
 *   - Deterministic from a public key (otherwise verification is meaningless)
 *   - Stable across releases (changing the formula renders all prior
 *     verifications inconclusive)
 *   - 4 digits, zero-padded
 *   - The same value regardless of device ordering in fromDevices()
 */
class VerificationCodeTest {

    @Test
    fun `fromKey is deterministic`() {
        val key = ByteArray(32) { it.toByte() }
        val a = VerificationCode.fromKey(key)
        val b = VerificationCode.fromKey(key)
        assertEquals(a, b, "Two calls with the same key must produce the same code — verification depends on it")
    }

    @Test
    fun `fromKey returns 4 digits zero-padded`() {
        // Loop over a wide spread of inputs to make sure padding holds.
        repeat(50) { seed ->
            val key = ByteArray(32) { (seed * it).toByte() }
            val code = VerificationCode.fromKey(key)
            assertEquals(4, code.length, "Code must be exactly 4 chars, got '$code'")
            assertEquals(true, code.all { it.isDigit() }, "Code must be all digits, got '$code'")
        }
    }

    @Test
    fun `fromKey wire format pinned for canonical input`() {
        // Lock the formula: first two bytes of SHA-256(key) treated as
        // big-endian uint16, mod 10000. If this changes, every prior
        // verification anyone has done becomes wrong silently.
        // All-zero 32-byte key -> SHA-256 = 66687aadf862bd776c8fc18b8e9f8e20
        // -> 0x66 0x68 = 26216 -> mod 10000 = 6216
        val zeroKey = ByteArray(32)
        assertEquals("6216", VerificationCode.fromKey(zeroKey),
            "Canonical SHA-256(all-zero-32) -> 6216. If this fails, the formula has drifted " +
            "and prior user verifications are invalidated.")
    }

    @Test
    fun `fromRecoveryKey is alias of fromKey`() {
        val key = ByteArray(32) { it.toByte() }
        assertEquals(VerificationCode.fromKey(key), VerificationCode.fromRecoveryKey(key))
    }

    @Test
    fun `fromDevices is order-independent (sorted by deviceUUID)`() {
        val k1 = ByteArray(33) { 0x01 }
        val k2 = ByteArray(33) { 0x02 }
        val k3 = ByteArray(33) { 0x03 }

        val orderA = listOf("uuid-a" to k1, "uuid-b" to k2, "uuid-c" to k3)
        val orderB = listOf("uuid-c" to k3, "uuid-a" to k1, "uuid-b" to k2)
        val orderC = listOf("uuid-b" to k2, "uuid-c" to k3, "uuid-a" to k1)

        val a = VerificationCode.fromDevices(orderA)
        val b = VerificationCode.fromDevices(orderB)
        val c = VerificationCode.fromDevices(orderC)
        assertEquals(a, b, "Device sort order must not affect code")
        assertEquals(b, c)
    }

    @Test
    fun `fromDevices changes when a device is added or removed`() {
        val k1 = ByteArray(33) { 0x01 }
        val k2 = ByteArray(33) { 0x02 }
        val one = VerificationCode.fromDevices(listOf("u1" to k1))
        val two = VerificationCode.fromDevices(listOf("u1" to k1, "u2" to k2))
        assertNotEquals(one, two,
            "Adding a device must change the code so users notice device-list drift")
    }

    @Test
    fun `fromDevices on empty list still returns 4 digits`() {
        val code = VerificationCode.fromDevices(emptyList())
        assertEquals(4, code.length)
    }
}
