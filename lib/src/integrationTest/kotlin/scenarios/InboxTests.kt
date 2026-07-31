package scenarios

import com.obscura.kit.ObscuraClient
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

    // No schema is defined anywhere in this file. The inbox does not need one — `modelKey` is an
    // opaque namespace string — and after §10 step 4 there is nothing to define it with.

    /** Send an entry the way obscura-pix does: the caller names the recipient (SPEC §0.4). */
    private suspend fun sendStory(from: ObscuraClient, to: ObscuraClient, entryId: String, content: String) =
        from.send(
            recipientUserIds = listOf(to.userId!!),
            modelKey = "story",
            entryId = entryId,
            payload = org.json.JSONObject(mapOf("content" to content)).toString().toByteArray(),
        )

    @Test
    fun `a received MODEL_SYNC lands in the inbox with authenticated identity`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("inbox_a")
        val bob = registerAndConnect("inbox_b")
        becomeFriends(alice, bob)

        assertEquals(0L, bob.inbox.depth(), "inbox starts empty")

        sendStory(alice, bob, "story_7557", "hello from the wire")
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
     * **Removed 2026-07-29: "the ORM still receives the same entry while the inbox runs alongside".**
     *
     * That test pinned the dual write, and the dual write is now deliberately gone — keeping it
     * would assert the bug it was written to protect. The ORM's parallel write did not merely
     * duplicate: it wrote `sync.authorDeviceId` (peer-asserted, wire field 7) and an unclamped
     * timestamp, and APPEND first-write-wins meant the app kept THAT row instead of the one carrying
     * the authenticated `senderDeviceId`. See `ObscuraClient.inboxMessage`.
     *
     * The invariant it was protecting — "adding the inbox must not take anything away" — held, and
     * is now covered by the fact that obscura-pix reads only the entry store.
     */

    /**
     * **SPEC §2.4: a peer-supplied timestamp is clamped BEFORE it is stored.**
     *
     * This property lost its only Kotlin coverage when the CRDT engine was deleted — `LWWMapTest`
     * held the clamp tests, and the surviving implementation is `ObscuraClient.clampFutureTimestamp`
     * on the inbox path. `RESET.md` explicitly says to KEEP §2.4, so it needs a test that outlives
     * the engine.
     *
     * Without the clamp a peer sets `sentAt` far in the future and wins every REPLACE conflict
     * forever: the tie-break can only order writes it can compare honestly.
     *
     * `Long.MAX_VALUE` also exercises the subtle half. `ModelSync.timestamp` is proto3 `uint64`,
     * which protobuf-java surfaces as a SIGNED Long — so a large enough value arrives NEGATIVE and
     * sails under a naive `minOf` cap. Both ends are clamped.
     */
    @Test
    fun `a far-future peer timestamp is clamped before it is stored`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("clamp_a")
        val bob = registerAndConnect("clamp_b")
        becomeFriends(alice, bob)

        val before = System.currentTimeMillis()
        alice.send(
            recipientUserIds = listOf(bob.userId!!),
            modelKey = "story",
            entryId = "story_clamped",
            sentAt = Long.MAX_VALUE,
            payload = """{"content":"from the far future"}""".toByteArray(),
        )
        delay(3000)

        val row = bob.inbox.peek().find { it.entryId == "story_clamped" }
        assertTrue(row != null, "the entry should still arrive — clamping is not rejection")
        assertTrue(row!!.sentAt!! <= before + 120_000,
            "sentAt must be clamped to about now, not stored as ${row.sentAt}")
        assertTrue(row.sentAt!! > 0, "a signed-Long overflow must not store a negative timestamp")

        alice.disconnect(); bob.disconnect()
    }

    /**
     * **Offline delivery, relocated from `ORMMessageTests`** when the ORM's receive path was
     * disconnected. The behaviour is the whole point of a durable inbox: the server holds what it
     * could not deliver, and hands it over on reconnect.
     *
     * This is also the case the drain's cold-start trigger exists for — a message that arrives while
     * the app is not listening must still reach the app.
     */
    @Test
    fun `a message sent while the recipient is offline arrives after reconnect`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("inboxoff_a")
        val bob = registerAndConnect("inboxoff_b")
        becomeFriends(alice, bob)

        bob.disconnect()
        delay(500)

        alice.send(
            recipientUserIds = listOf(bob.userId!!),
            modelKey = "directMessage",
            entryId = "dm_offline",
            payload = """{"content":"You there?"}""".toByteArray(),
        )
        delay(500)

        bob.connect()
        delay(4000)

        val rows = bob.inbox.peek()
        assertEquals(1, rows.size, "the server held it and delivered it on reconnect")
        assertEquals("dm_offline", rows[0].entryId)
        assertEquals("""{"content":"You there?"}""", String(rows[0].payload))

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

        sendStory(alice, bob, "story_one", "one")
        delay(1500)
        sendStory(alice, bob, "story_two", "two")
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
