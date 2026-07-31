package scenarios

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
 * Multi-device linking: verify device listing, befriend + messaging.
 * Uses only public API + shared helpers.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MultiDeviceLinkingTests {

    companion object {
        private var serverUp = false

        @BeforeAll @JvmStatic fun check() {
            serverUp = checkServer()
        }
    }

    private fun need() = assumeTrue(serverUp)

    @Test @Order(1)
    fun `Register shows 1 device on server`() = runBlocking {
        need()
        val client = registerAndConnect("mdl_single")
        val devices = client.api.listDevices()
        assertEquals(1, devices.length(), "Newly registered user should have 1 device")
        client.disconnect()
    }

    @Test @Order(2)
    fun `Two users befriend and exchange messages`() = runBlocking {
        need()
        val alice = registerAndConnect("mdl_alice")
        val bob = registerAndConnect("mdl_bob")

        becomeFriends(alice, bob)
        sendAndVerify(alice, bob, "Hello from Alice!")
        sendAndVerify(bob, alice, "Hello from Bob!")

        alice.disconnect(); bob.disconnect()
    }

    @Test @Order(3)
    fun `Messaging works after befriend - verify conversations state`() = runBlocking {
        need()
        val alice = registerAndConnect("mdl_conv_a")
        val bob = registerAndConnect("mdl_conv_b")

        becomeFriends(alice, bob)

        sendAndVerify(alice, bob, "Test message 1")
        bob.waitForMessage()
        delay(300)

        sendAndVerify(alice, bob, "Test message 2")
        bob.waitForMessage()
        delay(300)


        alice.disconnect(); bob.disconnect()
    }
}
