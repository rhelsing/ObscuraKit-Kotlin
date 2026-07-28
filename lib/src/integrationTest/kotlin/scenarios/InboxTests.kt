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
        // The APP-FACING spelling (KIT_API.md §3.1), not the proto's `OP_CREATE`. This assertion
        // exists because the first version of this code stored `sync.op.name` and nothing caught it
        // until the Swift port was written — the app reads one `op` across a bridge from two kits,
        // so `CREATE` here and `opCreate` there is a bug the app discovers, not the kits.
        assertEquals("CREATE", row.op)
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
     * **Deliberately absent: an end-to-end redelivery test.**
     *
     * There was one here, and it could not fail. It sent one entry, let the kit persist AND ACK it,
     * then reconnected and asserted `depth() == 1`. But the ack had already told the server to
     * delete its copy, so the reconnect redelivered nothing — the assertion held trivially, and it
     * held just as well with `INSERT OR IGNORE` replaced by `INSERT` and the UNIQUE constraint
     * dropped. It named §3.3 rule 8 without exercising it.
     *
     * Producing a genuine redelivery needs the ack suppressed for the first delivery, which needs a
     * seam in `GatewayConnection` that does not exist. Rather than keep a green tick over nothing,
     * the property is covered where it can actually be exercised:
     * `InboxDomainTest.a redelivered envelope does not create a second row` calls `put` twice with
     * one envelope id and asserts one row — a real test of the real constraint.
     *
     * Add the seam and the test together if this ever needs end-to-end coverage. Do not add the test
     * back without it.
     */
}
