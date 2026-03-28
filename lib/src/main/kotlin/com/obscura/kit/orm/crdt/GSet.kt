package com.obscura.kit.orm.crdt

import com.obscura.kit.orm.ModelStore
import com.obscura.kit.orm.OrmEntry

/**
 * GSet - Grow-only Set CRDT
 *
 * Used for immutable content: stories, comments, messages, friend requests.
 * Add-only (entries cannot be removed or modified).
 * Merge = union of sets. Idempotent.
 */
class GSet(
    private val store: ModelStore,
    val modelName: String
) {
    private val elements = mutableMapOf<String, OrmEntry>()
    private var loaded = false

    suspend fun load() {
        if (loaded) return
        store.getAll(modelName).forEach { elements[it.id] = it }
        loaded = true
    }

    private suspend fun ensureLoaded() {
        if (!loaded) load()
    }

    suspend fun add(entry: OrmEntry): OrmEntry {
        ensureLoaded()
        if (elements.containsKey(entry.id)) return elements[entry.id]!!
        store.put(modelName, entry)
        elements[entry.id] = entry
        return entry
    }

    suspend fun merge(entries: List<OrmEntry>): List<OrmEntry> {
        ensureLoaded()
        val added = mutableListOf<OrmEntry>()
        for (entry in entries) {
            if (!elements.containsKey(entry.id)) {
                store.put(modelName, entry)
                elements[entry.id] = entry
                added.add(entry)
            }
        }
        return added
    }

    suspend fun get(id: String): OrmEntry? {
        ensureLoaded()
        return elements[id]
    }

    suspend fun has(id: String): Boolean {
        ensureLoaded()
        return elements.containsKey(id)
    }

    suspend fun getAll(): List<OrmEntry> {
        ensureLoaded()
        return elements.values.toList()
    }

    suspend fun size(): Int {
        ensureLoaded()
        return elements.size
    }

    suspend fun filter(predicate: (OrmEntry) -> Boolean): List<OrmEntry> {
        ensureLoaded()
        return elements.values.filter(predicate)
    }

    suspend fun getAllSorted(descending: Boolean = true): List<OrmEntry> {
        ensureLoaded()
        return if (descending) {
            elements.values.sortedByDescending { it.timestamp }
        } else {
            elements.values.sortedBy { it.timestamp }
        }
    }
}
