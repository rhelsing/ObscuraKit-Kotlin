package scenarios

import com.obscura.kit.orm.ModelConfig
import com.obscura.kit.orm.SyncStrategy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The durable inbox, end to end against a real server (`obscura-proto/KIT_API.md` §3).
 *
 * `InboxDomainTest` covers the store's rules in isolation. This file covers the part that cannot be
 * faked: that a real MODEL_SYNC, sent by a real peer over a real Signal session and delivered by the
 * real gateway, arrives as an inbox row with the right authenticated identity on it.
 *
 * **The inbox runs alongside the ORM here on purpose** (`KIT_API.md` §10 step 2). obscura-pix still
 * reads through the ORM, so both are populated until pix switches and the ORM is deleted. Several
 * assertions below deliberately check both, because "the inbox filled" and "the old path still
 * works" are exactly the two things that must hold simultaneously during the migration.
 */
class InboxTests {

    private fun schema() = mapOf(
        "story" to ModelConfig(
            fields = mapOf("content" to "string", "author" to "string"),
            sync = SyncStrategy.GSET,
        )
    )

    @Test
    fun `a received MODEL_SYNC lands in the inbox with authenticated identity`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("inbox_a")
        val bob = registerAndConnect("inbox_b")
        becomeFriends(alice, bob)
        alice.orm.define(schema())
        bob.orm.define(schema())

        assertEquals(0L, bob.inbox.depth(), "inbox starts empty")

        alice.orm.model("story").create(
            mapOf("content" to "hello from the wire", "author" to alice.username!!)
        )
        delay(3000)

        val rows = bob.inbox.peek()
        assertEquals(1, rows.size, "exactly one inbox row for one MODEL_SYNC")
        val row = rows[0]

        assertEquals("MODEL_SYNC", row.kind)
        assertEquals("story", row.modelKey, "modelKey is carried opaquely so the app can merge")
        // Identity comes from the envelope and the Signal session, never from the payload
        // (SPEC §0.5, §0.10). This is the assertion a unit test cannot make honestly.
        assertEquals(alice.userId, row.senderUserId)
        assertEquals(alice.deviceId, row.senderDeviceId,
            "senderDeviceId is the address of the session that decrypted — cryptographic attribution")
        assertEquals(alice.username, row.senderDisplayName,
            "display name is resolved from BOB's friend graph, not from anything Alice sent")
        assertTrue(row.payload.isNotEmpty(), "payload is the opaque model data")

        alice.disconnect(); bob.disconnect()
    }

    /**
     * The migration invariant for §10 step 2: adding the inbox must not take anything away from the
     * ORM, because obscura-pix is still reading through it and will be until step 3.
     */
    @Test
    fun `the ORM still receives the same entry while the inbox is running alongside`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("inboxorm_a")
        val bob = registerAndConnect("inboxorm_b")
        becomeFriends(alice, bob)
        alice.orm.define(schema())
        bob.orm.define(schema())

        alice.orm.model("story").create(mapOf("content" to "both paths", "author" to alice.username!!))
        delay(3000)

        assertEquals(1, bob.orm.model("story").all().size, "the ORM path must keep working")
        assertEquals(1L, bob.inbox.depth(), "and the inbox must fill in parallel")

        alice.disconnect(); bob.disconnect()
    }

    /**
     * The full drain, which is the thing the app will actually do: peek, process, consume, and the
     * depth returns to zero. Also pins that consume is what empties it — nothing else does, because
     * once acked this row is the only copy of the message anywhere.
     */
    @Test
    fun `the app drains the inbox with peek then consume`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("inboxdrain_a")
        val bob = registerAndConnect("inboxdrain_b")
        becomeFriends(alice, bob)
        alice.orm.define(schema())
        bob.orm.define(schema())

        val story = alice.orm.model("story")
        story.create(mapOf("content" to "one", "author" to alice.username!!))
        delay(1500)
        story.create(mapOf("content" to "two", "author" to alice.username!!))
        delay(3000)

        assertEquals(2L, bob.inbox.depth())

        val batch = bob.inbox.peek()
        assertEquals(2, batch.size)
        // Drain order is arrival order.
        assertTrue(batch[0].id < batch[1].id)

        // Processing half is normal — partial progress must be accepted.
        bob.inbox.consume(listOf(batch[0].id))
        assertEquals(1L, bob.inbox.depth())

        bob.inbox.consume(listOf(batch[1].id))
        assertEquals(0L, bob.inbox.depth())

        alice.disconnect(); bob.disconnect()
    }

    /**
     * Reconnecting re-delivers anything the server did not see acked, which is by design. What must
     * NOT happen is a second row for the same envelope — `envelope_id UNIQUE` + `INSERT OR IGNORE`
     * is what holds that line (§3.3 rule 8), and it is load-bearing precisely because redelivery is
     * guaranteed rather than exceptional.
     */
    @Test
    fun `reconnecting does not duplicate rows already in the inbox`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("inboxdup_a")
        val bob = registerAndConnect("inboxdup_b")
        becomeFriends(alice, bob)
        alice.orm.define(schema())
        bob.orm.define(schema())

        alice.orm.model("story").create(mapOf("content" to "once", "author" to alice.username!!))
        delay(3000)
        assertEquals(1L, bob.inbox.depth())

        // Deliberately do NOT consume: the row stays, and a reconnect may redeliver the envelope.
        bob.disconnect()
        delay(1000)
        bob.connect()
        delay(3000)

        assertEquals(1L, bob.inbox.depth(),
            "a redelivered envelope must be absorbed by the dedupe key, not stored twice")

        alice.disconnect(); bob.disconnect()
    }
}
