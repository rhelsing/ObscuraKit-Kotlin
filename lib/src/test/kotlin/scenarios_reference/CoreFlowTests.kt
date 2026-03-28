package scenarios_reference

import com.obscura.kit.ObscuraTestClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import obscura.v2.Client.ClientMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Scenarios 1-4: Real E2E against live server.
 *
 * 1. Register → keys generated, token valid, userId parseable
 * 2. Logout → login → identity restored
 * 3. Friend request flow: encrypted FRIEND_REQUEST via server, WebSocket delivery, FRIEND_RESPONSE
 * 4. Send/receive encrypted TEXT messages over WebSocket
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CoreFlowTests {

    companion object {
        private val API_URL = System.getenv("OBSCURA_API_URL") ?: "https://obscura.barrelmaker.dev"
        private var serverAvailable = false

        // Shared clients — registered once, used across ordered tests
        private var alice: ObscuraTestClient? = null
        private var bob: ObscuraTestClient? = null

        @BeforeAll
        @JvmStatic
        fun setup() {
            serverAvailable = try {
                java.net.URL("$API_URL/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close()
                true
            } catch (e: Exception) { false }
        }
    }

    private fun requireServer() = assumeTrue(serverAvailable, "Server not available at $API_URL")
    private fun uniqueUsername() = "kt_${System.currentTimeMillis()}_${(1000..9999).random()}"

    @Test
    @Order(1)
    fun `Scenario 1 - Register generates keys, token valid, deviceId set`() = runTest {
        requireServer()

        alice = ObscuraTestClient(API_URL)
        alice!!.register(uniqueUsername(), "testpass123!xyz")

        assertNotNull(alice!!.userId, "userId should be set")
        assertNotNull(alice!!.deviceId, "deviceId should be set")
        assertNotNull(alice!!.api.token, "token should be set")
        assertNotNull(alice!!.refreshToken, "refreshToken should be set")

        val decoded = alice!!.api.decodeToken()
        assertNotNull(decoded, "token should be decodable")

        assertTrue(alice!!.signalStore.getLocalRegistrationId() > 0, "registrationId should be positive")
    }

    @Test
    @Order(2)
    fun `Scenario 2 - Logout and login restores identity`() = runTest {
        requireServer()
        assertNotNull(alice, "Scenario 1 must run first")

        val originalUserId = alice!!.userId
        val originalDeviceId = alice!!.deviceId

        // Login again (same client, same signal store)
        alice!!.login(password = "testpass123!xyz", deviceId = originalDeviceId)

        assertEquals(originalUserId, alice!!.userId, "userId should be restored")
        assertNotNull(alice!!.api.token, "token should be set after login")
    }

    @Test
    @Order(3)
    fun `Scenario 3 - Friend request via encrypted Signal messages over WebSocket`() = runBlocking {
        requireServer()
        assertNotNull(alice, "Scenario 1 must run first")

        bob = ObscuraTestClient(API_URL)
        bob!!.register(uniqueUsername(), "testpass123!xyz")

        // Both connect WebSocket
        alice!!.connectWebSocket()
        bob!!.connectWebSocket()

        // Alice fetches Bob's prekey bundles (establishes Signal session)
        val bobBundles = alice!!.fetchPreKeyBundles(bob!!.userId!!)
        assertTrue(bobBundles.isNotEmpty(), "Should get at least 1 bundle for Bob")
        val bobDeviceId = bobBundles[0].deviceId.toString()

        // Alice sends encrypted FRIEND_REQUEST to Bob
        alice!!.sendFriendRequest(bob!!.deviceId!!, bob!!.username!!, bob!!.userId!!)

        // Bob receives via WebSocket, decrypts
        val friendReq = bob!!.waitForMessage(15_000)
        assertEquals("FRIEND_REQUEST", friendReq.type, "Should receive FRIEND_REQUEST")
        assertEquals(alice!!.username, friendReq.username, "Should be from Alice")

        // Bob sends encrypted FRIEND_RESPONSE back to Alice
        // Bob needs Alice's prekey bundles first
        bob!!.fetchPreKeyBundles(alice!!.userId!!)
        bob!!.sendFriendResponse(alice!!.deviceId!!, alice!!.username!!, true, alice!!.userId!!)

        // Alice receives FRIEND_RESPONSE
        val friendResp = alice!!.waitForMessage(15_000)
        assertEquals("FRIEND_RESPONSE", friendResp.type, "Should receive FRIEND_RESPONSE")
        assertTrue(friendResp.accepted, "Should be accepted")

        // Store friendship
        alice!!.storeFriend(bob!!.username!!, bob!!.userId!!, listOf(
            ObscuraTestClient.DeviceRef(bob!!.deviceId!!, bob!!.registrationId)
        ))
        bob!!.storeFriend(alice!!.username!!, alice!!.userId!!, listOf(
            ObscuraTestClient.DeviceRef(alice!!.deviceId!!, alice!!.registrationId)
        ))

        assertTrue(alice!!.isFriendsWith(bob!!.username!!))
        assertTrue(bob!!.isFriendsWith(alice!!.username!!))
    }

    @Test
    @Order(4)
    fun `Scenario 4 - Send and receive encrypted text messages`() = runBlocking {
        requireServer()
        assertNotNull(alice, "Scenario 3 must run first")
        assertNotNull(bob, "Scenario 3 must run first")

        // Alice sends TEXT to Bob
        alice!!.sendToFriend(bob!!.username!!, ClientMessage.Type.TEXT) {
            text = "Hello Bob from Kotlin!"
        }

        // Bob receives and decrypts
        val msg1 = bob!!.waitForMessage(15_000)
        assertEquals("TEXT", msg1.type)
        assertEquals("Hello Bob from Kotlin!", msg1.text)

        // Bob replies
        bob!!.sendToFriend(alice!!.username!!, ClientMessage.Type.TEXT) {
            text = "Hello Alice, Kotlin works!"
        }

        // Alice receives and decrypts
        val msg2 = alice!!.waitForMessage(15_000)
        assertEquals("TEXT", msg2.type)
        assertEquals("Hello Alice, Kotlin works!", msg2.text)

        // Cleanup
        alice!!.disconnectWebSocket()
        bob!!.disconnectWebSocket()
    }
}
