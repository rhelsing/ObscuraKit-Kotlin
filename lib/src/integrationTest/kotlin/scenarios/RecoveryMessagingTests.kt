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
 *
 * ## What this file does NOT cover
 *
 * **There is no receive handler for `DEVICE_RECOVERY_ANNOUNCE`, in either kit.** `routeMessage`'s
 * `else -> { }` branch swallows it and the envelope is then acked, so a recovery announcement
 * changes nothing at all on the recipient: not the friend graph, not the device list, not the stored
 * recovery key.
 *
 * These tests assert only that the **wire message is delivered** — they would pass unchanged if the
 * handler were deleted, which is to say they pass because it never existed. Do not read a green run
 * here as "recovery works". Naming test 1 for delivery rather than for the feature is deliberate:
 * `obscura-proto/PLAN.md`'s F-findings are largely about green ticks over behaviour nobody
 * implemented, and this was one of them.
 *
 * The gap is a **deliberate deferral, not a bug** (`obscura-proto/KIT_API.md` §4.2):
 * `ObscuraConfig.enableRecoveryPhrase` defaults to `false` and obscura-pix never sets it, so nothing
 * in the running app can emit this message. This suite reaches it only by opting in explicitly
 * (`recoveryConfig`, below). When the handler is built, extend test 1 to assert the recipient's
 * device list actually changed — and verify the signature against the **stored** recovery key, never
 * the `recovery_public_key` carried inside the message it authenticates.
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

    /**
     * DELIVERY ONLY — see the class doc. Asserts the announcement reaches Bob's device, not that
     * Bob does anything with it. Nothing does: there is no `DEVICE_RECOVERY_ANNOUNCE` handler.
     */
    @Test @Order(1)
    fun `DEVICE_RECOVERY_ANNOUNCE is delivered to Bob (no handler consumes it)`() = runBlocking {
        need()

        alice!!.announceRecovery(aliceRecoveryPhrase!!)

        val msg = bob!!.waitForMessage()
        assertEquals("DEVICE_RECOVERY_ANNOUNCE", msg.type,
            "Bob's device should receive the announcement off the wire")
    }

    @Test @Order(2)
    fun `Messaging continues after recovery announcement`() = runBlocking {
        need()

        sendAndVerify(alice!!, bob!!, "I recovered my account!")
        sendAndVerify(bob!!, alice!!, "Glad you're back!")

        // Verify conversations state
        delay(300)
        val aliceMsgs = alice!!.getMessages(bob!!.userId!!)

        val bobMsgs = bob!!.getMessages(alice!!.userId!!)

        alice!!.disconnect(); bob!!.disconnect()
    }
}
