package scenarios

import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.AuthState
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
 * Multi-device fan-out: Bob has 2 devices, Alice sends, both receive.
 * Uses only public API + becomeFriends() helper.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MultiDeviceFanOutTests {

    companion object {
        private var serverUp = false
        private var alice: ObscuraClient? = null
        private var bob1: ObscuraClient? = null
        private var bob2: ObscuraClient? = null
        private var bobUsername: String? = null

        @BeforeAll @JvmStatic fun setup() {
            serverUp = checkServer()

            if (serverUp) runBlocking {
                val name = uniqueName("mdf_bob")
                bobUsername = name

                // Bob device 1: register + connect
                bob1 = ObscuraClient(ObscuraConfig(API, deviceName = "Bob Phone"))
                bob1!!.register(name, TEST_PASSWORD)
                assertEquals(AuthState.AUTHENTICATED, bob1!!.authState.value)
                assertNotNull(bob1!!.userId)
                assertNotNull(bob1!!.deviceId)

                // Bob device 2: provision + approve via link code
                bob1!!.connect()
                bob2 = provisionAndApprove(bob1!!, name, "Bob Laptop")
                assertNotNull(bob2!!.deviceId)
                assertNotEquals(bob1!!.deviceId, bob2!!.deviceId, "Devices should have different IDs")

                // Alice: register + connect
                alice = registerAndConnect("mdf_alice")

                // Connect all
                bob1!!.connect()
                bob2!!.connect()

                // Alice befriends Bob (device 1) via becomeFriends()
                becomeFriends(alice!!, bob1!!)

                // Bob device 2 may receive friend request/response/sync — drain all
                try { while (true) { bob2!!.waitForMessage(2_000) } } catch (_: Exception) {}
            }
        }
    }

    private fun need() = assumeTrue(serverUp && bob2 != null)

    @Test @Order(1)
    fun `Server shows 2 devices for Bob`() = runBlocking {
        need()
        val devices = bob1!!.api.listDevices()
        assertEquals(2, devices.length(), "Bob should have 2 devices on server")
    }

    @Test @Order(2)
    fun `Alice sends to Bob, both devices receive`() = runBlocking {
        need()

        alice!!.send(bobUsername!!, "Hello both Bobs!")

        val msg1 = bob1!!.waitForMessage()
        assertEquals("TEXT", msg1.type)
        assertEquals("Hello both Bobs!", msg1.text)
        assertEquals(alice!!.userId, msg1.sourceUserId)

        val msg2 = bob2!!.waitForMessage()
        assertEquals("TEXT", msg2.type)
        assertEquals("Hello both Bobs!", msg2.text)
        assertEquals(alice!!.userId, msg2.sourceUserId)

        // Verify conversations on bob1
        delay(300)
        val bob1Msgs = bob1!!.getMessages(alice!!.userId!!)
        assertTrue(bob1Msgs.any { it.content == "Hello both Bobs!" },
            "Bob device 1 conversations should contain the message")
    }

    @Test @Order(3)
    fun `Bob1 sends to Alice, Alice receives`() = runBlocking {
        need()

        bob1!!.send(alice!!.username!!, "From Bob1 to Alice")

        val aliceMsg = alice!!.waitForMessage()
        assertEquals("TEXT", aliceMsg.type)
        assertEquals("From Bob1 to Alice", aliceMsg.text)
        assertEquals(bob1!!.userId, aliceMsg.sourceUserId)

        // Verify Alice's conversations
        delay(300)
        val aliceMsgs = alice!!.getMessages(bob1!!.userId!!)
        assertTrue(aliceMsgs.any { it.content == "From Bob1 to Alice" },
            "Alice's conversations should contain Bob1's message")

        alice!!.disconnect(); bob1!!.disconnect(); bob2!!.disconnect()
    }
}
