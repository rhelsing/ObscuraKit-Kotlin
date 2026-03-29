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
 * Device takeover: replace identity key, verify registrationId changed,
 * then verify messaging works with new keys.
 * Uses only public API + shared helpers.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DeviceTakeoverTests {

    companion object {
        private var serverUp = false

        @BeforeAll @JvmStatic fun check() {
            serverUp = checkServer()
        }
    }

    private fun need() = assumeTrue(serverUp)

    @Test @Order(1)
    fun `Takeover replaces keys on server`() = runBlocking {
        need()
        val client = registerAndConnect("tko_single")

        val oldRegId = client.registrationId
        client.takeoverDevice()
        assertNotEquals(oldRegId, client.registrationId,
            "registrationId should change after takeover")

        // Server still lists 1 device (same device, new keys)
        val devices = client.api.listDevices()
        assertEquals(1, devices.length(), "Should still have 1 device after takeover")

        client.disconnect()
    }

    @Test @Order(2)
    fun `Full lifecycle - befriend, takeover, then message`() = runBlocking {
        need()
        val alice = registerAndConnect("tko_alice")
        val bob = registerAndConnect("tko_bob")

        // Full befriend lifecycle
        becomeFriends(alice, bob)

        // Alice takes over device (new Signal keys)
        val oldRegId = alice.registrationId
        alice.takeoverDevice()
        assertNotEquals(oldRegId, alice.registrationId,
            "Alice's registrationId should change after takeover")

        // Messaging should still work after takeover (session rebuilds automatically)
        sendAndVerify(alice, bob, "Post-takeover message from Alice")
        sendAndVerify(bob, alice, "Reply to post-takeover Alice")

        // Verify conversations state
        delay(300)
        val aliceMsgs = alice.getMessages(bob.userId!!)
        assertTrue(aliceMsgs.any { it.content == "Reply to post-takeover Alice" },
            "Alice's conversations should contain Bob's reply")

        alice.disconnect(); bob.disconnect()
    }
}
