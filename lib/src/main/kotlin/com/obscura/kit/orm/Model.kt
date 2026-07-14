package com.obscura.kit.orm

import com.obscura.kit.orm.crdt.GSet
import com.obscura.kit.orm.crdt.LWWMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import org.json.JSONObject

/**
 * Generic base class for all ORM models.
 * Works for any model name: "story", "streak", "settings", etc.
 */
class Model(
    val name: String,
    val config: ModelConfig,
    private val gset: GSet? = null,
    private val lwwMap: LWWMap? = null,
    internal val syncManager: SyncManager? = null,
    private val ttlManager: TTLManager? = null,
    /**
     * Resolves the local device id at create/upsert time. A lambda (not a
     * constant) so models constructed before login still produce entries
     * stamped with the correct deviceId once it becomes available.
     */
    private val deviceIdProvider: () -> String = { "" },
    internal val store: ModelStore? = null,
    internal var signalManager: SignalManager? = null
) {

    suspend fun create(data: Map<String, Any?>): OrmEntry {
        validate(data)

        val id = "${name}_${System.currentTimeMillis()}_${randomId()}"
        val entry = OrmEntry(
            id = id,
            data = data,
            timestamp = MonotonicClock.now(),
            authorDeviceId = deviceIdProvider()
        )

        if (config.sync == SyncStrategy.LWW) {
            lwwMap!!.add(entry)
        } else {
            gset!!.add(entry)
        }

        if (config.ttl != null && ttlManager != null) {
            ttlManager.schedule(name, id, config.ttl!!)
        }

        // Auto-register association if this model belongs_to a parent
        if (config.belongsTo.isNotEmpty() && store != null) {
            for (parentModel in config.belongsTo) {
                val foreignKey = "${parentModel}Id"
                val parentId = data[foreignKey] as? String
                if (parentId != null) {
                    store.addAssociation(parentModel, parentId, name, id)
                }
            }
        }

        syncManager?.broadcast(this, entry)

        return entry
    }

    suspend fun upsert(id: String, data: Map<String, Any?>): OrmEntry {
        validate(data)
        val entry = OrmEntry(
            id = id,
            data = data,
            timestamp = MonotonicClock.now(),
            authorDeviceId = deviceIdProvider()
        )

        val result = lwwMap?.set(entry) ?: gset?.add(entry) ?: entry

        if (result === entry) {
            syncManager?.broadcast(this, entry)
        }

        return result
    }

    suspend fun find(id: String): OrmEntry? {
        val entry = if (config.sync == SyncStrategy.LWW) lwwMap?.get(id) else gset?.get(id)
        // A tombstone is not findable — consistent with all()/getAll(), which exclude
        // deleted entries. (This used to "work" only because a minimal {_deleted:true}
        // tombstone failed to deserialize; now tombstones preserve prior fields.)
        return entry?.takeUnless { it.isDeleted }
    }

    fun where(conditions: Map<String, Any?>): QueryBuilder {
        return QueryBuilder(this).where(conditions)
    }

    /**
     * DSL query:
     *   story.where { "author" eq "alice"; "likes" atLeast 5 }.exec()
     */
    fun where(block: WhereBuilder.() -> Unit): QueryBuilder {
        val builder = WhereBuilder()
        builder.block()
        return QueryBuilder(this).where(builder.conditions)
    }

    suspend fun all(): List<OrmEntry> {
        return if (config.sync == SyncStrategy.LWW) lwwMap?.getAll() ?: emptyList() else gset?.getAll() ?: emptyList()
    }

    suspend fun allSorted(descending: Boolean = true): List<OrmEntry> {
        return if (config.sync == SyncStrategy.LWW) lwwMap?.getAllSorted(descending) ?: emptyList()
        else gset?.getAllSorted(descending) ?: emptyList()
    }

    /**
     * Observe all entries as a reactive Flow. Compose-ready:
     *   val stories = model.observe().collectAsState(emptyList())
     */
    fun observe(): Flow<List<OrmEntry>> {
        val db = store?.db ?: throw IllegalStateException("observe() requires a store-backed model")
        val now = System.currentTimeMillis()
        return db.modelEntryQueries.selectByModel(name)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows ->
                rows.filter { row -> row.ttl_expires_at == null || row.ttl_expires_at > System.currentTimeMillis() }
                    .map { row ->
                        OrmEntry(
                            id = row.entry_id,
                            data = parseJsonMap(row.data_),
                            timestamp = row.timestamp,
                            authorDeviceId = row.author_device_id
                        )
                    }
            }
    }

    private fun parseJsonMap(json: String): Map<String, Any?> {
        val obj = JSONObject(json)
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) { map[key] = if (obj.isNull(key)) null else obj.get(key) }
        return map
    }

    suspend fun delete(id: String): OrmEntry? {
        if (config.sync != SyncStrategy.LWW) throw IllegalStateException("Delete only supported for LWW models")
        val tombstone = lwwMap?.delete(id, deviceIdProvider()) ?: return null
        // A delete must propagate like any other write; otherwise the tombstone
        // stays local and the entry "resurrects" on other devices. broadcast()
        // derives op=DELETE from the tombstone and routes it using the entry data
        // that LWWMap.delete preserved (so 1:1 audiences still resolve).
        syncManager?.broadcast(this, tombstone)
        return tombstone
    }

    // ─── ECS Signals (ephemeral, not persisted) ─────────────────

    /** Username of the local user — set by ObscuraClient when wiring. */
    internal var localUsername: String = ""

    /**
     * Send a typing indicator for the given [contextKey] (an opaque identifier
     * chosen by the application — typically a conversation ID or channel name).
     * Auto-throttled: sends at most once per 2 seconds.
     */
    suspend fun typing(contextKey: String) {
        signalManager?.emit(name, "typing", contextKey, deviceIdProvider())
    }

    /** Explicitly stop typing. */
    suspend fun stopTyping(contextKey: String) {
        signalManager?.emit(name, "stoppedTyping", contextKey, deviceIdProvider())
    }

    /** Send a read receipt for the given [contextKey]. */
    suspend fun read(contextKey: String) {
        signalManager?.emit(name, "read", contextKey, deviceIdProvider())
    }

    /**
     * Observe who is actively typing for [contextKey].
     *
     * Emits **authorDeviceId** strings, not usernames: the device comes from the
     * authenticated Signal envelope, whereas a sender-supplied display name in the payload
     * would be spoofable (SPEC §5). To render a name, resolve each id against your own friend
     * graph with [com.obscura.kit.stores.FriendDomain.friendForDeviceId].
     *
     * Auto-expires after 3 seconds of no signal.
     */
    fun observeTyping(contextKey: String): Flow<List<String>> {
        return signalManager?.observe(name, "typing", contextKey)
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    /**
     * Observe read receipts for [contextKey]. Emits **authorDeviceId** strings — resolve names
     * via [com.obscura.kit.stores.FriendDomain.friendForDeviceId], as with [observeTyping].
     */
    fun observeRead(contextKey: String): Flow<List<String>> {
        return signalManager?.observe(name, "read", contextKey)
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    suspend fun handleSync(modelSync: ModelSyncData): OrmEntry? {
        val entry = OrmEntry(
            id = modelSync.id,
            data = decodeData(modelSync.data),
            timestamp = modelSync.timestamp,
            authorDeviceId = modelSync.authorDeviceId
        )

        val merged = if (config.sync == SyncStrategy.LWW) {
            lwwMap?.merge(listOf(entry)) ?: emptyList()
        } else {
            gset?.merge(listOf(entry)) ?: emptyList()
        }

        return merged.firstOrNull()
    }

    fun validate(data: Map<String, Any?>) {
        for ((field, type) in config.fields) {
            val (baseType, isOptional) = FieldTypes.parse(field, type)
            val value = data[field]

            if (value == null) {
                if (!isOptional) throw ValidationException("$field is required")
                continue
            }

            when (baseType) {
                "string" -> if (value !is String) throw ValidationException("$field must be string")
                "number" -> if (value !is Number) throw ValidationException("$field must be number")
                "boolean" -> if (value !is Boolean) throw ValidationException("$field must be boolean")
                "timestamp" -> if (value !is Number || value.toLong() < 0) throw ValidationException("$field must be positive timestamp")
            }
        }
    }

    private fun decodeData(data: ByteArray): Map<String, Any?> {
        val json = String(data)
        val obj = JSONObject(json)
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            map[key] = if (obj.isNull(key)) null else obj.get(key)
        }
        return map
    }

    companion object {
        private fun randomId(length: Int = 8): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            return (1..length).map { chars.random() }.joinToString("")
        }
    }
}

data class ModelSyncData(
    val model: String,
    val id: String,
    val op: ModelOp = ModelOp.CREATE,
    val timestamp: Long,
    val data: ByteArray,
    val authorDeviceId: String
)

class ValidationException(message: String) : IllegalArgumentException(message)
