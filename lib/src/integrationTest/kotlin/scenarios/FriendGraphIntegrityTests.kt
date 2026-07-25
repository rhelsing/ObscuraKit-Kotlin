package scenarios

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import obscura.client.v1.Client.ClientMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import com.obscura.kit.stores.FriendStatus

/**
 * The friend graph is the ONLY source of display names (SPEC §0.5, §0.10 rule 5), and Phase 3's
 * thin kit API puts that name on OS notifications. So a peer's ability to influence its own record
 * is a lock-screen spoofing surface, not a cosmetic bug.
 *
 * Both tests below FAIL against the code as it stood on 2026-07-25, and both drive the attack
 * through the sender's genuine encryption path — no fabricated envelopes, no injected wire fields.
 * What they send is exactly what a malicious peer running a patched kit would send.
 *
 * Attack 1 — self-rename + status downgrade. `handleFriendRequest` called `friends.add()`
 * unconditionally and `Friend.sq` is `INSERT OR REPLACE`, so an ALREADY-ACCEPTED friend could
 * re-send a FriendRequest to rewrite their stored username and reset their status to
 * PENDING_RECEIVED — dropping themselves out of `getAccepted()` and out of fan-out on the way.
 *
 * Attack 2 — unsolicited acceptance. `handleFriendResponse` called `friends.add(..., ACCEPTED)`
 * whenever `accepted` was true, without checking that we had ever sent a request. Friendship is not
 * required to deliver a message — the server relays to any device of any user — so any
 * authenticated stranger could insert themselves as an ACCEPTED friend under a chosen name.
 */
class FriendGraphIntegrityTests {

    companion object {
        private var serverUp = false
        @BeforeAll @JvmStatic fun setup() { serverUp = checkServer() }
    }

    private fun need() = assumeTrue(serverUp)

    @Test
    fun `an accepted friend cannot rename itself or reset its own status`() = runBlocking {
        need()

        val alice = registerAndConnect("fgi_alice")
        val mallory = registerAndConnect("fgi_mallory")
        becomeFriends(mallory, alice)
        delay(500)

        val malloryId = mallory.userId!!
        val storedBefore = alice.friendList.value.find { it.userId == malloryId }
        assertNotNull(storedBefore, "Mallory must be in Alice's graph before the attack")
        assertEquals(FriendStatus.ACCEPTED, storedBefore!!.status, "precondition: accepted")
        val realName = storedBefore.username

        // THE ATTACK: a second FriendRequest from an established friend, claiming a new name.
        // Sent through Mallory's real session — this is a well-formed, authentic message.
        val spoof = ClientMessage.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .setFriendRequest(obscura.client.v1.friendRequest { username = "Mum" })
            .build()
        mallory.messenger.queueMessage(alice.deviceId!!, spoof, alice.userId!!)
        mallory.messenger.flushMessages()

        // Let Alice ingest it.
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            try { alice.waitForMessage(1_500) } catch (_: Exception) {}
            if (System.currentTimeMillis() > deadline - 6_000) break
        }
        delay(500)

        val after = alice.friendList.value.find { it.userId == malloryId }
        assertNotNull(after, "Mallory must still be in the graph")
        println("  stored name before='$realName' after='${after!!.username}' status=${after.status}")

        assertEquals(realName, after.username,
            "a peer MUST NOT be able to rewrite its own display name — that name reaches the lock screen")
        assertNotEquals("Mum", after.username, "the payload-supplied name must be ignored for a known peer")
        assertEquals(FriendStatus.ACCEPTED, after.status,
            "a peer MUST NOT be able to reset its own status — that silently removes it from fan-out")

        alice.disconnect(); mallory.disconnect()
    }

    @Test
    fun `a stranger cannot become an accepted friend by sending an unsolicited acceptance`() = runBlocking {
        need()

        val alice = registerAndConnect("fgi_victim")
        val stranger = registerAndConnect("fgi_stranger")
        delay(500)

        // No friendship in either direction. The stranger fetches Alice's prekeys (public to any
        // authenticated user) and sends a FRIEND_RESPONSE claiming Alice accepted them.
        stranger.messenger.fetchPreKeyBundles(alice.userId!!)
        val forged = ClientMessage.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .setFriendResponse(obscura.client.v1.friendResponse {
                username = "Mum"
                accepted = true
            })
            .build()
        stranger.messenger.queueMessage(alice.deviceId!!, forged, alice.userId!!)
        stranger.messenger.flushMessages()

        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            try { alice.waitForMessage(1_500) } catch (_: Exception) {}
            if (System.currentTimeMillis() > deadline - 6_000) break
        }
        delay(500)

        val injected = alice.friendList.value.find { it.userId == stranger.userId }
        println("  alice's graph after unsolicited acceptance: ${alice.friendList.value.map { it.username to it.status }}")
        assertTrue(injected == null || injected.status != FriendStatus.ACCEPTED,
            "an unsolicited FRIEND_RESPONSE MUST NOT create an accepted friend — got $injected")

        alice.disconnect(); stranger.disconnect()
    }
}
