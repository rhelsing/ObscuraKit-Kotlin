package com.obscura.kit

import com.obscura.kit.orm.OrmEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * ObscuraEvent is the typed event stream apps subscribe to (and bridges relay
 * to JS). These pin the payload contract of each variant and the state enums
 * they carry, so a field rename or a dropped enum value can't slip through.
 */
class ObscuraEventTest {

    @Test
    fun `FriendsUpdated carries the friend list`() {
        val e = ObscuraEvent.FriendsUpdated(emptyList())
        assertEquals(emptyList<Any>(), e.friends)
    }

    @Test
    fun `ConnectionChanged and AuthChanged carry their state`() {
        assertEquals(ConnectionState.CONNECTED, ObscuraEvent.ConnectionChanged(ConnectionState.CONNECTED).state)
        assertEquals(AuthState.AUTHENTICATED, ObscuraEvent.AuthChanged(AuthState.AUTHENTICATED).state)
    }

    @Test
    fun `MessageReceived carries the model name and entry`() {
        val entry = OrmEntry(id = "dm_1", data = mapOf("content" to "hi"), timestamp = 1L, authorDeviceId = "d1")
        val e = ObscuraEvent.MessageReceived("directMessage", entry)
        assertEquals("directMessage", e.model)
        assertEquals("dm_1", e.entry.id)
    }

    @Test
    fun `TypingChanged carries context key and typers`() {
        val e = ObscuraEvent.TypingChanged("c1", listOf("alice", "bob"))
        assertEquals("c1", e.contextId)
        assertEquals(listOf("alice", "bob"), e.typers)
    }

    @Test
    fun `data class equality holds for identical payloads`() {
        assertEquals(
            ObscuraEvent.TypingChanged("c1", listOf("alice")),
            ObscuraEvent.TypingChanged("c1", listOf("alice"))
        )
        assertNotEquals(
            ObscuraEvent.TypingChanged("c1", listOf("alice")),
            ObscuraEvent.TypingChanged("c2", listOf("alice"))
        )
    }

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
