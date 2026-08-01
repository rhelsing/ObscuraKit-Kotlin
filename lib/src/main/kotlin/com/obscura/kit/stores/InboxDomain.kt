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
 * Handing a payload to the app and then acknowledging it would make an asynchronous event the only
 * copy:
 *
 * ```
 * decrypt → emit to app → ACK (server DELETEs) → ...app writes to its store, maybe, later
 * ```
 *
 * The bridge may be backpressured and the app may not be running. The kit therefore persists bytes
 * it does not understand before acknowledging the server copy.
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
        val existedBefore = db.inboxQueries.existsByEnvelopeId(record.envelopeId).executeAsOne()
        db.inboxQueries.insertRow(
            record.envelopeId, record.kind, record.receivedAt, record.senderUserId,
            record.senderDeviceId, record.senderDisplayName, record.modelKey, record.entryId,
            record.op, record.sentAt, record.payload,
        )

        // Assert the postcondition the ack depends on, rather than inferring it from a row count.
        //
        // `changes()` cannot distinguish an envelope-id duplicate from another ignored constraint
        // violation. Check the required row directly before allowing the caller to acknowledge it.
        if (!db.inboxQueries.existsByEnvelopeId(record.envelopeId).executeAsOne()) {
            throw IllegalStateException(
                "inbox row for envelope ${record.envelopeId} is absent after insert; refusing to " +
                    "report it as stored"
            )
        }
        !existedBefore
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
        // Chunked because `WHERE id IN ?` binds one variable per id, and SQLite caps that at 999 on
        // older builds. The app chooses the batch size, so `peek(limit = 5000)` then `consume` of
        // 5000 ids would throw "too many SQL variables" — and it would throw exactly when a large
        // backlog exists, i.e. the one situation where the drain must not stall (§3.5).
        ids.chunked(DELETE_CHUNK).forEach { db.inboxQueries.deleteByIds(it) }
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
        ids.chunked(DELETE_CHUNK).forEach { db.inboxQueries.deleteByIds(it) }
        onDiscard?.invoke(ids, reason)
        ids
    }

    private companion object {
        /** Comfortably under SQLite's 999-variable floor. */
        const val DELETE_CHUNK = 500
    }

    /** Set by the client so a discard reaches the security log rather than vanishing. */
    internal var onDiscard: ((List<Long>, String) -> Unit)? = null

    /**
     * How many rows are waiting.
     *
     * Unbounded growth means the app has stopped draining. The app must surface abnormal depth
     * before disk pressure prevents persistence and moves the backlog to the bounded server queue.
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
