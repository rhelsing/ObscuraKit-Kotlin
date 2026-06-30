package com.obscura.kit.orm

import com.obscura.kit.newInMemoryStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Regression test for the deviceId="" bug:
 *
 *   Before the fix, Schema/Model took a STRING deviceId at construction
 *   time. Models defined before login completed got deviceId="" baked in,
 *   so every local create() stamped authorDeviceId="" on the entry. Down-
 *   stream filters that asked "did THIS device author this entry?" then
 *   matched everything from any pre-login session.
 *
 *   Fix: deviceIdProvider is now `() -> String`, read at create() time,
 *   so a model defined before login still produces correctly-stamped
 *   entries once the device id becomes known.
 *
 * If anyone ever reverts to a constant deviceId, these tests fail loudly.
 */
class DeviceIdPropagationTest {

    private fun schemaWith(provider: () -> String): Schema {
        val store = newInMemoryStore()
        val sync = SyncManager(store)
        val ttl = TTLManager(store)
        return Schema(
            store = store,
            syncManager = sync,
            ttlManager = ttl,
            deviceIdProvider = provider
        ).apply {
            define(mapOf("note" to ModelConfig(
                fields = mapOf("text" to "string"),
                sync = "lww"
            )))
        }
    }

    @Test
    fun `model created before login picks up deviceId once provider resolves`() = runTest {
        // Simulates the original bug scenario: schema defined eagerly at
        // app startup before the user has logged in.
        var currentDeviceId = ""
        val schema = schemaWith { currentDeviceId }
        val note = schema.model("note")

        // Pre-login create — provider returns ""
        val before = note.create(mapOf("text" to "pre-login draft"))
        assertEquals("", before.authorDeviceId,
            "Sanity check: before login, provider returns empty")

        // Login happens — device id is now known
        currentDeviceId = "device-abc-123"

        // Post-login create — provider returns the real id WITHOUT
        // schema being rebuilt. This is the regression: before the fix,
        // the second create would also stamp "".
        val after = note.create(mapOf("text" to "post-login note"))
        assertEquals("device-abc-123", after.authorDeviceId,
            "REGRESSION: deviceIdProvider must be read at create() time, " +
            "not captured at schema-define time")
        assertNotEquals(before.authorDeviceId, after.authorDeviceId)
    }

    @Test
    fun `upsert also reads the latest deviceId at write time`() = runTest {
        var currentDeviceId = "old-device"
        val schema = schemaWith { currentDeviceId }
        val note = schema.model("note")

        val first = note.upsert("fixed-id", mapOf("text" to "initial"))
        assertEquals("old-device", first.authorDeviceId)

        // Device id changes mid-session (e.g. takeover scenario)
        currentDeviceId = "new-device"

        val second = note.upsert("fixed-id", mapOf("text" to "updated"))
        assertEquals("new-device", second.authorDeviceId,
            "Each upsert must re-resolve the current device id")
    }

    @Test
    fun `delete tombstone also carries the current deviceId`() = runTest {
        var currentDeviceId = ""
        val schema = schemaWith { currentDeviceId }
        val note = schema.model("note")

        val created = note.create(mapOf("text" to "x"))
        currentDeviceId = "device-z"
        note.delete(created.id)

        val tomb = note.find(created.id)!!
        assertEquals(true, tomb.data["_deleted"],
            "Sanity: deleted entry is a tombstone")
        assertEquals("device-z", tomb.authorDeviceId,
            "Tombstone author must reflect the device that performed the delete, " +
            "not the device captured at schema-define time")
    }
}
