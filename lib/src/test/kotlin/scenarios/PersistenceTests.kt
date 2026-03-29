package scenarios

import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.File

/**
 * Server queue drain after simulated restart.
 *
 * The server is a dumb queue — it holds encrypted envelopes until a device
 * connects and ACKs them. These tests prove the client survives losing all
 * in-memory state and still drains the queue correctly.
 *
 * Key chain that must work after restart:
 * 1. Valid token (or refresh it)
 * 2. Get gateway ticket
 * 3. Connect WebSocket
 * 4. Decrypt every envelope (needs persisted Signal session state)
 * 5. Route each message (needs rebuilt deviceMap from persisted friends)
 * 6. ACK so server deletes them
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PersistenceTests {

    companion object {
        private val API = "https://obscura.barrelmaker.dev"
        private var serverUp = false

        // File-backed DB so a second ObscuraClient can read the same state
        private val bobDbPath = File.createTempFile("obscura_bob_", ".db").absolutePath

        // Alice (stays in memory — she's the sender)
        private var alice: ObscuraClient? = null

        // Bob's credentials for restore
        private var bobToken: String? = null
        private var bobRefreshToken: String? = null
        private var bobUserId: String? = null
        private var bobDeviceId: String? = null
        private var bobUsername: String? = null
        private var bobRegId: Int = 0

        @BeforeAll @JvmStatic fun setup() {
            serverUp = try {
                java.net.URL("$API/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close(); true
            } catch (e: Exception) { false }
        }
    }

    private fun need() = assumeTrue(serverUp)

    @Test @Order(1)
    fun `Scenario E - Friend request arrives while offline, received after restart`() = runBlocking {
        need()

        // Register Alice (in-memory DB, stays alive)
        alice = ObscuraClient(ObscuraConfig(API))
        alice!!.register("kt_pe_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
        alice!!.connect()

        // Register Bob with file-backed DB
        val bob1 = ObscuraClient(ObscuraConfig(API, databasePath = bobDbPath))
        bob1.register("kt_pe2_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
        bob1.connect()

        // Save Bob's credentials for later restore
        bobToken = bob1.token
        bobRefreshToken = bob1.refreshToken
        bobUserId = bob1.userId
        bobDeviceId = bob1.deviceId
        bobUsername = bob1.username
        bobRegId = bob1.registrationId

        // Establish friendship (creates Signal sessions on both sides)
        alice!!.befriend(bobUserId!!, bobUsername!!)
        bob1.waitForMessage()
        bob1.acceptFriend(alice!!.userId!!, alice!!.username!!)
        alice!!.waitForMessage()

        // Exchange a message to prove sessions work
        alice!!.send(bobUsername!!, "before restart")
        val msg = bob1.waitForMessage()
        assertEquals("before restart", msg.text)

        // Bob disconnects (simulates app being killed)
        bob1.disconnect()

        // Alice sends a friend-visible message while Bob is offline
        // Server queues it
        alice!!.send(bobUsername!!, "while you were gone")

        // Simulate restart: create NEW ObscuraClient with SAME file-backed DB
        val bob2 = ObscuraClient(ObscuraConfig(API, databasePath = bobDbPath))

        // Restore session (token + userId, NOT re-registering)
        bob2.restoreSession(
            token = bobToken!!,
            refreshToken = bobRefreshToken,
            userId = bobUserId!!,
            deviceId = bobDeviceId,
            username = bobUsername,
            registrationId = bobRegId
        )

        // Connect — should drain the server's queue
        bob2.connect()

        // Bob should receive the message sent while offline
        val received = bob2.waitForMessage()
        assertEquals("TEXT", received.type)
        assertEquals("while you were gone", received.text)
        assertEquals(alice!!.userId, received.sourceUserId)

        // Prove bidirectional works after restart too
        bob2.send(alice!!.username!!, "I'm back")
        val reply = alice!!.waitForMessage()
        assertEquals("I'm back", reply.text)

        alice!!.disconnect()
        bob2.disconnect()
    }
}
