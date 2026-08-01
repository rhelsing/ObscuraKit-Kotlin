package com.obscura.kit

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * ObscuraConfig is the single source of truth for client-side env config.
 * The `init` validator is the only behavior, but it's load-bearing: every
 * caller hands it a URL string that must be HTTPS.
 */
class ObscuraConfigTest {

    @Test
    fun `valid https url is accepted`() {
        assertDoesNotThrow { ObscuraConfig(apiUrl = "https://obscura.example.com") }
    }

    @Test
    fun `http url is rejected`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ObscuraConfig(apiUrl = "http://obscura.example.com")
        }
        assertNotNull(ex.message)
        assertEquals(true, ex.message!!.contains("HTTPS"))
    }

    @Test
    fun `non-url scheme is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ObscuraConfig(apiUrl = "ws://example.com") }
        assertThrows(IllegalArgumentException::class.java) { ObscuraConfig(apiUrl = "file:///etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { ObscuraConfig(apiUrl = "") }
    }

    @Test
    fun `loopback http is accepted for local container use`() {
        assertDoesNotThrow { ObscuraConfig(apiUrl = "http://localhost:3000") }
        assertDoesNotThrow { ObscuraConfig(apiUrl = "http://127.0.0.1:3000") }
    }

    @Test
    fun `spoofed loopback hosts are rejected (no cleartext to a remote host)`() {
        // These all start with "http://localhost"/"http://127.0.0.1" but resolve
        // to a remote host — a prefix check would have let them tunnel the auth
        // token in cleartext. The host must be parsed and matched exactly.
        for (url in listOf(
            "http://localhost.evil.com",
            "http://localhost@evil.com",
            "http://127.0.0.1.evil.com",
            "http://127.0.0.1@evil.com/api"
        )) {
            assertThrows(IllegalArgumentException::class.java, { ObscuraConfig(apiUrl = url) }, url)
        }
    }

    @Test
    fun `defaults are sensible for tests`() {
        val c = ObscuraConfig(apiUrl = "https://x.test")
        assertEquals("Kotlin Client", c.deviceName)
        assertNull(c.databasePath, "databasePath null => in-memory sqlite, the safe default")
        assertEquals(500L, c.authRateLimitDelayMs)
        assertEquals(false, c.enableRecoveryPhrase, "Recovery phrase must be explicit opt-in")
    }
}
