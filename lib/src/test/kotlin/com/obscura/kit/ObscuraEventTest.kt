package com.obscura.kit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The state enums a bridge relays to JS.
 *
 * Five of this file's six tests used to assert compiler-generated members —
 * `FriendsUpdated(emptyList()).friends == emptyList()`, `ConnectionChanged(x).state == x`,
 * `data class equality holds for identical payloads`. Those cannot fail for any edit that compiles,
 * so they measured nothing. The one below can: it pins the order and membership of two enums that
 * cross a bridge as ordinals, where dropping or reordering a case is a silent wire break.
 */
class ObscuraEventTest {

    @Test
    fun `state enums expose the full lifecycle`() {
        assertEquals(
            listOf("DISCONNECTED", "CONNECTING", "RECONNECTING", "CONNECTED"),
            ConnectionState.entries.map { it.name }
        )
        assertEquals(
            listOf("LOGGED_OUT", "PENDING_APPROVAL", "AUTHENTICATED"),
            AuthState.entries.map { it.name }
        )
    }
}
