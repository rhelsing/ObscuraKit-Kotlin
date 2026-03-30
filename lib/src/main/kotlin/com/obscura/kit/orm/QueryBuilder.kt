package com.obscura.kit.orm

import kotlinx.coroutines.flow.map

/**
 * QueryBuilder for ORM model queries.
 *
 * Chainable, Rails-like:
 *   model.where(mapOf("data.author" to "alice")).orderBy("data.likes").limit(10).exec()
 *   model.where(mapOf("data.likes" to mapOf("atLeast" to 5, "atMost" to 100))).exec()
 *   model.where(mapOf("data.author" to mapOf("oneOf" to listOf("alice", "bob")))).exec()
 *   model.where(mapOf("data.title" to mapOf("contains" to "hello"))).first()
 *
 * Operators:
 *   equals, not, greaterThan, atLeast, lessThan, atMost,
 *   oneOf, noneOf, contains, startsWith, endsWith
 *
 * Short aliases also supported: eq, ne, gt, gte, lt, lte, in, nin
 */
class QueryBuilder(private val model: Model) {
    private val conditionsList = mutableListOf<Map<String, Any?>>()
    private var _orderBy: String? = null
    private var _orderDir: String = "desc"
    private var _limit: Int? = null
    private val _includes = mutableListOf<String>()

    fun where(conditions: Map<String, Any?>): QueryBuilder {
        conditionsList.add(conditions)
        return this
    }

    fun orderBy(field: String, direction: String = "desc"): QueryBuilder {
        // Auto-prefix data. for non-system fields, like the DSL where does
        _orderBy = if (field.startsWith("data.") || field in SYSTEM_FIELDS) field else "data.$field"
        _orderDir = direction
        return this
    }

    companion object {
        private val SYSTEM_FIELDS = setOf("id", "timestamp", "authorDeviceId")
    }

    fun limit(count: Int): QueryBuilder {
        _limit = count
        return this
    }

    fun include(vararg associations: String): QueryBuilder {
        _includes.addAll(associations)
        return this
    }

    suspend fun exec(): List<OrmEntry> {
        var entries = model.all()

        // Apply filters
        for (conditions in conditionsList) {
            entries = entries.filter { entry -> matchesAll(entry, conditions) }
        }

        // Sort
        if (_orderBy != null) {
            entries = sortEntries(entries)
        }

        // Limit
        if (_limit != null) {
            entries = entries.take(_limit!!)
        }

        // Eager load associations
        if (_includes.isNotEmpty()) {
            loadAssociations(entries)
        }

        return entries
    }

    suspend fun first(): OrmEntry? = exec().firstOrNull()

    suspend fun count(): Int = exec().size

    /**
     * Reactive query — returns a Flow that re-emits when the underlying data changes.
     *   model.where(mapOf("data.author" to "alice")).observe().collectAsState(emptyList())
     */
    fun observe(): kotlinx.coroutines.flow.Flow<List<OrmEntry>> {
        return model.observe().map { allEntries ->
            var entries = allEntries

            for (conditions in conditionsList) {
                entries = entries.filter { entry -> matchesAll(entry, conditions) }
            }

            if (_orderBy != null) {
                entries = sortEntries(entries)
            }

            if (_limit != null) {
                entries = entries.take(_limit!!)
            }

            entries
        }
    }

    private suspend fun loadAssociations(entries: List<OrmEntry>) {
        val store = model.store ?: return

        for (entry in entries) {
            for (assocName in _includes) {
                val children = store.getAssociated(model.name, entry.id, assocName)
                    .filter { !it.isDeleted }
                entry.associations[assocName] = children
            }
        }
    }

    private fun matchesAll(entry: OrmEntry, conditions: Map<String, Any?>): Boolean {
        return conditions.all { (key, value) -> matchesCondition(entry, key, value) }
    }

