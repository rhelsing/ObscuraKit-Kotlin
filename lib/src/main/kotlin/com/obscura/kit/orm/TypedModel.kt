package com.obscura.kit.orm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * Type-safe wrapper around Model.
 *
 * iOS equivalent: TypedModel<Story> with Codable.
 * Kotlin equivalent: TypedModel<Story> with kotlinx.serialization.
 *
 * Usage:
 *   @Serializable
 *   data class Story(val content: String, val authorUsername: String)
 *
 *   val stories = TypedModel.wrap<Story>(client.orm.model("story"))
 *   stories.create(Story(content = "Beach day!", authorUsername = "alice"))
 *   val all: List<TypedEntry<Story>> = stories.all()
 *   val feed: Flow<List<TypedEntry<Story>>> = stories.observe()
 */
class TypedModel<T : Any>(
    private val model: Model,
    private val serializer: KSerializer<T>,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    val name: String get() = model.name

    suspend fun create(value: T): TypedEntry<T> {
        val map = encodeToMap(value)
        val entry = model.create(map)
        return TypedEntry(entry, value)
    }

    suspend fun upsert(id: String, value: T): TypedEntry<T> {
        val map = encodeToMap(value)
        val entry = model.upsert(id, map)
        return TypedEntry(entry, decodeOrNull(entry) ?: value)
    }

    suspend fun find(id: String): TypedEntry<T>? {
        val entry = model.find(id) ?: return null
        val value = decodeOrNull(entry) ?: return null
        return TypedEntry(entry, value)
    }

    suspend fun all(): List<TypedEntry<T>> {
        return model.all().mapNotNull { entry ->
            decodeOrNull(entry)?.let { TypedEntry(entry, it) }
        }
    }

    suspend fun allSorted(descending: Boolean = true): List<TypedEntry<T>> {
        return model.allSorted(descending).mapNotNull { entry ->
            decodeOrNull(entry)?.let { TypedEntry(entry, it) }
        }
    }

    suspend fun delete(id: String) = model.delete(id)

    fun where(conditions: Map<String, Any?>): TypedQueryBuilder<T> {
        return TypedQueryBuilder(model.where(conditions), serializer, json)
    }

    fun where(block: WhereBuilder.() -> Unit): TypedQueryBuilder<T> {
        return TypedQueryBuilder(model.where(block), serializer, json)
    }

    fun observe(): Flow<List<TypedEntry<T>>> {
        return model.observe().map { entries ->
            entries.mapNotNull { entry ->
                decodeOrNull(entry)?.let { TypedEntry(entry, it) }
            }
        }
    }

    private fun encodeToMap(value: T): Map<String, Any?> {
        val jsonStr = json.encodeToString(serializer, value)
        val obj = org.json.JSONObject(jsonStr)
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            map[key] = if (obj.isNull(key)) null else obj.get(key)
        }
        return map
    }

    private fun decodeOrNull(entry: OrmEntry): T? {
        return try {
            val jsonStr = org.json.JSONObject(entry.data).toString()
            json.decodeFromString(serializer, jsonStr)
        } catch (_: Exception) { null }
    }

    companion object {
        /**
         * Wrap a model with type safety. Call from the same module as the data class,
         * or pass the serializer explicitly: TypedModel(model, MyType.serializer())
         */
        inline fun <reified T : Any> wrap(model: Model): TypedModel<T> {
            return TypedModel(model, serializer())
        }
    }
}

/**
 * A typed entry — the raw OrmEntry plus the decoded value.
 */
data class TypedEntry<T>(
    val entry: OrmEntry,
    val value: T
) {
    val id: String get() = entry.id
    val timestamp: Long get() = entry.timestamp
    val authorDeviceId: String get() = entry.authorDeviceId
}

/**
 * Type-safe query builder that returns TypedEntry<T>.
 */
class TypedQueryBuilder<T : Any>(
    private val inner: QueryBuilder,
    private val serializer: KSerializer<T>,
    private val json: Json
) {
    fun where(conditions: Map<String, Any?>) = apply { inner.where(conditions) }
    fun where(block: WhereBuilder.() -> Unit) = apply { inner.where(WhereBuilder().apply(block).conditions) }
    fun orderBy(field: String, direction: String = "desc") = apply { inner.orderBy(field, direction) }
    fun limit(count: Int) = apply { inner.limit(count) }

    suspend fun exec(): List<TypedEntry<T>> {
        return inner.exec().mapNotNull { entry ->
            decodeOrNull(entry)?.let { TypedEntry(entry, it) }
        }
    }

    suspend fun first(): TypedEntry<T>? {
        val entry = inner.first() ?: return null
        val value = decodeOrNull(entry) ?: return null
        return TypedEntry(entry, value)
    }

    suspend fun count(): Int = inner.count()

    fun observe(): Flow<List<TypedEntry<T>>> {
        return inner.observe().map { entries ->
            entries.mapNotNull { entry ->
                decodeOrNull(entry)?.let { TypedEntry(entry, it) }
            }
        }
    }

    private fun decodeOrNull(entry: OrmEntry): T? {
        return try {
            val jsonStr = org.json.JSONObject(entry.data).toString()
            json.decodeFromString(serializer, jsonStr)
        } catch (_: Exception) { null }
    }
}
