package scenarios

import com.obscura.kit.ConnectionState
import com.obscura.kit.stores.FriendStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Session reconnect: Signal session survives disconnect/reconnect, self-friend rejection.
 * All E2E against live server using ObscuraClient public API only.
 */
class SessionReconnectTests {

    @Test
    fun `Signal session survives disconnect and reconnect`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("rc_a")
        val bob = registerAndConnect("rc_b")

        assertEquals(ConnectionState.CONNECTED, alice.connectionState.value)
        assertEquals(ConnectionState.CONNECTED, bob.connectionState.value)

        becomeFriends(alice, bob)

        // Verify friend state
        assertTrue(alice.friendList.value.any { it.userId == bob.userId && it.status == FriendStatus.ACCEPTED })
        assertTrue(bob.friendList.value.any { it.userId == alice.userId && it.status == FriendStatus.ACCEPTED })

        // Send before disconnect
        sendAndVerify(alice, bob, "before disconnect")

        val bobMsgsBefore = bob.getMessages(alice.userId!!)
        assertTrue(bobMsgsBefore.any { it.content == "before disconnect" },
            "Bob's conversations should contain pre-disconnect message")

        // Bob disconnects and reconnects
        bob.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, bob.connectionState.value)
        delay(1000)
        bob.connect()
        assertEquals(ConnectionState.CONNECTED, bob.connectionState.value,
            "Bob should be CONNECTED after reconnect")

        // Send after reconnect — Signal session should still work
        sendAndVerify(alice, bob, "after reconnect")

        val bobMsgsAfter = bob.getMessages(alice.userId!!)
        assertTrue(bobMsgsAfter.any { it.content == "after reconnect" },
            "Bob's conversations should contain post-reconnect message")
        assertTrue(bobMsgsAfter.any { it.content == "before disconnect" },
            "Bob's conversations should still contain pre-disconnect message")

        alice.disconnect(); bob.disconnect()
    }

    @Test
    fun `Self-friend rejection`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("rc_c")

        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { alice.befriend(alice.userId!!, alice.username!!) }
        }
        assertTrue(ex.message!!.contains("Cannot befriend yourself"),
            "Should reject self-befriend with clear message")

        alice.disconnect()
    }
}
