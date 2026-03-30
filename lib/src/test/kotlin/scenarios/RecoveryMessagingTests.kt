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
 * Recovery messaging: full befriend lifecycle, generate recovery phrase,
 * announce recovery to friend, then verify messaging continues.
 * Uses only public API + shared helpers.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RecoveryMessagingTests {

    companion object {
        private var serverUp = false
        private var alice: com.obscura.kit.ObscuraClient? = null
        private var bob: com.obscura.kit.ObscuraClient? = null
        private var aliceRecoveryPhrase: String? = null

        @BeforeAll @JvmStatic fun setup() {
            serverUp = checkServer()

            if (serverUp) runBlocking {
                val recoveryConfig = com.obscura.kit.ObscuraConfig(API, enableRecoveryPhrase = true)
                alice = registerAndConnect("rec_alice", recoveryConfig)
                bob = registerAndConnect("rec_bob")

                // Generate recovery phrase before befriending
                aliceRecoveryPhrase = alice!!.generateRecoveryPhrase()
                assertNotNull(aliceRecoveryPhrase, "Recovery phrase should not be null")

                // Full befriend lifecycle
                becomeFriends(alice!!, bob!!)
            }
        }
    }

    private fun need() = assumeTrue(serverUp && alice != null)

    @Test @Order(1)
    fun `Alice announces recovery, Bob receives`() = runBlocking {
        need()

        alice!!.announceRecovery(aliceRecoveryPhrase!!)

        val msg = bob!!.waitForMessage()
        assertEquals("DEVICE_RECOVERY_ANNOUNCE", msg.type,
            "Bob should receive DEVICE_RECOVERY_ANNOUNCE")
    }

    @Test @Order(2)
    fun `Messaging continues after recovery announcement`() = runBlocking {
        need()

        sendAndVerify(alice!!, bob!!, "I recovered my account!")
        sendAndVerify(bob!!, alice!!, "Glad you're back!")

        // Verify conversations state
        delay(300)
        val aliceMsgs = alice!!.getMessages(bob!!.userId!!)
        assertTrue(aliceMsgs.any { it.content == "Glad you're back!" },
            "Alice's conversations should contain Bob's reply")

        val bobMsgs = bob!!.getMessages(alice!!.userId!!)
        assertTrue(bobMsgs.any { it.content == "I recovered my account!" },
            "Bob's conversations should contain Alice's recovery message")

        alice!!.disconnect(); bob!!.disconnect()
    }
}
