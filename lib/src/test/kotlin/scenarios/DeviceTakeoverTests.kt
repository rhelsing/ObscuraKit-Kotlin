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
 * Device takeover: replace identity key, verify server accepts, messaging resumes.
 * Covers: test-device-takeover.js
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DeviceTakeoverTests {

    companion object {
        private val API = "https://obscura.barrelmaker.dev"
        private var serverUp = false

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
    fun `Takeover replaces keys on server`() = runBlocking {
        need()
        val client = ObscuraClient(ObscuraConfig(API))
        client.register(name(), "testpass123!xyz")

        val oldRegId = client.registrationId
        client.takeoverDevice()
        assertNotEquals(oldRegId, client.registrationId)

        // Server still lists 1 device (same device, new keys)
        val devices = client.api.listDevices()
        assertEquals(1, devices.length())
    }

    @Test @Order(2)
    fun `Can message new user after takeover`() = runBlocking {
        need()
        // Alice takes over, then befriends a NEW user (clean session)
        val alice = ObscuraClient(ObscuraConfig(API))
        alice.register(name(), "testpass123!xyz")
        alice.takeoverDevice()

        val bob = ObscuraClient(ObscuraConfig(API))
        bob.register(name(), "testpass123!xyz")

        alice.connect(); bob.connect()
        alice.befriend(bob.userId!!, bob.username!!)
        bob.waitForMessage() // FRIEND_REQUEST
        bob.acceptFriend(alice.userId!!, alice.username!!)
        alice.waitForMessage() // FRIEND_RESPONSE

        alice.send(bob.username!!, "Post-takeover message")
        val msg = bob.waitForMessage()
        assertEquals("Post-takeover message", msg.text)

        alice.disconnect(); bob.disconnect()
    }
}
