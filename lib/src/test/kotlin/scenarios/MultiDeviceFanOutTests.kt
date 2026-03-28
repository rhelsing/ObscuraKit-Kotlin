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
 * Multi-device fan-out: Bob has 2 devices, Alice sends, both receive.
 * Covers: test-multi-device.js, test-multi-device-2.js, test-multi-device-3.js
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MultiDeviceFanOutTests {

    companion object {
        private val API = "https://obscura.barrelmaker.dev"
        private var serverUp = false
        private var alice: ObscuraClient? = null
        private var bob1: ObscuraClient? = null
        private var bob2: ObscuraClient? = null
        private var bobUsername: String? = null

        @BeforeAll @JvmStatic fun setup() {
            serverUp = try {
                java.net.URL("$API/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close(); true
            } catch (e: Exception) { false }

            if (serverUp) runBlocking {
                val name = "kt_mdf_${System.currentTimeMillis()}_${(1000..9999).random()}"
                bobUsername = name

                // Bob device 1
                bob1 = ObscuraClient(ObscuraConfig(API, deviceName = "Bob Phone"))
                bob1!!.register(name, "testpass123!xyz")

                // Bob device 2 (same user, new device)
                bob2 = ObscuraClient(ObscuraConfig(API, deviceName = "Bob Laptop"))
                bob2!!.loginAndProvision(name, "testpass123!xyz", "Bob Laptop")

                // Alice
                alice = ObscuraClient(ObscuraConfig(API))
                alice!!.register("kt_mdfa_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")

                // Befriend — bob2 also needs to be connected to drain its queue
                bob1!!.connect(); bob2!!.connect(); alice!!.connect()
                alice!!.befriend(bob1!!.userId!!, bobUsername!!)
                bob1!!.waitForMessage() // FRIEND_REQUEST on bob1
                bob2!!.waitForMessage() // FRIEND_REQUEST on bob2 (fan-out)
                bob1!!.acceptFriend(alice!!.userId!!, alice!!.username!!)
                alice!!.waitForMessage() // FRIEND_RESPONSE
                bob1!!.disconnect(); bob2!!.disconnect()
            }
        }
    }

    private fun need() = assumeTrue(serverUp && bob2 != null)

    @Test @Order(1)
    fun `Server shows 2 devices for Bob`() = runBlocking {
        need()
        val devices = bob1!!.api.listDevices()
        assertEquals(2, devices.length())
    }

    @Test @Order(2)
    fun `Alice sends to Bob, both devices receive`() = runBlocking {
        need()
        bob1!!.connect()
        bob2!!.connect()

        // Alice fetches Bob's bundles — should discover 2 devices
        alice!!.send(bobUsername!!, "Hello both Bobs!")

        val msg1 = bob1!!.waitForMessage()
        assertEquals("TEXT", msg1.type)
        assertEquals("Hello both Bobs!", msg1.text)

        val msg2 = bob2!!.waitForMessage()
        assertEquals("TEXT", msg2.type)
        assertEquals("Hello both Bobs!", msg2.text)

        bob1!!.disconnect(); bob2!!.disconnect()
    }

    @Test @Order(3)
    fun `Bob1 sends to Alice, Bob2 gets SENT_SYNC`() = runBlocking {
        need()
        bob1!!.connect(); bob2!!.connect()

        // Register bob2 as own device so self-sync targets are populated
        bob1!!.devices.addOwnDevice(com.obscura.kit.stores.OwnDeviceData(bob2!!.deviceId!!, "Bob Laptop"))
        bob1!!.messenger.mapDevice(bob2!!.deviceId!!, bob1!!.userId!!, bob2!!.registrationId)

        bob1!!.send(alice!!.username!!, "From Bob1 to Alice")

        // Alice should get the message
        val aliceMsg = alice!!.waitForMessage()
        assertEquals("From Bob1 to Alice", aliceMsg.text)

        // Bob2 should get SENT_SYNC
        val syncMsg = bob2!!.waitForMessage()
        assertEquals("SENT_SYNC", syncMsg.type)

        bob1!!.disconnect(); bob2!!.disconnect()
    }
}
