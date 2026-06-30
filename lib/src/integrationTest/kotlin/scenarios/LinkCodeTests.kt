package scenarios

import com.obscura.kit.crypto.LinkCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.signal.libsignal.protocol.IdentityKeyPair

/**
 * Tier 1: Link code unit tests — no server.
 *
 * Device linking is a Layer 2 constant. Every new device must go through
 * QR code or pasted code approval. These tests prove the code generation,
 * parsing, validation, and challenge verification are correct.
 */
class LinkCodeTests {

    private fun fakeIdentityKey(): ByteArray = IdentityKeyPair.generate().publicKey.serialize()

    @Test
    fun `Generate produces a base64 string and challenge`() {
        val generated = LinkCode.generate("device-1", "uuid-1", fakeIdentityKey())
        assertTrue(generated.code.isNotBlank())
        assertEquals(16, generated.challenge.size)
        assertDoesNotThrow { java.util.Base64.getDecoder().decode(generated.code) }
    }

    @Test
    fun `Parse round-trips correctly`() {
        val key = fakeIdentityKey()
        val generated = LinkCode.generate("device-1", "uuid-1", key)
        val parsed = LinkCode.parse(generated.code)

        assertEquals("device-1", parsed.deviceId)
        assertEquals("uuid-1", parsed.deviceUUID)
        assertArrayEquals(key, parsed.signalIdentityKey)
        assertEquals(16, parsed.challenge.size)
        assertTrue(parsed.timestamp > 0)
    }

    @Test
    fun `Generated challenge matches parsed challenge`() {
        val generated = LinkCode.generate("d1", "u1", fakeIdentityKey())
        val parsed = LinkCode.parse(generated.code)
        assertArrayEquals(generated.challenge, parsed.challenge,
            "Challenge from generate() must match challenge from parse()")
    }

    @Test
    fun `Validate accepts fresh code`() {
        val generated = LinkCode.generate("device-1", "uuid-1", fakeIdentityKey())
        val result = LinkCode.validate(generated.code)
        assertTrue(result.valid)
        assertNull(result.error)
        assertNotNull(result.data)
    }

    @Test
    fun `Validate rejects expired code`() {
        val generated = LinkCode.generate("device-1", "uuid-1", fakeIdentityKey())
        Thread.sleep(5)
        val result = LinkCode.validate(generated.code, maxAgeMs = 1)
        assertFalse(result.valid)
        assertEquals("Link code expired", result.error)
    }

    @Test
    fun `Validate rejects garbage input`() {
        val result = LinkCode.validate("not-a-valid-link-code!!!")
        assertFalse(result.valid)
        assertTrue(result.error!!.startsWith("Could not parse"))
    }

    @Test
    fun `Validate rejects empty deviceId`() {
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
    fun `Validate rejects wrong challenge size`() {
        val json = org.json.JSONObject().apply {
            put("d", "device-1")
            put("u", "uuid")
            put("k", java.util.Base64.getEncoder().encodeToString(fakeIdentityKey()))
            put("c", java.util.Base64.getEncoder().encodeToString(ByteArray(8)))
            put("t", System.currentTimeMillis())
        }
        val code = java.util.Base64.getEncoder().encodeToString(json.toString().toByteArray())
        val result = LinkCode.validate(code)
        assertFalse(result.valid)
        assertEquals("Invalid challenge size", result.error)
    }

    @Test
    fun `Each generate produces unique challenge`() {
        val key = fakeIdentityKey()
        val g1 = LinkCode.generate("d1", "u1", key)
        val g2 = LinkCode.generate("d1", "u1", key)
        assertFalse(g1.challenge.contentEquals(g2.challenge),
            "Each link code should have a unique random challenge")
    }

    // ─── Challenge verification ───────────────────────────────────

    @Test
    fun `verifyChallenge accepts matching bytes`() {
        val generated = LinkCode.generate("d1", "u1", fakeIdentityKey())
        assertTrue(LinkCode.verifyChallenge(generated.challenge, generated.challenge.clone()))
    }

    @Test
    fun `verifyChallenge rejects different bytes`() {
        val generated = LinkCode.generate("d1", "u1", fakeIdentityKey())
        val wrong = generated.challenge.clone()
        wrong[0] = (wrong[0].toInt() xor 0xFF).toByte()
        assertFalse(LinkCode.verifyChallenge(generated.challenge, wrong))
    }

    @Test
    fun `verifyChallenge rejects different lengths`() {
        assertFalse(LinkCode.verifyChallenge(ByteArray(16), ByteArray(8)))
    }
}
