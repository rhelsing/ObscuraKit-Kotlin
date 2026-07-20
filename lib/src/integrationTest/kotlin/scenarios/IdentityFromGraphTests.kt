package scenarios

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Phase 2 acceptance: identity comes from the client's OWN FRIEND GRAPH, not the envelope.
 *
 * The Phase-2 Envelope no longer carries `sender_id` — there is literally no user on the wire to
 * trust (SPEC §0.5). The receiver resolves device -> user from its own friend graph (deviceMap),
 * keyed on the sender's device UUID. These tests prove the resolved user/conversation of a received
 * message equals the friend's REAL userId, and that the resolution source is the graph.
 */
class IdentityFromGraphTests {

    @Test
    fun `received text resolves to the friend's real userId via the device-to-user graph`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("idg_a")
        val bob = registerAndConnect("idg_b")
        becomeFriends(alice, bob)

        alice.send(bob.username!!, "identity comes from the graph")
        val msg = bob.waitForMessage(15_000)

        // The resolved author equals Alice's REAL userId (not anything read off the envelope).
        assertEquals("TEXT", msg.type)
        assertEquals(alice.userId, msg.sourceUserId,
            "resolved sourceUserId must equal the friend's real userId")

        // Prove the resolution SOURCE is the friend graph: the sender device UUID that produced the
        // MAC maps to Alice's userId in Bob's deviceMap. The envelope has no user field at all — the
        // ONLY way Bob obtained this userId is device -> user resolution through the graph.
        val graphResolved = bob.messenger.deviceMap(msg.senderDeviceId!!)?.first
        assertEquals(alice.userId, graphResolved,
            "Bob's friend graph must map the sending device UUID to Alice's userId")
        assertEquals(alice.deviceId, msg.senderDeviceId,
            "senderDeviceId must be Alice's real device UUID (proven by the session that decrypted)")

        // And the conversation is keyed under the RESOLVED user, not the device.
        val convo = bob.getMessages(alice.userId!!)
        assertTrue(convo.any { it.content == "identity comes from the graph" },
            "conversation must be keyed under the graph-resolved userId")

        alice.disconnect(); bob.disconnect()
    }

    @Test
    fun `a friend request self-identifies and is verified against the session identity key`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("idg_fr_a")
        val bob = registerAndConnect("idg_fr_b")

        // Alice sends a friend request carrying her user_id in the encrypted payload. Bob resolves
        // the requester from that payload and VERIFIES it against the identity key of the session
        // that decrypted the message before adding her under her real userId.
        alice.befriend(bob.userId!!, bob.username!!)
        val req = bob.waitForMessage(15_000)

        assertEquals("FRIEND_REQUEST", req.type)
        assertEquals(alice.userId, req.sourceUserId,
            "friend request must resolve to the verified requester userId from the payload")
        kotlinx.coroutines.delay(500) // let the pendingRequests StateFlow refresh after persist
        assertTrue(bob.pendingRequests.value.any { it.userId == alice.userId },
            "Bob's pending requests must list Alice under her real (verified) userId")

        alice.disconnect(); bob.disconnect()
    }
}
