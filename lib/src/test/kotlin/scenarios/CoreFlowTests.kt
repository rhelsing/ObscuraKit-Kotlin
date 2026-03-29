package scenarios

import com.obscura.kit.AuthState
import com.obscura.kit.ConnectionState
import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.stores.FriendStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Core lifecycle: Register, Login, Befriend, Message, Offline delivery.
 * All E2E against live server using ObscuraClient public API only.
 */
class CoreFlowTests {

    @Test
    fun `Register creates user and authenticates`() = runBlocking {
        assumeTrue(checkServer())

        val client = ObscuraClient(ObscuraConfig(API))
        val username = uniqueName("reg")
        client.register(username, TEST_PASSWORD)

        assertEquals(AuthState.AUTHENTICATED, client.authState.value,
            "authState should be AUTHENTICATED after register")
        assertNotNull(client.userId, "userId should be set after register")
        assertNotNull(client.deviceId, "deviceId should be set after register")
        assertEquals(username, client.username, "username should match")
    }

    @Test
    fun `Login restores same identity`() = runBlocking {
        assumeTrue(checkServer())

        val client = ObscuraClient(ObscuraConfig(API))
        val username = uniqueName("login")
        client.register(username, TEST_PASSWORD)
        val originalUserId = client.userId

        assertEquals(AuthState.AUTHENTICATED, client.authState.value)

        client.login(username, TEST_PASSWORD)

        assertEquals(AuthState.AUTHENTICATED, client.authState.value,
            "authState should remain AUTHENTICATED after login")
        assertEquals(originalUserId, client.userId,
            "userId should be the same after login")
    }

    @Test
    fun `Friend request flow with state verification`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("cf_a")
        val bob = registerAndConnect("cf_b")

        assertEquals(ConnectionState.CONNECTED, alice.connectionState.value)
        assertEquals(ConnectionState.CONNECTED, bob.connectionState.value)
        assertTrue(alice.friendList.value.isEmpty(), "Alice should start with no friends")
        assertTrue(bob.friendList.value.isEmpty(), "Bob should start with no friends")

        becomeFriends(alice, bob)

        // State already verified inside becomeFriends(), but double-check
        assertEquals(1, alice.friendList.value.size)
        assertEquals(1, bob.friendList.value.size)
        assertTrue(alice.friendList.value.any { it.userId == bob.userId && it.status == FriendStatus.ACCEPTED })
        assertTrue(bob.friendList.value.any { it.userId == alice.userId && it.status == FriendStatus.ACCEPTED })

        alice.disconnect(); bob.disconnect()
    }

    @Test
    fun `Encrypted text messages round-trip with conversations verification`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("cf_c")
        val bob = registerAndConnect("cf_d")
        becomeFriends(alice, bob)

        // Alice -> Bob
        sendAndVerify(alice, bob, "Hello Bob from Kotlin!")

        // Bob -> Alice
        sendAndVerify(bob, alice, "Hello Alice!")

        // Verify both sides have the full conversation
        val bobMsgs = bob.getMessages(alice.userId!!)
        assertTrue(bobMsgs.any { it.content == "Hello Bob from Kotlin!" },
            "Bob's conversations should contain Alice's message")

        val aliceMsgs = alice.getMessages(bob.userId!!)
        assertTrue(aliceMsgs.any { it.content == "Hello Alice!" },
            "Alice's conversations should contain Bob's message")

        alice.disconnect(); bob.disconnect()
    }

    @Test
    fun `Offline delivery - message queued while disconnected`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("cf_e")
        val bob = registerAndConnect("cf_f")
        becomeFriends(alice, bob)

        // Bob disconnects
        bob.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, bob.connectionState.value,
            "Bob should be DISCONNECTED")
        delay(1000)

        // Alice sends while Bob offline
        alice.send(bob.username!!, "You were offline!")
        delay(1000)

        // Bob reconnects
        bob.connect()
        assertEquals(ConnectionState.CONNECTED, bob.connectionState.value,
            "Bob should be CONNECTED after reconnect")

        val msg = bob.waitForMessage(20_000)
        assertEquals("TEXT", msg.type)
        assertEquals("You were offline!", msg.text)
        assertEquals(alice.userId, msg.sourceUserId)
        delay(300)

        // Verify in Bob's conversations
        val bobMsgs = bob.getMessages(alice.userId!!)
        assertTrue(bobMsgs.any { it.content == "You were offline!" },
            "Bob's conversations should contain the offline message")

        alice.disconnect(); bob.disconnect()
    }
}
