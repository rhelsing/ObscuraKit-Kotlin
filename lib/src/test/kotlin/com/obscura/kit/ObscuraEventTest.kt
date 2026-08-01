package com.obscura.kit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the state names consumed by explicit bridge mappings.
 */
class ObscuraEventTest {

    @Test
    fun `state enums expose the full lifecycle`() {
        assertEquals(
            setOf("DISCONNECTED", "CONNECTING", "RECONNECTING", "CONNECTED"),
            ConnectionState.entries.map { it.name }.toSet()
        )
        assertEquals(
            setOf("LOGGED_OUT", "PENDING_APPROVAL", "AUTHENTICATED"),
            AuthState.entries.map { it.name }.toSet()
        )
    }
}
