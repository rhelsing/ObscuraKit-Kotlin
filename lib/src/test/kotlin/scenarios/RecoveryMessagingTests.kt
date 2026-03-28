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
 * Recovery messaging: recover account with new device, resume messaging.
 * Covers: test-recovery-messaging.js
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RecoveryMessagingTests {

    companion object {
        private val API = "https://obscura.barrelmaker.dev"
        private var serverUp = false
        private var alice: ObscuraClient? = null
        private var bob: ObscuraClient? = null
        private var aliceRecoveryPhrase: String? = null

        @BeforeAll @JvmStatic fun setup() {
            serverUp = try {
                java.net.URL("$API/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close(); true
            } catch (e: Exception) { false }

            if (serverUp) runBlocking {
                alice = ObscuraClient(ObscuraConfig(API))
                alice!!.register("kt_rm_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
                aliceRecoveryPhrase = alice!!.generateRecoveryPhrase()

                bob = ObscuraClient(ObscuraConfig(API))
                bob!!.register("kt_rm2_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")

                alice!!.connect(); bob!!.connect()
                alice!!.befriend(bob!!.userId!!, bob!!.username!!)
                bob!!.waitForMessage()
                bob!!.acceptFriend(alice!!.userId!!, alice!!.username!!)
                alice!!.waitForMessage()
            }
        }
    }

    private fun need() = assumeTrue(serverUp && alice != null)

    @Test @Order(1)
    fun `Alice announces recovery, Bob receives`() = runBlocking {
        need()

        alice!!.announceRecovery(aliceRecoveryPhrase!!)

        val msg = bob!!.waitForMessage()
        assertEquals("DEVICE_RECOVERY_ANNOUNCE", msg.type)
        assertTrue(msg.raw!!.deviceRecoveryAnnounce.isFullRecovery)
    }

    @Test @Order(2)
    fun `Messaging continues after recovery announcement`() = runBlocking {
        need()

        alice!!.send(bob!!.username!!, "I recovered my account!")
        val msg = bob!!.waitForMessage()
        assertEquals("I recovered my account!", msg.text)

        alice!!.disconnect(); bob!!.disconnect()
    }
}
