package com.obscura.kit.orm

/**
 * Handles ephemeral content expiration.
 */
class TTLManager(private val store: ModelStore) {

    fun parseTTL(ttl: String): Long {
        val regex = Regex("""^(\d+)(s|m|h|d)$""")
        val match = regex.matchEntire(ttl)
            ?: throw IllegalArgumentException("Invalid TTL format: $ttl. Expected: 24h, 7d, 30m, 30s")

        val num = match.groupValues[1].toLong()
        val unit = match.groupValues[2]

        return num * when (unit) {
            "s" -> 1_000L
            "m" -> 60_000L
            "h" -> 3_600_000L
            "d" -> 86_400_000L
            else -> throw IllegalArgumentException("Unknown unit: $unit")
        }
    }

    fun schedule(modelName: String, id: String, ttl: String) {
        val ms = parseTTL(ttl)
        val expiresAt = System.currentTimeMillis() + ms
        store.setTTL(modelName, id, expiresAt)
    }

    fun cleanup(getModel: (String) -> Model?): Int {
        val expired = store.getExpired()
        var cleaned = 0
        for ((modelName, id) in expired) {
            try {
                store.markDeleted(modelName, id)
                cleaned++
            } catch (e: Exception) {
                // Skip failed cleanup
            }
        }
        store.deleteExpired()
        return cleaned
    }

    fun isExpired(modelName: String, id: String): Boolean {
        val remaining = getTimeRemaining(modelName, id)
        return remaining != null && remaining <= 0
    }

    fun getTimeRemaining(modelName: String, id: String): Long? {
        // Look up the TTL from the store
        val entry = store.find(modelName, id) ?: return null
        // TTL is stored in the database row
        val row = try {
            val q = store.db.modelEntryQueries.selectByModelAndId(modelName, id).executeAsOneOrNull()
            q?.ttl_expires_at
        } catch (e: Exception) { null }
        if (row == null) return null
        return maxOf(0, row - System.currentTimeMillis())
    }
}
