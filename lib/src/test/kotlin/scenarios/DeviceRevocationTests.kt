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

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DeviceRevocationTests {

    companion object {
        private val API = "https://obscura.barrelmaker.dev"
        private var serverUp = false
        private var bob: ObscuraClient? = null
        private var alice: ObscuraClient? = null

        @BeforeAll @JvmStatic fun setup() {
            serverUp = try {
                java.net.URL("$API/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close(); true
            } catch (e: Exception) { false }

            if (serverUp) runBlocking {
                bob = ObscuraClient(ObscuraConfig(API))
                bob!!.register("kt_r7_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
                alice = ObscuraClient(ObscuraConfig(API))
                alice!!.register("kt_r7a_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
                bob!!.connect(); alice!!.connect()
                bob!!.befriend(alice!!.userId!!, alice!!.username!!)
                alice!!.waitForMessage()
                alice!!.acceptFriend(bob!!.userId!!, bob!!.username!!)
                bob!!.waitForMessage()
            }
        }
    }

    private fun need() = assumeTrue(serverUp && bob != null)

    @Test @Order(1)
    fun `7-1 - Server shows registered device`() = runBlocking {
        need()
        val devices = bob!!.api.listDevices()
        assertTrue(devices.length() >= 1)
    }

    @Test @Order(2)
    fun `7-2 - Revocation announcement delivered to friend`() = runBlocking {
        need()
        bob!!.announceDeviceRevocation(alice!!.username!!, listOf(bob!!.deviceId!!))

        val msg = alice!!.waitForMessage()
        assertEquals("DEVICE_ANNOUNCE", msg.type)
        assertTrue(msg.raw!!.deviceAnnounce.isRevocation)
        assertEquals(1, msg.raw!!.deviceAnnounce.devicesCount)
    }
}
