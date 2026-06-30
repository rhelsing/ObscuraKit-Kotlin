package scenarios

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Device revocation: full befriend lifecycle, announce revocation, verify delivery,
 * then verify messaging still works.
 * Uses only public API + shared helpers.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DeviceRevocationTests {

    companion object {
        private var serverUp = false
        private var bob: com.obscura.kit.ObscuraClient? = null
        private var alice: com.obscura.kit.ObscuraClient? = null

        @BeforeAll @JvmStatic fun setup() {
            serverUp = checkServer()

            if (serverUp) runBlocking {
                bob = registerAndConnect("rev_bob")
                alice = registerAndConnect("rev_alice")
                becomeFriends(bob!!, alice!!)
            }
        }
    }

    private fun need() = assumeTrue(serverUp && bob != null)

    @Test @Order(1)
    fun `Server shows registered device`() = runBlocking {
        need()
        val devices = bob!!.api.listDevices()
        assertTrue(devices.length() >= 1, "Bob should have at least 1 device on server")
    }

    @Test @Order(2)
    fun `Revocation announcement delivered to friend`() = runBlocking {
        need()
        bob!!.announceDeviceRevocation(alice!!.username!!, listOf(bob!!.deviceId!!))

        val msg = alice!!.waitForMessage()
        assertEquals("DEVICE_ANNOUNCE", msg.type, "Alice should receive DEVICE_ANNOUNCE")
    }

    @Test @Order(3)
    fun `Messaging still works after revocation announcement`() = runBlocking {
        need()
        sendAndVerify(bob!!, alice!!, "Post-revocation message from Bob")
        sendAndVerify(alice!!, bob!!, "Post-revocation reply from Alice")

        bob!!.disconnect(); alice!!.disconnect()
    }
}
