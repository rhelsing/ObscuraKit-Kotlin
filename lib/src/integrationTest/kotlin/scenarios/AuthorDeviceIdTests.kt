package scenarios

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * PLAN.md Phase 2 acceptance — `authorDeviceId` is HONEST.
 *
 * F4/F1 background: pre-Phase-2, the Envelope carried no sender device, so decrypt brute-forced
 * candidate registrationIds and `authorDeviceId` fell back to `sourceUserId` — a USER id in a
 * DEVICE field, a false security claim. Phase 2 stamps `Envelope.sender_device_id` (server-side,
 * from the sender's device-scoped JWT) and derives attribution from the address of the session
 * that decrypted: a valid MAC proves possession of that session's chain key, which only the
 * sender's device holds.
 *
 * This test asserts that a message Alice receives from Bob is attributed to Bob's REAL DEVICE
 * UUID — both on the wake-up (`ReceivedMessage.senderDeviceId`) and on the durably persisted
 * message (`MessageData.authorDeviceId`) — and that it is emphatically NOT Bob's user id.
 */
class AuthorDeviceIdTests {

    companion object {
        private var serverUp = false
        @BeforeAll @JvmStatic fun setup() { serverUp = checkServer() }
    }

    private fun need() = assumeTrue(serverUp)

    @Test
    fun `received message authorDeviceId equals the sender's real device UUID, never the userId`() = runBlocking {
        need()

        val alice = registerAndConnect("adid_alice")
        val bob = registerAndConnect("adid_bob")
        becomeFriends(bob, alice)
        delay(500)

        val bobDeviceId = bob.deviceId!!
        val bobUserId = bob.userId!!
        assertNotEquals(bobDeviceId, bobUserId, "device UUID and user UUID must differ for this test to mean anything")

        bob.send(alice.username!!, "attribute me correctly")

        val received = alice.waitForMessage(15_000)
        assertEquals("attribute me correctly", received.text)
        assertEquals(bobUserId, received.sourceUserId, "sourceUserId is Bob's USER id")

        // The wake-up carries the sender's real DEVICE UUID (from Envelope.sender_device_id,
        // validated by the decrypting session's MAC) — not the user id.
        assertEquals(bobDeviceId, received.senderDeviceId,
            "senderDeviceId must be Bob's REAL device UUID")
        assertNotEquals(bobUserId, received.senderDeviceId,
            "senderDeviceId must NOT be the user id (that was the F4 lie)")

        delay(500)

        // The DURABLY PERSISTED message records the honest device id too.
        val persisted = alice.getMessages(bobUserId)
        val msg = persisted.find { it.content == "attribute me correctly" }
        assertNotNull(msg, "message must be persisted in Alice's conversation with Bob")
        assertEquals(bobDeviceId, msg!!.authorDeviceId,
            "persisted authorDeviceId must be Bob's REAL device UUID")
        assertNotEquals(bobUserId, msg.authorDeviceId,
            "persisted authorDeviceId must NOT be the user id")

        println("PROVEN: authorDeviceId=${msg.authorDeviceId} == bob.deviceId=$bobDeviceId " +
            "(bob.userId=$bobUserId)")

        alice.disconnect(); bob.disconnect()
    }
}
