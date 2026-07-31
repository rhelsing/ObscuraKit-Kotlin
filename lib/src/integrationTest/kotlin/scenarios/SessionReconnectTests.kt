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


        // Bob disconnects and reconnects
        bob.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, bob.connectionState.value)
        delay(1000)
        bob.connect()
        assertEquals(ConnectionState.CONNECTED, bob.connectionState.value,
            "Bob should be CONNECTED after reconnect")

        // Send after reconnect — Signal session should still work
        sendAndVerify(alice, bob, "after reconnect")


        // The EARLIER message must still be there. "An older stored row survives a disconnect and
        // reconnect" is a different property from "a new message arrives", and only the latter is
        // covered by sendAndVerify — which looks only for its own entryId.
        assertTrue(bob.hasReceived("before disconnect"),
            "the pre-disconnect message must survive the reconnect")



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
