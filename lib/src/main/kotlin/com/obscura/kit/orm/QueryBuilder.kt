package com.obscura.kit.orm

/**
 * QueryBuilder for ORM model queries.
 * Supports chaining where() conditions.
 */
class QueryBuilder(private val model: Model) {
    private val conditions = mutableMapOf<String, Any?>()

    fun where(newConditions: Map<String, Any?>): QueryBuilder {
        conditions.putAll(newConditions)
        return this
    }

    suspend fun exec(): List<OrmEntry> {
        val allEntries = model.all()
        return allEntries.filter { entry ->
            conditions.all { (key, value) ->
                val entryValue = if (key.startsWith("data.")) {
                    entry.data[key.removePrefix("data.")]
                } else {
                    when (key) {
                        "authorDeviceId" -> entry.authorDeviceId
                        "id" -> entry.id
                        "timestamp" -> entry.timestamp
                        else -> entry.data[key]
                    }
                }
                entryValue == value
            }
        }
    }

    suspend fun first(): OrmEntry? = exec().firstOrNull()

    suspend fun count(): Int = exec().size
}
