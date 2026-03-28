package scenarios

import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
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
 * Offline sync: Bob1 disconnects, Alice sends, Bob1 reconnects and gets queued messages.
 * Covers: test-scenario16.js
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OfflineSyncTests {

    companion object {
        private val API = "https://obscura.barrelmaker.dev"
        private var serverUp = false
        private var alice: ObscuraClient? = null
        private var bob: ObscuraClient? = null

        @BeforeAll @JvmStatic fun setup() {
            serverUp = try {
                java.net.URL("$API/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close(); true
            } catch (e: Exception) { false }

            if (serverUp) runBlocking {
                alice = ObscuraClient(ObscuraConfig(API))
                alice!!.register("kt_os_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
                bob = ObscuraClient(ObscuraConfig(API))
                bob!!.register("kt_os2_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")

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
    fun `Messages queued while offline, received on reconnect`() = runBlocking {
        need()

        // Bob disconnects
        bob!!.disconnect()
        delay(1000)

        // Alice sends while Bob is offline
        alice!!.send(bob!!.username!!, "You were offline!")

        delay(1000)

        // Bob reconnects — should receive queued message
        bob!!.connect()
        val msg = bob!!.waitForMessage(20_000)
        assertEquals("TEXT", msg.type)
        assertEquals("You were offline!", msg.text)
    }

    @Test @Order(2)
    fun `Can message normally after reconnect`() = runBlocking {
        need()

        alice!!.send(bob!!.username!!, "Welcome back!")
        val msg = bob!!.waitForMessage()
        assertEquals("Welcome back!", msg.text)

        bob!!.disconnect(); alice!!.disconnect()
    }
}
