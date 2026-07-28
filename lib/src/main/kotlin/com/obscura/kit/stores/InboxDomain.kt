package com.obscura.kit.stores

import com.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One drained inbox row, as the app sees it (`obscura-proto/KIT_API.md` §3.1).
 *
 * `payload` is opaque bytes the kit never parsed. The ModelSync-derived fields are null for every
 * other kind, including an unknown arm — there is no ModelSync to derive them from.
 */
data class InboxRecord(
    val id: Long,
    val envelopeId: String,
    val kind: String,
    val receivedAt: Long,
    val senderUserId: String,
    val senderDeviceId: String?,
    val senderDisplayName: String?,
    val modelKey: String?,
    val entryId: String?,
    val op: String?,
    val sentAt: Long?,
    val payload: ByteArray,
) {
    // ByteArray gives reference equality from the data-class defaults, which silently breaks any
    // assertEquals on a record. Spelled out rather than left to surprise someone in a test.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InboxRecord) return false
        return id == other.id && envelopeId == other.envelopeId && kind == other.kind &&
            receivedAt == other.receivedAt && senderUserId == other.senderUserId &&
            senderDeviceId == other.senderDeviceId && senderDisplayName == other.senderDisplayName &&
            modelKey == other.modelKey && entryId == other.entryId && op == other.op &&
            sentAt == other.sentAt && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * id.hashCode() + payload.contentHashCode()
}

/**
 * The durable inbox (`obscura-proto/KIT_API.md` §3).
 *
 * The kit is a durable, authenticated inbox for **opaque payloads**: it stores bytes it cannot read,
 * addressed to and from identities it can prove. This class is that store.
 *
 * ## Why an inbox and not an event stream
 *
 * The reset takes application data away from the kit. If the thin kit instead handed each payload to
 * the app — an event, a callback, a bridge emit — and then acked, the ordering becomes:
 *
 * ```
 * decrypt → emit to app → ACK (server DELETEs) → ...app writes to its store, maybe, later
 * ```
 *
 * That is the Phase 1 data-loss bug rebuilt across a process boundary, in both kits at once, on a
 * path where the app may not be running. The React Native bridge is asynchronous and lossy under
 * backpressure, and the iOS push path has no JS runtime at all. **So the kit must persist before it
 * acks, and therefore needs somewhere durable to put bytes it does not understand.**
 *
 * ## The API is four methods, and there is no fifth
 *
 * `peek` / `consume` / `discard` / `depth`. In particular there is **no insert**: the inbox is
 * kit-write, app-read-and-delete (§3.3 rule 9). The only candidate for an app-side write was
 * self-sync, and it does not need one — a send fans out to the user's *other* devices via the
 * server, which receive it through the ordinary envelope path. The originating device is never
 * echoed to and writes its own store directly.
 */
class InboxDomain internal constructor(private val db: ObscuraDatabase) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * Persist a decrypted message. **Kit-internal**: called from the receive loop before the ack,
     * never by the app.
     *
     * Throws if the write fails, which is the point — the caller must not ack what is not stored.
     *
     * Returns true if a row was inserted, false if `envelopeId` was already present. A false is a
     * *successful* redelivery absorption, not an error: it means the message is already durably
     * held, so the caller should ack exactly as it would after a fresh insert. Acking is what stops
     * the server sending it a third time.
     */
    internal suspend fun put(record: InboxRecord): Boolean = withContext(dispatcher) {
        val before = db.inboxQueries.depth().executeAsOne()
        db.inboxQueries.insertRow(
            record.envelopeId, record.kind, record.receivedAt, record.senderUserId,
            record.senderDeviceId, record.senderDisplayName, record.modelKey, record.entryId,
            record.op, record.sentAt, record.payload,
        )
        db.inboxQueries.depth().executeAsOne() > before
    }

    /**
     * The next rows to process, in drain order, oldest first.
     *
     * **Side-effect free.** Peeking twice without consuming returns the same rows — that is the
     * crash-safety property, not a bug: an app that dies mid-drain reprocesses, and the merge rules
     * downstream are idempotent so that reprocessing converges.
     */
    suspend fun peek(limit: Int = 50): List<InboxRecord> = withContext(dispatcher) {
        db.inboxQueries.peek(limit.toLong()).executeAsList().map { row ->
            InboxRecord(
                id = row.id,
                envelopeId = row.envelope_id,
                kind = row.kind,
                receivedAt = row.received_at,
                senderUserId = row.sender_user_id,
                senderDeviceId = row.sender_device_id,
                senderDisplayName = row.sender_display_name,
                modelKey = row.model_key,
                entryId = row.entry_id,
                op = row.op,
                sentAt = row.sent_at,
                payload = row.payload,
            )
        }
    }

    /**
     * Drop rows the app has durably processed.
     *
     * Idempotent, and a subset is fine — partial progress is normal, not an error path.
     */
    suspend fun consume(ids: List<Long>) = withContext(dispatcher) {
        if (ids.isNotEmpty()) db.inboxQueries.deleteByIds(ids)
    }

    /**
     * Drop rows the app declares it can **never** process.
     *
     * This is data loss, chosen deliberately: the server's copy is already gone, so nothing else
     * holds these bytes. It is therefore logged as a security-relevant event and must never be the
     * quiet path (§3.3 rule 5) — which is the entire reason it is a separate method from [consume]
     * rather than a flag on it. The SQL is identical; the accountability is not.
     */
    suspend fun discard(ids: List<Long>, reason: String): List<Long> = withContext(dispatcher) {
        if (ids.isEmpty()) return@withContext emptyList()
        db.inboxQueries.deleteByIds(ids)
        onDiscard?.invoke(ids, reason)
        ids
    }

    /** Set by the client so a discard reaches the security log rather than vanishing. */
    internal var onDiscard: ((List<Long>, String) -> Unit)? = null

    /**
     * How many rows are waiting.
     *
     * Exposed because it MUST be (§3.3 rule 7): unbounded growth means the app has stopped draining.
     * §3.5 traces where that ends — inbox grows, disk pressure, the durable write throws, the kit
     * correctly refuses to ack, the message stays on the server, the *server's* queue hits 1000, and
     * it evicts oldest-first and silently. **A number nobody reads is not observability**: the app
     * is expected to surface this past a threshold, not merely be able to ask.
     */
    suspend fun depth(): Long = withContext(dispatcher) {
        db.inboxQueries.depth().executeAsOne()
    }

    /**
     * Destroy every row.
     *
     * The §3.3 rule 2 carve-out, and **not** an eviction policy: a device wipe or a remote
     * revocation has to be able to destroy decrypted plaintext, and that is a security requirement.
     * Note it takes no selector — destroying the whole store is what keeps it from becoming "drop
     * the oldest when things get tight", which is the rule this design exists to refuse.
     */
    internal suspend fun wipe() = withContext(dispatcher) {
        db.inboxQueries.deleteAll()
    }
}
