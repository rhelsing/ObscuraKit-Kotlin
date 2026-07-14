package com.obscura.kit.orm.crdt

import com.obscura.kit.orm.ModelStore
import com.obscura.kit.orm.MonotonicClock
import com.obscura.kit.orm.OrmEntry

/**
 * LWWMap - Last-Writer-Wins Map CRDT
 *
 * Used for mutable state: streaks, settings, profiles, reactions.
 * On conflict, highest timestamp wins.
 */
class LWWMap(
    private val store: ModelStore,
    val modelName: String
) {
    private val entries = mutableMapOf<String, OrmEntry>()
    private var loaded = false

    suspend fun load() {
        if (loaded) return
        store.getAll(modelName).forEach { entries[it.id] = it }
        loaded = true
    }

    private suspend fun ensureLoaded() {
        if (!loaded) load()
    }

    suspend fun set(entry: OrmEntry): OrmEntry {
        ensureLoaded()
        val clamped = clampFutureTimestamp(entry)
        val existing = entries[clamped.id]
        if (supersedes(clamped, existing)) {
            store.put(modelName, clamped)
            entries[clamped.id] = clamped
            return clamped
        }
        return existing!! // supersedes(x, null) is always true, so a false result implies existing != null
    }

    suspend fun add(entry: OrmEntry): OrmEntry = set(entry)

    suspend fun merge(entries: List<OrmEntry>): List<OrmEntry> {
        ensureLoaded()
        val updated = mutableListOf<OrmEntry>()
        for (entry in entries) {
            val clamped = clampFutureTimestamp(entry)
            val existing = this.entries[clamped.id]
            if (supersedes(clamped, existing)) {
                store.put(modelName, clamped)
                this.entries[clamped.id] = clamped
                updated.add(clamped)
            }
        }
        return updated
    }

    /**
     * Reject a spoofed far-future timestamp that would otherwise win every future
     * LWW conflict forever. Applied on BOTH the local-write (set) and the
     * incoming-sync (merge) paths — a timestamp arriving over sync is no more
     * trustworthy than a local one.
     */
    private fun clampFutureTimestamp(entry: OrmEntry): OrmEntry {
        val maxAllowed = System.currentTimeMillis() + CLOCK_SKEW_TOLERANCE_MS
        return if (entry.timestamp > maxAllowed) entry.copy(timestamp = maxAllowed) else entry
    }

    /**
     * Does [incoming] win the LWW conflict against [existing]?
     *
     * Total order on (timestamp, authorDeviceId): a strictly-greater timestamp
     * wins; on an equal timestamp the lexicographically-higher authorDeviceId
     * wins. The device-id tie-break is what makes resolution deterministic and
     * order-independent across replicas (a true CRDT) instead of "whichever
     * write happened to arrive first" — which would let two devices converge to
     * different states on an equal-timestamp conflict. Equal timestamp AND equal
     * author is the same logical write (idempotent → existing is kept).
     */
    private fun supersedes(incoming: OrmEntry, existing: OrmEntry?): Boolean {
        if (existing == null) return true
        if (incoming.timestamp != existing.timestamp) return incoming.timestamp > existing.timestamp
        return incoming.authorDeviceId > existing.authorDeviceId
    }

    suspend fun get(id: String): OrmEntry? {
        ensureLoaded()
        return entries[id]
    }

    suspend fun has(id: String): Boolean {
        ensureLoaded()
        return entries.containsKey(id)
    }

    suspend fun getAll(): List<OrmEntry> {
        ensureLoaded()
        return entries.values.filter { !it.isDeleted }.toList()
    }

    suspend fun size(): Int {
        ensureLoaded()
        return entries.values.count { !it.isDeleted }
    }

    suspend fun delete(id: String, authorDeviceId: String): OrmEntry {
        ensureLoaded()
        // Preserve the prior entry's fields in the tombstone (plus _deleted) so a
        // delete on a 1:1 model still carries the routing field (e.g. conversationId)
        // when it is broadcast; without it, SyncManager could not resolve the audience.
        val priorData = entries[id]?.data ?: emptyMap()
        val tombstone = OrmEntry(
            id = id,
            data = priorData + ("_deleted" to true),
            timestamp = MonotonicClock.now(),
            authorDeviceId = authorDeviceId
        )
        store.put(modelName, tombstone)
        entries[id] = tombstone
        return tombstone
    }

    suspend fun filter(
        predicate: (OrmEntry) -> Boolean,
        includeTombstones: Boolean = false
    ): List<OrmEntry> {
        ensureLoaded()
        var list = entries.values.toList()
        if (!includeTombstones) {
            list = list.filter { !it.isDeleted }
        }
        return list.filter(predicate)
    }

    suspend fun getAllSorted(descending: Boolean = true): List<OrmEntry> {
        ensureLoaded()
        val live = entries.values.filter { !it.isDeleted }
        return if (descending) {
            live.sortedByDescending { it.timestamp }
        } else {
            live.sortedBy { it.timestamp }
        }
    }

    companion object {
        /**
         * Tolerance for benign clock skew when rejecting far-future timestamps (SPEC §2.4).
         * Aliased from [com.obscura.kit.orm.MonotonicClock] so the CRDT path and the
         * device-announce replay guard share one definition.
         */
        private const val CLOCK_SKEW_TOLERANCE_MS =
            com.obscura.kit.orm.MonotonicClock.CLOCK_SKEW_TOLERANCE_MS
    }
}
