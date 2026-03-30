package com.obscura.kit.orm

/**
 * Kotlin DSL for building query conditions.
 *
 * Instead of:
 *   model.where(mapOf("data.author" to "alice", "data.likes" to mapOf("atLeast" to 5)))
 *
 * Write:
 *   model.where { "author" eq "alice"; "likes" atLeast 5 }
 *
 * All field names are automatically prefixed with "data." — you just write the field name.
 * For system fields (id, timestamp, authorDeviceId), use the full name.
 */
class WhereBuilder {
    internal val conditions = mutableMapOf<String, Any?>()

    private fun key(field: String) = if (field in SYSTEM_FIELDS) field else "data.$field"

    // Simple equality — most common
    infix fun String.eq(value: Any?) { conditions[key(this)] = value }

    // Negation
    infix fun String.not(value: Any?) { conditions[key(this)] = mapOf("not" to value) }

    // Comparison
    infix fun String.greaterThan(value: Number) { addOp(this, "greaterThan", value) }
    infix fun String.atLeast(value: Number) { addOp(this, "atLeast", value) }
    infix fun String.lessThan(value: Number) { addOp(this, "lessThan", value) }
    infix fun String.atMost(value: Number) { addOp(this, "atMost", value) }

    // List membership
    infix fun String.oneOf(values: List<Any?>) { conditions[key(this)] = mapOf("oneOf" to values) }
    infix fun String.noneOf(values: List<Any?>) { conditions[key(this)] = mapOf("noneOf" to values) }

    // String matching
    infix fun String.contains(value: String) { conditions[key(this)] = mapOf("contains" to value) }
    infix fun String.startsWith(value: String) { conditions[key(this)] = mapOf("startsWith" to value) }
    infix fun String.endsWith(value: String) { conditions[key(this)] = mapOf("endsWith" to value) }

    private fun addOp(field: String, op: String, value: Any?) {
        val k = key(field)
        val existing = conditions[k]
        if (existing is MutableMap<*, *>) {
            @Suppress("UNCHECKED_CAST")
            (existing as MutableMap<String, Any?>)[op] = value
        } else {
            conditions[k] = mutableMapOf(op to value)
        }
    }

    companion object {
        private val SYSTEM_FIELDS = setOf("id", "timestamp", "authorDeviceId")
    }
}
