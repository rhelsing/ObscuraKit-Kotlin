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
import java.io.File

/**
 * Persistence: file-backed DB survives simulated restart, queued messages
 * are drained, Signal sessions still work after restore.
 * All E2E against live server using ObscuraClient public API only.
 */
class PersistenceTests {

    @Test
    fun `Messages received after restart with file-backed DB`() = runBlocking {
        assumeTrue(checkServer())

        val bobDbPath = File.createTempFile("obscura_bob_", ".db").absolutePath

        // Alice: in-memory, stays alive
        val alice = registerAndConnect("pe_a")
        assertEquals(AuthState.AUTHENTICATED, alice.authState.value)

        // Bob: file-backed DB
        val bob1 = ObscuraClient(ObscuraConfig(API, databasePath = bobDbPath))
        bob1.register(uniqueName("pe_b"), TEST_PASSWORD)
        assertEquals(AuthState.AUTHENTICATED, bob1.authState.value)
        assertNotNull(bob1.userId)
        assertNotNull(bob1.deviceId)
        bob1.connect()
        assertEquals(ConnectionState.CONNECTED, bob1.connectionState.value)

        // Save Bob's credentials for restore
        val bobToken = bob1.token!!
        val bobRefreshToken = bob1.refreshToken
        val bobUserId = bob1.userId!!
        val bobDeviceId = bob1.deviceId
        val bobUsername = bob1.username
        val bobRegId = bob1.registrationId

        // Establish friendship with state verification
        becomeFriends(alice, bob1)
        assertTrue(alice.friendList.value.any { it.userId == bobUserId && it.status == FriendStatus.ACCEPTED })
        assertTrue(bob1.friendList.value.any { it.userId == alice.userId && it.status == FriendStatus.ACCEPTED })

        // Exchange a message to prove sessions work
        sendAndVerify(alice, bob1, "before restart")

        val bob1Msgs = bob1.getMessages(alice.userId!!)
        assertTrue(bob1Msgs.any { it.content == "before restart" },
            "Bob1 conversations should contain pre-restart message")

        // Bob disconnects (simulates app killed)
        bob1.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, bob1.connectionState.value)

        // Alice sends while Bob is offline — server queues it
        alice.send(bobUsername!!, "while you were gone")
        delay(1000)

        // Simulate restart: new ObscuraClient, same file-backed DB
        val bob2 = ObscuraClient(ObscuraConfig(API, databasePath = bobDbPath))
        bob2.restoreSession(
            token = bobToken,
            refreshToken = bobRefreshToken,
            userId = bobUserId,
            deviceId = bobDeviceId,
            username = bobUsername,
            registrationId = bobRegId
        )

        // Connect — should drain the server queue
        bob2.connect()
        assertEquals(ConnectionState.CONNECTED, bob2.connectionState.value,
            "Bob2 should be CONNECTED after restore + connect")

        // Receive the queued message
        val received = bob2.waitForMessage(20_000)
        assertEquals("TEXT", received.type)
        assertEquals("while you were gone", received.text)
        assertEquals(alice.userId, received.sourceUserId)
        delay(300)

        // Verify in Bob2's conversations
        val bob2Msgs = bob2.getMessages(alice.userId!!)
        assertTrue(bob2Msgs.any { it.content == "while you were gone" },
            "Bob2 conversations should contain the offline message after restart")

        // Prove bidirectional works after restart
        bob2.send(alice.username!!, "I'm back")
        val reply = alice.waitForMessage()
        assertEquals("TEXT", reply.type)
        assertEquals("I'm back", reply.text)
        assertEquals(bobUserId, reply.sourceUserId)
        delay(300)

        // Verify Alice's conversations
        val aliceMsgs = alice.getMessages(bobUserId)
        assertTrue(aliceMsgs.any { it.content == "I'm back" },
            "Alice's conversations should contain Bob's reply after restart")

        alice.disconnect(); bob2.disconnect()
    }
}
