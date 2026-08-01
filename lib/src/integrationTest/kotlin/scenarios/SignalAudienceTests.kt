package scenarios

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Who receives an ephemeral signal.
 *
 * `SignalECSTests` proves a typing indicator *arrives*. Nothing proved it did not arrive
 * somewhere it shouldn't, and it did: the send path fanned every MODEL_SIGNAL out to
 * `friends.getAccepted()` while `contextId` carried the canonical two-party
 * `"userIdA_userIdB"` conversation id. Every accepted friend therefore learned, in real
 * time, that you were typing to a *named* third party.
 *
 * These tests need three participants to see it — a two-party test cannot distinguish
 * "sent to the conversation" from "sent to everyone", which is why the existing suite
 * passed throughout.
 *
 * The rule under test is the one `SyncManager`'s `Audience.Conversation` already applied
 * to entries (`SPEC.md` §0.4 — the kit does not invent an audience): resolve the two
 * participants, send to the peer, and **fail closed** if the id does not resolve.
 */
class SignalAudienceTests {

    // Signals need no schema — `modelKey` is an opaque namespace string, exactly as it is on the
    // inbox and the entry store. This file proved that before §10 step 4 and is why signals survived
    // the ORM and its schema going.

    @Test
    fun `typing reaches the conversation peer and NOT an uninvolved friend`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("sig_aud_a")
        val bob = registerAndConnect("sig_aud_b")
        val carol = registerAndConnect("sig_aud_c")

        // Carol is Alice's friend but is NOT in the Alice↔Bob conversation. That is the
        // whole point: before the fix she received the signal, contextId included.
        becomeFriends(alice, bob)
        becomeFriends(alice, carol)


        val convId = listOf(alice.userId!!, bob.userId!!).sorted().joinToString("_")
        val aliceName = alice.username!!

        alice.sendTyping("directMessage", convId)

        // Bob must still get it — a fix that silences the feature is not a fix.
        val bobSees = withTimeout(15_000) {
            bob.observeTyping("directMessage", convId).first { it.contains(aliceName) }
        }
        assertTrue(bobSees.contains(aliceName), "the conversation peer must still receive the signal")

        // Carol must not, on the conversation Alice is actually typing in...
        val carolSeesConv = withTimeoutOrNull(3_000) {
            carol.observeTyping("directMessage", convId).first { it.isNotEmpty() }
        }
        assertNull(
            carolSeesConv,
            "LEAK: an uninvolved friend received a 1:1 typing signal carrying the conversation id"
        )

        // ...nor on any conversation of her own with Alice. Belt and braces: this catches a
        // "fix" that merely rewrites contextId while still fanning the message out.
        val carolConvId = listOf(alice.userId!!, carol.userId!!).sorted().joinToString("_")
        val carolSeesOwn = withTimeoutOrNull(2_000) {
            carol.observeTyping("directMessage", carolConvId).first { it.isNotEmpty() }
        }
        assertNull(carolSeesOwn, "LEAK: signal was re-scoped but still delivered to an uninvolved friend")

        listOf(alice, bob, carol).forEach { it.disconnect() }
    }

    @Test
    fun `a malformed conversation id sends nothing at all`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("sig_mal_a")
        val bob = registerAndConnect("sig_mal_b")
        becomeFriends(alice, bob)


        // Not a canonical two-party id. The old code shrugged and broadcast; `SPEC.md` §1.2
        // says fail loud rather than guess an audience, and for an ephemeral signal the
        // correct failure is to send nothing — dropping a typing indicator costs nothing,
        // guessing its audience leaks the conversation.
        alice.sendTyping("directMessage", "not-a-conversation-id")

        val leaked = withTimeoutOrNull(3_000) {
            bob.observeTyping("directMessage", "not-a-conversation-id").first { it.isNotEmpty() }
        }
        assertNull(leaked, "a signal whose audience cannot be resolved must not be sent to anyone")

        // The client must still work afterwards — fail-closed, not fall-over.
        val convId = listOf(alice.userId!!, bob.userId!!).sorted().joinToString("_")
        alice.sendTyping("directMessage", convId)
        val recovered = withTimeout(15_000) {
            bob.observeTyping("directMessage", convId).first { it.contains(alice.username!!) }
        }
        assertTrue(recovered.isNotEmpty(), "a dropped signal must not wedge the send path")

        listOf(alice, bob).forEach { it.disconnect() }
    }

    @Test
    fun `a three-party conversation id is refused rather than guessed`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("sig_3p_a")
        val bob = registerAndConnect("sig_3p_b")
        val carol = registerAndConnect("sig_3p_c")
        becomeFriends(alice, bob)
        becomeFriends(alice, carol)


        // This is `routing.json`'s "LEAK GUARD: conversation with a malformed 3-party id must
        // fail loud, never broadcast", asserted on the signal path rather than the entry path.
        val threeParty = listOf(alice.userId!!, bob.userId!!, carol.userId!!).sorted().joinToString("_")
        alice.sendTyping("directMessage", threeParty)

        delay(2_000)
        for ((who, client) in listOf("bob" to bob, "carol" to carol)) {
            val seen = withTimeoutOrNull(1_000) {
                client.observeTyping("directMessage", threeParty).first { it.isNotEmpty() }
            }
            assertNull(seen, "$who received a signal for an id that names three participants")
        }

        listOf(alice, bob, carol).forEach { it.disconnect() }
    }
}
