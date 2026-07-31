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

        sendAndVerify(bob, alice, "attribute me correctly")

        val received = alice.waitForMessage(15_000)
        assertEquals("attribute me correctly", received.content())
        assertEquals(bobUserId, received.sourceUserId, "sourceUserId is Bob's USER id")

        // The wake-up carries the sender's real DEVICE UUID (from Envelope.sender_device_id,
        // validated by the decrypting session's MAC) — not the user id.
        assertEquals(bobDeviceId, received.senderDeviceId,
            "senderDeviceId must be Bob's REAL device UUID")
        assertNotEquals(bobUserId, received.senderDeviceId,
            "senderDeviceId must NOT be the user id (that was the F4 lie)")

        delay(500)

        // The DURABLY PERSISTED record carries the honest device id too — and THIS is the assertion
        // that matters, because the wake-up above is droppable while the row is the delivery path.
        //
        // It reads the INBOX rather than `getMessages`: a MODEL_SYNC never reaches `MessageDomain`
        // (only the legacy TEXT arm and SENT_SYNC do), and `senderDeviceId` on the row is the
        // address of the Signal session that decrypted it — cryptographic attribution, SPEC §0.10
        // rule 4. That is precisely the field F4 got wrong.
        val row = alice.inbox.peek(200).find {
            org.json.JSONObject(String(it.payload)).optString("content", "") == "attribute me correctly"
        }
        assertNotNull(row, "message must be persisted in Alice's inbox")
        assertEquals(bobDeviceId, row!!.senderDeviceId,
            "persisted senderDeviceId must be Bob's REAL device UUID")
        assertNotEquals(bobUserId, row.senderDeviceId,
            "persisted senderDeviceId must NOT be the user id (that was the F4 lie)")

        println("PROVEN: senderDeviceId=${row.senderDeviceId} == bob.deviceId=$bobDeviceId " +
            "(bob.userId=$bobUserId)")

        alice.disconnect(); bob.disconnect()
    }
}