    private fun matchesCondition(entry: OrmEntry, key: String, value: Any?): Boolean {
        val entryValue = resolveField(entry, key)

        // Operator map: mapOf("gt" to 5, "lt" to 100)
        if (value is Map<*, *>) {
            return matchesOperators(entryValue, value)
        }

        // Simple equality
        return entryValue == value
    }

    @Suppress("UNCHECKED_CAST")
    private fun matchesOperators(value: Any?, operators: Map<*, *>): Boolean {
        for ((op, target) in operators) {
            val opStr = op as? String ?: continue
            when (opStr) {
                // Readable names (primary)
                "equals", "eq" -> if (value != target) return false
                "not", "ne" -> if (value == target) return false
                "greaterThan", "gt" -> if (!compareGt(value, target)) return false
                "atLeast", "gte" -> if (!compareGte(value, target)) return false
                "lessThan", "lt" -> if (!compareLt(value, target)) return false
                "atMost", "lte" -> if (!compareLte(value, target)) return false
                "oneOf", "in" -> {
                    val list = target as? List<*> ?: return false
                    if (value !in list) return false
                }
                "noneOf", "nin" -> {
                    val list = target as? List<*> ?: return false
                    if (value in list) return false
                }
                "contains" -> {
                    if (value !is String || target !is String) return false
                    if (!value.contains(target)) return false
                }
                "startsWith" -> {
                    if (value !is String || target !is String) return false
                    if (!value.startsWith(target)) return false
                }
                "endsWith" -> {
                    if (value !is String || target !is String) return false
                    if (!value.endsWith(target)) return false
                }
            }
        }
        return true
    }

    private fun resolveField(entry: OrmEntry, key: String): Any? {
        // Dot notation: "data.author" → entry.data["author"]
        if (key.startsWith("data.")) {
            return entry.data[key.removePrefix("data.")]
        }
        return when (key) {
            "authorDeviceId" -> entry.authorDeviceId
            "id" -> entry.id
            "timestamp" -> entry.timestamp
            else -> entry.data[key]
        }
    }

    private fun sortEntries(entries: List<OrmEntry>): List<OrmEntry> {
        val field = _orderBy ?: return entries
        return entries.sortedWith(Comparator { a, b ->
            val aVal = resolveField(a, field)
            val bVal = resolveField(b, field)
            val cmp = compareValues(aVal, bVal)
            if (_orderDir == "asc") cmp else -cmp
        })
    }

    @Suppress("UNCHECKED_CAST")
    private fun compareValues(a: Any?, b: Any?): Int {
        if (a == null && b == null) return 0
        if (a == null) return 1
        if (b == null) return -1
        return when (a) {
            is String -> a.compareTo(b as String)
            is Number -> a.toDouble().compareTo((b as Number).toDouble())
            is Long -> a.compareTo(b as Long)
            is Comparable<*> -> (a as Comparable<Any>).compareTo(b)
            else -> 0
        }
    }

    private fun compareGt(value: Any?, target: Any?): Boolean {
        if (value == null || target == null) return false
        return when {
            value is Number && target is Number -> value.toDouble() > target.toDouble()
            value is String && target is String -> value > target
            else -> false
        }
    }

    private fun compareGte(value: Any?, target: Any?): Boolean {
        if (value == null || target == null) return false
        return when {
            value is Number && target is Number -> value.toDouble() >= target.toDouble()
            value is String && target is String -> value >= target
            else -> false
        }
    }

    private fun compareLt(value: Any?, target: Any?): Boolean {
        if (value == null || target == null) return false
        return when {
            value is Number && target is Number -> value.toDouble() < target.toDouble()
            value is String && target is String -> value < target
            else -> false
        }
    }

    private fun compareLte(value: Any?, target: Any?): Boolean {
        if (value == null || target == null) return false
        return when {
            value is Number && target is Number -> value.toDouble() <= target.toDouble()
            value is String && target is String -> value <= target
            else -> false
        }
    }
}
