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

/**
 * Scenarios 1-4: Register, Login, Friend Request, Text Message.
 * All E2E against live server using ObscuraClient public API only.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CoreFlowTests {

    companion object {
        private val API = "https://obscura.barrelmaker.dev"
        private var serverUp = false
        private var alice: ObscuraClient? = null
        private var bob: ObscuraClient? = null

        @BeforeAll @JvmStatic fun check() {
            serverUp = try {
                java.net.URL("$API/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close(); true
            } catch (e: Exception) { false }
        }
    }

    private fun need() = assumeTrue(serverUp)
    private fun name() = "kt_${System.currentTimeMillis()}_${(1000..9999).random()}"

    @Test @Order(1)
    fun `1 - Register creates user, device, Signal keys`() = runBlocking {
        need()
        alice = ObscuraClient(ObscuraConfig(API))
        alice!!.register(name(), "testpass123!xyz")

        assertNotNull(alice!!.userId)
        assertNotNull(alice!!.deviceId)
        assertNotNull(alice!!.refreshToken)
        assertTrue(alice!!.signalStore.getLocalRegistrationId() > 0)
    }

    @Test @Order(2)
    fun `2 - Login restores identity`() = runBlocking {
        need(); assertNotNull(alice)
        val origUserId = alice!!.userId
        alice!!.login(alice!!.username!!, "testpass123!xyz")
        assertEquals(origUserId, alice!!.userId)
    }

    @Test @Order(3)
    fun `3 - Friend request flow over encrypted WebSocket`() = runBlocking {
        need(); assertNotNull(alice)
        bob = ObscuraClient(ObscuraConfig(API))
        bob!!.register(name(), "testpass123!xyz")

        alice!!.connect()
        bob!!.connect()

        alice!!.befriend(bob!!.userId!!, bob!!.username!!)

        val req = bob!!.waitForMessage()
        assertEquals("FRIEND_REQUEST", req.type)

        bob!!.acceptFriend(alice!!.userId!!, alice!!.username!!)

        val resp = alice!!.waitForMessage()
        assertEquals("FRIEND_RESPONSE", resp.type)
        assertTrue(resp.accepted)
    }

    @Test @Order(4)
    fun `4 - Encrypted text messages round-trip`() = runBlocking {
        need(); assertNotNull(alice); assertNotNull(bob)

        alice!!.send(bob!!.username!!, "Hello Bob from Kotlin!")
        val msg1 = bob!!.waitForMessage()
        assertEquals("TEXT", msg1.type)
        assertEquals("Hello Bob from Kotlin!", msg1.text)

        bob!!.send(alice!!.username!!, "Hello Alice!")
        val msg2 = alice!!.waitForMessage()
        assertEquals("TEXT", msg2.type)
        assertEquals("Hello Alice!", msg2.text)

        alice!!.disconnect()
        bob!!.disconnect()
    }
}
