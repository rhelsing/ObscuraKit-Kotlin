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
 * The other half of the thin kit's app-facing surface: `InboxDomain` is how messages arrive,
 * this is where the app keeps what it made of them. Together they are the whole data path.
 *
 * The API is `put` / `all` / `delete`. `put` is a blind upsert; the app resolves merge before
 * writing. This store has no schema parser, query layer, merge engine, or expiry policy.
 * `all(model)` therefore loads every live entry for that model.
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
     * This is a local soft delete: the row remains on disk and [all] filters it out. The flag is
     * not synchronized and is not a distributed tombstone.
     */
    suspend fun delete(model: String, id: String) = withContext(dispatcher) {
        db.modelEntryQueries.markDeleted(System.currentTimeMillis(), model, id)
    }
}
