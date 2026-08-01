package com.obscura.kit.stores

import com.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One stored entry. `data` is an opaque JSON string the kit never parses.
 *
 * `sentAt` and `authorDeviceId` are carried because the app's merge needs them — REPLACE is a total
 * order on `(sentAt, authorDeviceId)` (`KIT_API.md` §8.2). They are metadata in columns beside the
 * payload, not fields the kit reads out of it.
 */
data class StoredEntry(
    val id: String,
    val data: String,
    val sentAt: Long,
    val authorDeviceId: String,
)

/**
 * Raw storage for application entries (`obscura-proto/KIT_API.md` §8.1).
 *
 * ## What this is
 *
 * The other half of the thin kit's app-facing surface: `InboxDomain` is how messages arrive,
 * this is where the app keeps what it made of them. Together they are the whole data path.
 *
 * ## Why it exists separately from `ModelStore`
 *
 * **The table is being kept; the engine above it is being deleted.** `orm/ModelStore` reads and
 * writes the same `ModelEntry` table, but it lives inside the package `RESET.md` removes, and it is
 * the bottom of a stack — CRDT merge, TTL manager, query DSL, schema parser — that goes with it. So
 * the store the app keeps cannot be that one; it has to be a class that survives the deletion, over
 * the same rows.
 *
 * That is why this is a **new file with no ORM imports** rather than `ModelStore` made public. When
 * `orm/` goes in §10 step 4, nothing here changes.
 *
 * ## Three methods, and there is no fourth
 *
 * `put` / `all` / `delete`. §9 is explicit that a query API is not part of this design, and names
 * the failure mode in advance: a table grows a filter, then an index abstraction, then observation,
 * and 1,726 lines of Kotlin have been reimplemented in TypeScript and called a reset. If a screen
 * needs `WHERE conversationId = ?`, that is a deliberate decision with a number attached.
 *
 * Note the pressure it will come from, so it is not a surprise: `all("directMessage")` loads every
 * message ever sent, forever, and nothing here deletes on a timer. Fine at current volume; not fine
 * indefinitely.
 *
 * ## No merge, no CRDT, no TTL
 *
 * `put` is a blind upsert. **The app decides who wins** — merge moved to `obscura-pix`'s
 * `src/domain/merge.ts`, where APPEND/REPLACE live once in TypeScript instead of twice in Kotlin and
 * Swift. By the time a write reaches here the decision is made, so re-deciding it would be a second
 * implementation of the thing being deleted.
 *
 * Expiry is the same: `story` has a 24h TTL, and that is now an `expiresAt` field inside the app's
 * own `data` which the app filters on. This class does not read `data`, so it cannot know.
 */
class EntryStore internal constructor(private val db: ObscuraDatabase) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * Write an entry, replacing any existing one with the same `(model, id)`.
     *
     * Blind by design — see the class doc. `data` is stored verbatim; the kit does not validate it
     * as JSON, because validating a shape it may not read is a boundary violation dressed as
     * defensiveness (SPEC §0.4).
     */
    suspend fun put(model: String, entry: StoredEntry) = withContext(dispatcher) {
        db.modelEntryQueries.insertEntry(
            model_name = model,
            entry_id = entry.id,
            data_ = entry.data,
            timestamp = entry.sentAt,
            author_device_id = entry.authorDeviceId,
            deleted = 0L,
            ttl_expires_at = null,
        )
    }

    /** Every live entry for a model, in no guaranteed order. */
    suspend fun all(model: String): List<StoredEntry> = withContext(dispatcher) {
        db.modelEntryQueries.selectByModel(model).executeAsList().map { row ->
            StoredEntry(
                id = row.entry_id,
                data = row.data_,
                sentAt = row.timestamp,
                authorDeviceId = row.author_device_id,
            )
        }
    }

    /**
     * Remove an entry.
     *
     * A **soft** delete: this sets `ModelEntry.deleted = 1`, which `selectByModel` filters on, so
     * the row stays on disk and stops appearing in [all]. That is the same observable behaviour a
     * hard delete would give through the bridge, and it is what `ModelEntry.sq` documents. (The
     * comment here used to claim a hard delete, which was simply false — the call below has always
     * been `markDeleted`. ObscuraKit-swift does hard-delete the row; the difference is invisible to
     * the app.)
     *
     * Not a tombstone either: the flag is local and is never synced. The app never deletes an entry
     * today — `deleteEntry` is on the bridge with zero callers — and the whole distributed-delete
     * ordering design was dead on arrival. This exists so "the app can remove something" is possible
     * at all; distributed deletes would be a design with a `SPEC` change, not a flag added here.
     */
    suspend fun delete(model: String, id: String) = withContext(dispatcher) {
        db.modelEntryQueries.markDeleted(System.currentTimeMillis(), model, id)
    }
}
