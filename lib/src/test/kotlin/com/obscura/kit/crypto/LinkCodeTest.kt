package com.obscura.kit.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * LinkCode is the device-linking handshake. A new device generates a code,
 * an existing device parses + validates it, then approves the link. Wire
 * format must be stable across releases (a code generated on v0.1 must be
 * parsable by v0.2), and the validation must reject all the failure modes
 * that matter for security: expired codes, wrong-sized challenges, future
 * timestamps (clock-skew or attack).
 */
class LinkCodeTest {

    private fun fakeIdentityKey() = ByteArray(33) { 0xAB.toByte() }

    @Test
    fun `generate produces parseable code with all fields populated`() {
        val key = fakeIdentityKey()
        val generated = LinkCode.generate("dev-1", "uuid-1", key)
        val parsed = LinkCode.parse(generated.code)

        assertEquals("dev-1", parsed.deviceId)
        assertEquals("uuid-1", parsed.deviceUUID)
        assertArrayEquals(key, parsed.signalIdentityKey)
        assertEquals(16, parsed.challenge.size, "Challenge must be 16 bytes")
        assertArrayEquals(generated.challenge, parsed.challenge,
            "Generator's challenge must match the one embedded in the code")
        assertTrue(parsed.timestamp > 0)
    }

    @Test
    fun `generate produces fresh challenge each call`() {
        val key = fakeIdentityKey()
        val a = LinkCode.generate("d", "u", key)
        val b = LinkCode.generate("d", "u", key)
        assertFalse(a.challenge.contentEquals(b.challenge),
            "Two generates must produce different challenges — otherwise replay attacks are possible")
        assertFalse(a.code == b.code)
    }

    @Test
    fun `validate accepts freshly-generated code`() {
        val result = LinkCode.validate(LinkCode.generate("d", "u", fakeIdentityKey()).code)
        assertTrue(result.valid, "Just-generated code must validate; got error=${result.error}")
        assertNull(result.error)
        assertNotNull(result.data)
    }

    @Test
    fun `validate rejects code older than default max age`() {
        // Hand-craft a code with a far-past timestamp so we don't race the
        // System.currentTimeMillis() tick between generate() and validate().
        val pastTs = System.currentTimeMillis() - 10 * 60 * 1000 // 10 minutes ago
        val json = org.json.JSONObject().apply {
            put("d", "dev")
            put("u", "uuid")
            put("k", java.util.Base64.getEncoder().encodeToString(fakeIdentityKey()))
            put("c", java.util.Base64.getEncoder().encodeToString(ByteArray(16)))
            put("t", pastTs)
        }
        val code = java.util.Base64.getEncoder().encodeToString(json.toString().toByteArray())
        val result = LinkCode.validate(code)
        assertFalse(result.valid)
        assertEquals("Link code expired", result.error)
    }

    @Test
    fun `validate rejects far-future timestamp (clock-skew protection)`() {
        // Hand-craft a code with timestamp 10 minutes in the future
        val futureTs = System.currentTimeMillis() + 600_000
        val json = org.json.JSONObject().apply {
            put("d", "dev")
            put("u", "uuid")
            put("k", java.util.Base64.getEncoder().encodeToString(fakeIdentityKey()))
            put("c", java.util.Base64.getEncoder().encodeToString(ByteArray(16)))
            put("t", futureTs)
        }
        val code = java.util.Base64.getEncoder().encodeToString(json.toString().toByteArray())

        val result = LinkCode.validate(code)
        assertFalse(result.valid)
        assertEquals("Link code timestamp is in the future", result.error,
            "Must reject far-future timestamps so a peer can't pre-mint codes that stay valid forever")
    }

    @Test
    fun `validate accepts small clock skew up to 60 seconds`() {
        // 30s into the future is within tolerance
        val nearFuture = System.currentTimeMillis() + 30_000
        val json = org.json.JSONObject().apply {
            put("d", "dev")
            put("u", "uuid")
            put("k", java.util.Base64.getEncoder().encodeToString(fakeIdentityKey()))
            put("c", java.util.Base64.getEncoder().encodeToString(ByteArray(16)))
            put("t", nearFuture)
        }
        val code = java.util.Base64.getEncoder().encodeToString(json.toString().toByteArray())
        val result = LinkCode.validate(code)
        assertTrue(result.valid, "30s clock skew must be tolerated; got ${result.error}")
    }

    @Test
    fun `validate rejects blank deviceId`() {
        val json = org.json.JSONObject().apply {
            put("d", "")
            put("u", "uuid")
            put("k", java.util.Base64.getEncoder().encodeToString(fakeIdentityKey()))
            put("c", java.util.Base64.getEncoder().encodeToString(ByteArray(16)))
            put("t", System.currentTimeMillis())
        }
        val code = java.util.Base64.getEncoder().encodeToString(json.toString().toByteArray())
        val result = LinkCode.validate(code)
        assertFalse(result.valid)
        assertEquals("Missing deviceId", result.error)
    }

    @Test
    fun `validate rejects empty signal identity key`() {
        val json = org.json.JSONObject().apply {
            put("d", "dev")
            put("u", "uuid")
            put("k", java.util.Base64.getEncoder().encodeToString(ByteArray(0)))
            put("c", java.util.Base64.getEncoder().encodeToString(ByteArray(16)))
            put("t", System.currentTimeMillis())
        }
        val code = java.util.Base64.getEncoder().encodeToString(json.toString().toByteArray())
        val result = LinkCode.validate(code)
        assertFalse(result.valid)
        assertEquals("Missing Signal identity key", result.error)
    }

    @Test
    fun `validate rejects wrong-sized challenge`() {
        val json = org.json.JSONObject().apply {
            put("d", "dev")
            put("u", "uuid")
            put("k", java.util.Base64.getEncoder().encodeToString(fakeIdentityKey()))
            put("c", java.util.Base64.getEncoder().encodeToString(ByteArray(8))) // wrong size
            put("t", System.currentTimeMillis())
        }
        val code = java.util.Base64.getEncoder().encodeToString(json.toString().toByteArray())
        val result = LinkCode.validate(code)
        assertFalse(result.valid)
        assertEquals("Invalid challenge size", result.error)
    }

    @Test
    fun `validate rejects garbage input gracefully`() {
        val result = LinkCode.validate("not a real link code")
        assertFalse(result.valid)
        assertTrue(result.error!!.startsWith("Could not parse"),
            "Bad input must return a structured failure, not throw")
    }

    @Test
    fun `verifyChallenge returns true for matching arrays`() {
        val challenge = ByteArray(16) { it.toByte() }
        assertTrue(LinkCode.verifyChallenge(challenge, challenge.copyOf()))
    }

    @Test
    fun `verifyChallenge returns false for mismatched arrays`() {
        assertFalse(LinkCode.verifyChallenge(ByteArray(16) { 1 }, ByteArray(16) { 2 }))
        assertFalse(LinkCode.verifyChallenge(ByteArray(16), ByteArray(15)),
            "Different sizes must compare false (length is part of identity)")
    }
}
