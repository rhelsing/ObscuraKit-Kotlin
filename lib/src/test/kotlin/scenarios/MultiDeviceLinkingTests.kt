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
 * Scenario 5: Multi-device E2E.
 * Verify device listing, friend flow + text from different user pairs.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MultiDeviceLinkingTests {

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
    fun `5-1 - Register shows 1 device on server`() = runBlocking {
        need()
        val client = ObscuraClient(ObscuraConfig(API))
        client.register(name(), "testpass123!xyz")

        val devices = client.api.listDevices()
        assertEquals(1, devices.length())
    }

    @Test @Order(2)
    fun `5-2 - Two users exchange messages after befriend`() = runBlocking {
        need()
        val alice = ObscuraClient(ObscuraConfig(API))
        alice.register(name(), "testpass123!xyz")
        val bob = ObscuraClient(ObscuraConfig(API))
        bob.register(name(), "testpass123!xyz")

        alice.connect(); bob.connect()
        alice.befriend(bob.userId!!, bob.username!!)
        bob.waitForMessage() // FRIEND_REQUEST
        bob.acceptFriend(alice.userId!!, alice.username!!)
        alice.waitForMessage() // FRIEND_RESPONSE

        // Verify messaging works through the abstraction
        alice.send(bob.username!!, "Multi-device test msg")
        val msg = bob.waitForMessage()
        assertEquals("Multi-device test msg", msg.text)

        alice.disconnect(); bob.disconnect()
    }

    @Test @Order(3)
    fun `5-3 - Friend fan-out targets populated after befriend`() = runBlocking {
        need()
        val alice = ObscuraClient(ObscuraConfig(API))
        alice.register(name(), "testpass123!xyz")
        val bob = ObscuraClient(ObscuraConfig(API))
        bob.register(name(), "testpass123!xyz")

        alice.connect(); bob.connect()
        alice.befriend(bob.userId!!, bob.username!!)
        bob.waitForMessage()
        bob.acceptFriend(alice.userId!!, alice.username!!)
        alice.waitForMessage()

        // Verify messenger knows Bob's devices
        val bobDevices = alice.messenger.getDeviceIdsForUser(bob.userId!!)
        assertTrue(bobDevices.isNotEmpty(), "Should know at least 1 Bob device")
        assertTrue(bobDevices.contains(bob.deviceId), "Should contain Bob's deviceId")

        alice.disconnect(); bob.disconnect()
    }
}
