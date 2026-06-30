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
    fun `defaults are sensible for tests`() {
        val c = ObscuraConfig(apiUrl = "https://x.test")
        assertEquals("Kotlin Client", c.deviceName)
        assertNull(c.databasePath, "databasePath null => in-memory sqlite, the safe default")
        assertEquals(500L, c.authRateLimitDelayMs)
        assertEquals(false, c.enableRecoveryPhrase, "Recovery phrase must be explicit opt-in")
        assertNull(c.gatewayUrl)
    }
}
