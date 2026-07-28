package com.obscura.kit.stores

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The durable inbox (`obscura-proto/KIT_API.md` §3).
 *
 * These test the properties the design is *for*, not the SQL. Each one corresponds to a normative
 * rule, and most of them exist because getting the rule wrong loses messages permanently — the row
 * is the only copy once the kit has acked, because **an ACK is a DELETE**.
 */
class InboxDomainTest {

    private lateinit var db: ObscuraDatabase
    private lateinit var inbox: InboxDomain

    @BeforeEach
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        db = ObscuraDatabase(driver)
        inbox = InboxDomain(db)
    }

    private fun record(
        envelopeId: String,
        kind: String = "MODEL_SYNC",
        payload: ByteArray = "{}".toByteArray(),
        modelKey: String? = "directMessage",
    ) = InboxRecord(
        id = 0,
        envelopeId = envelopeId,
        kind = kind,
        receivedAt = 1_700_000_000_000,
        senderUserId = "user_peer",
        senderDeviceId = "device_peer",
        senderDisplayName = "alice",
        modelKey = modelKey,
        entryId = "entry_1",
        op = "CREATE",
        sentAt = 1_700_000_000_000,
        payload = payload,
    )

    // ── §3.3 rule 8: idempotence ──────────────────────────────────────────────

    /**
     * The rule the whole design leans on. Persist-then-ack **guarantees** redelivery: the ack is
     * best-effort and its failure is swallowed, so the server's per-connection cursor re-sends on
     * the next connection. That is correct behaviour — losing the message would be worse.
     *
     * Today that redelivery is harmless only because of the engine being deleted: `ModelStore.put`
     * is `INSERT OR REPLACE`, so a re-delivered entry overwrites itself. Put a monotonic id in front
     * of it without a dedupe key and the same redelivery inserts a SECOND row.
     */
    @Test
    fun `a redelivered envelope does not create a second row`() = runBlocking {
        assertTrue(inbox.put(record("env_1")), "first insert should create a row")
        assertFalse(inbox.put(record("env_1")), "redelivery must be absorbed, not duplicated")

        assertEquals(1L, inbox.depth())
    }

    /**
     * `put` returning false is a *successful* absorption, not a failure — the message is already
     * durably held. The caller must still ack, because acking is what stops the server sending it a
     * third time. This pins the contract that makes that safe to rely on.
     */
    @Test
    fun `redelivery keeps the originally stored row rather than replacing it`() = runBlocking {
        inbox.put(record("env_1", payload = "original".toByteArray()))
        inbox.put(record("env_1", payload = "tampered".toByteArray()))

        val rows = inbox.peek()
        assertEquals(1, rows.size)
        assertEquals("original", String(rows[0].payload),
            "INSERT OR IGNORE keeps the first write; a later copy of the same envelope cannot overwrite it")
    }

    // ── §3.3 rule 3: peek is side-effect free ─────────────────────────────────

    /**
     * The crash-safety property, stated as a test because it reads like a bug otherwise: draining
     * twice without consuming returns the same rows. An app that dies between peek and consume
     * reprocesses them, and the merge rules downstream are idempotent so that converges.
     */
    @Test
    fun `peeking twice without consuming returns the same rows`() = runBlocking {
        inbox.put(record("env_1"))
        inbox.put(record("env_2"))

        val first = inbox.peek()
        val second = inbox.peek()

        assertEquals(2, first.size)
        assertEquals(first, second)
        assertEquals(2L, inbox.depth(), "peek must not consume")
    }

    /** Drain order is oldest-first, because the app processes in arrival order. */
    @Test
    fun `peek returns rows in insertion order and respects the limit`() = runBlocking {
        repeat(5) { inbox.put(record("env_$it")) }

        val rows = inbox.peek(limit = 3)

        assertEquals(listOf("env_0", "env_1", "env_2"), rows.map { it.envelopeId })
    }

    /**
     * `id` must be monotonic across the whole install, and that is why the column is AUTOINCREMENT.
     * A plain INTEGER PRIMARY KEY aliases rowid, which SQLite **reuses after deletion** — so a
     * drained-then-refilled inbox would hand out ids that go backwards, and `peek` orders by id.
     */
    @Test
    fun `ids keep increasing after rows are consumed`() = runBlocking {
        inbox.put(record("env_1"))
        inbox.put(record("env_2"))
        val firstBatch = inbox.peek()
        inbox.consume(firstBatch.map { it.id })

        inbox.put(record("env_3"))
        val afterDrain = inbox.peek().single()

        assertTrue(afterDrain.id > firstBatch.maxOf { it.id },
            "rowid reuse would make drain order go backwards; AUTOINCREMENT is what prevents it")
    }

    // ── §3.3 rules 2, 4, 5: removal ───────────────────────────────────────────

    @Test
    fun `consume is idempotent and accepts a subset`() = runBlocking {
        repeat(3) { inbox.put(record("env_$it")) }
        val rows = inbox.peek()

        inbox.consume(listOf(rows[0].id))
        inbox.consume(listOf(rows[0].id)) // again — partial progress is normal, not an error

        assertEquals(2L, inbox.depth())
        assertEquals(listOf("env_1", "env_2"), inbox.peek().map { it.envelopeId })
    }

    @Test
    fun `consume of an empty list is a no-op`() = runBlocking {
        inbox.put(record("env_1"))

        inbox.consume(emptyList())

        assertEquals(1L, inbox.depth())
    }

    /**
     * A discard is data loss the app chose — the server's copy is already gone, so nothing else
     * holds these bytes. §3.3 rule 5 requires it be logged as a security-relevant event, and this
     * pins that the hook actually fires. It is the entire reason discard is a separate method from
     * consume rather than a flag: the SQL is identical, the accountability is not.
     */
    @Test
    fun `discard removes rows and reports them for the security log`() = runBlocking {
        inbox.put(record("env_1"))
        inbox.put(record("env_2"))
        val logged = mutableListOf<Pair<List<Long>, String>>()
        inbox.onDiscard = { ids, reason -> logged.add(ids to reason) }

        val rows = inbox.peek()
        inbox.discard(listOf(rows[0].id), reason = "unknown modelKey from a newer peer")

        assertEquals(1L, inbox.depth())
        assertEquals(1, logged.size)
        assertEquals("unknown modelKey from a newer peer", logged[0].second)
    }

    @Test
    fun `discarding nothing does not log a security event`() = runBlocking {
        var calls = 0
        inbox.onDiscard = { _, _ -> calls++ }

        inbox.discard(emptyList(), reason = "nothing to do")

        assertEquals(0, calls, "an empty discard is not a data-loss event and must not read as one")
    }

    // ── §3.3 rule 7: depth ────────────────────────────────────────────────────

    @Test
    fun `depth reflects what is waiting`() = runBlocking {
        assertEquals(0L, inbox.depth())
        repeat(4) { inbox.put(record("env_$it")) }
        assertEquals(4L, inbox.depth())

        inbox.consume(inbox.peek(limit = 2).map { it.id })

        assertEquals(2L, inbox.depth())
    }

    // ── §3.1: the record ──────────────────────────────────────────────────────

    @Test
    fun `every field survives a round trip, including opaque payload bytes`() = runBlocking {
        val payload = byteArrayOf(0, 1, 2, -1, 127, -128) // deliberately not valid UTF-8
        inbox.put(record("env_1", payload = payload))

        val row = inbox.peek().single()

        assertEquals("env_1", row.envelopeId)
        assertEquals("MODEL_SYNC", row.kind)
        assertEquals("user_peer", row.senderUserId)
        assertEquals("device_peer", row.senderDeviceId)
        assertEquals("alice", row.senderDisplayName)
        assertEquals("directMessage", row.modelKey)
        assertEquals("entry_1", row.entryId)
        assertEquals("CREATE", row.op)
        assertArrayEquals(payload, row.payload, "payload is opaque bytes and must not be re-encoded")
    }

    /**
     * An unknown arm has no ModelSync to derive from, so those columns are null (§4.1). The row
     * still exists, which is the point — the message is preserved rather than destroyed.
     */
    @Test
    fun `an unknown arm is stored with null ModelSync fields`() = runBlocking {
        inbox.put(
            record("env_1", kind = "UNKNOWN_ARM", modelKey = null)
                .copy(entryId = null, op = null, sentAt = null)
        )

        val row = inbox.peek().single()

        assertEquals("UNKNOWN_ARM", row.kind)
        assertNull(row.modelKey)
        assertNull(row.entryId)
        assertNull(row.op)
        assertNull(row.sentAt)
    }

    // ── §3.3 rule 2 carve-out ─────────────────────────────────────────────────

    /**
     * A device wipe or remote revocation must be able to destroy decrypted plaintext. Note it takes
     * no selector: destroying the whole store is what keeps this a security operation rather than
     * "drop the oldest when things get tight", which is the policy §3.4 refuses to add.
     */
    @Test
    fun `wipe destroys everything`() = runBlocking {
        repeat(3) { inbox.put(record("env_$it")) }

        inbox.wipe()

        assertEquals(0L, inbox.depth())
    }
}
