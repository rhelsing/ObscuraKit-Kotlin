package com.obscura.kit.orm

import com.obscura.kit.newInMemoryStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Model.validate is the front line against bad data getting into the CRDT.
 * Once a malformed entry is stored, it's broadcast to peers and tombstones
 * can't recover a schema violation. These tests pin the validation contract.
 */
class ModelValidationTest {

    private fun model(fields: Map<String, String>): Model {
        val store = newInMemoryStore()
        val sync = SyncManager(store)
        val ttl = TTLManager(store)
        return Schema(store, sync, ttl, deviceIdProvider = { "d" }).apply {
            define(mapOf("thing" to ModelConfig(fields = fields, sync = "lww")))
        }.model("thing")
    }

    @Test
    fun `required string field must be present and a string`() {
        val m = model(mapOf("name" to "string"))
        assertThrows(ValidationException::class.java) { m.validate(emptyMap()) }
        assertThrows(ValidationException::class.java) { m.validate(mapOf("name" to 42)) }
        assertDoesNotThrow { m.validate(mapOf("name" to "alice")) }
    }

    @Test
    fun `optional field with trailing question mark accepts null or absent`() {
        val m = model(mapOf("nickname" to "string?"))
        assertDoesNotThrow { m.validate(emptyMap()) }
        assertDoesNotThrow { m.validate(mapOf("nickname" to null)) }
        assertDoesNotThrow { m.validate(mapOf("nickname" to "ali")) }
        assertThrows(ValidationException::class.java) { m.validate(mapOf("nickname" to 1)) }
    }

    @Test
    fun `number accepts Int Long Double`() {
        val m = model(mapOf("count" to "number"))
        assertDoesNotThrow { m.validate(mapOf("count" to 1)) }
        assertDoesNotThrow { m.validate(mapOf("count" to 1L)) }
        assertDoesNotThrow { m.validate(mapOf("count" to 1.5)) }
        assertThrows(ValidationException::class.java) { m.validate(mapOf("count" to "1")) }
    }

    @Test
    fun `boolean is strict`() {
        val m = model(mapOf("flag" to "boolean"))
        assertDoesNotThrow { m.validate(mapOf("flag" to true)) }
        assertDoesNotThrow { m.validate(mapOf("flag" to false)) }
        assertThrows(ValidationException::class.java) { m.validate(mapOf("flag" to "true")) }
        assertThrows(ValidationException::class.java) { m.validate(mapOf("flag" to 1)) }
    }

    @Test
    fun `timestamp must be non-negative number`() {
        val m = model(mapOf("at" to "timestamp"))
        assertDoesNotThrow { m.validate(mapOf("at" to 0L)) }
        assertDoesNotThrow { m.validate(mapOf("at" to System.currentTimeMillis())) }
        assertThrows(ValidationException::class.java) { m.validate(mapOf("at" to -1L)) }
        assertThrows(ValidationException::class.java) { m.validate(mapOf("at" to "now")) }
    }

    @Test
    fun `create rejects entry that fails validation`() = runTest {
        val m = model(mapOf("title" to "string"))
        assertThrows(ValidationException::class.java) {
            kotlinx.coroutines.runBlocking { m.create(mapOf("title" to 42)) }
        }
    }
}
