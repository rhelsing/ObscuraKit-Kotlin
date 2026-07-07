package com.obscura.kit.persistence

/**
 * Kit-owned session persistence. Bridge provides platform-specific implementation.
 * Default: no-op (for JVM tests). Android bridge provides SharedPreferences impl.
 *
 * Contract: [save] REPLACES the entire stored blob with `data` (matches the Swift
 * kit's UserDefaults impl, which stores one dict under one key). Callers that only
 * touch part of the session (e.g. [ObscuraClient.persistSession]) load-merge-save
 * so nothing is dropped, so implementations must NOT try to patch individual keys —
 * just persist exactly `data` and drop anything not in it. [load] returns the whole
 * blob or null if nothing is stored.
 */
interface SessionStorage {
    fun save(data: Map<String, Any?>)
    fun load(): Map<String, Any?>?
    fun clear()
}

/** No-op implementation for JVM tests */
object NoOpSessionStorage : SessionStorage {
    override fun save(data: Map<String, Any?>) {}
    override fun load(): Map<String, Any?>? = null
    override fun clear() {}
}
