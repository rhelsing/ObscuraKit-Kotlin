package scenarios

import com.obscura.kit.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Offline sync: Bob disconnects, Alice sends multiple messages,
 * Bob reconnects and receives all queued messages.
 * All E2E against live server using ObscuraClient public API only.
 */
class OfflineSyncTests {

    @Test
    fun `Multiple messages queued while offline, all received on reconnect`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("os_a")
        val bob = registerAndConnect("os_b")
        becomeFriends(alice, bob)

        // Bob disconnects
        bob.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, bob.connectionState.value,
            "Bob should be DISCONNECTED")
        delay(1000)

        // Alice sends 2 messages while Bob offline
        alice.send(bob.username!!, "Offline message 1")
        delay(500)
        alice.send(bob.username!!, "Offline message 2")
        delay(1000)

        // Bob reconnects — should receive both queued messages
        bob.connect()
        assertEquals(ConnectionState.CONNECTED, bob.connectionState.value,
            "Bob should be CONNECTED after reconnect")

        val msg1 = bob.waitForMessage(20_000)
        assertEquals("TEXT", msg1.type)
        assertEquals(alice.userId, msg1.sourceUserId)

        val msg2 = bob.waitForMessage(20_000)
        assertEquals("TEXT", msg2.type)
        assertEquals(alice.userId, msg2.sourceUserId)

        delay(300)

        // Verify Bob's conversations has both messages
        val bobMsgs = bob.getMessages(alice.userId!!)
        assertTrue(bobMsgs.any { it.content == "Offline message 1" },
            "Bob's conversations should contain 'Offline message 1'")
        assertTrue(bobMsgs.any { it.content == "Offline message 2" },
            "Bob's conversations should contain 'Offline message 2'")

        alice.disconnect(); bob.disconnect()
    }

    @Test
    fun `Normal messaging works after reconnect`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("os_c")
        val bob = registerAndConnect("os_d")
        becomeFriends(alice, bob)

        // Bob disconnects and reconnects
        bob.disconnect()
        delay(1000)
        bob.connect()
        assertEquals(ConnectionState.CONNECTED, bob.connectionState.value)

        // Send after reconnect works normally
        sendAndVerify(alice, bob, "Welcome back!")

        // Verify conversations
        val bobMsgs = bob.getMessages(alice.userId!!)
        assertTrue(bobMsgs.any { it.content == "Welcome back!" },
            "Bob's conversations should contain post-reconnect message")

        alice.disconnect(); bob.disconnect()
    }
}
