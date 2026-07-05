package com.obscura.kit.orm

import com.obscura.kit.newInMemoryStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TTL is the mechanism that makes stories/pix disappear on schedule. A wrong
 * unit multiplier or an off-by-one in the expiry filter means ephemeral
 * content lingers (privacy leak) or vanishes early (data loss). These tests
 * pin the parsing table and the expiry semantics directly against TTLManager.
 */
class TTLManagerTest {

    private class Fixture {
        val store = newInMemoryStore()
        val ttl = TTLManager(store)
        private val schema = Schema(store, SyncManager(store), ttl, deviceIdProvider = { "d" }).apply {
            define(mapOf("story" to ModelConfig(fields = mapOf("body" to "string"), sync = "lww")))
        }
        val story: Model = schema.model("story")

        fun put(id: String) = runBlocking { story.upsert(id, mapOf("body" to "hi")) }
    }

    // ── parseTTL: unit table ────────────────────────────────────────────

    @Test
    fun `parseTTL converts each unit to milliseconds`() {
        val ttl = TTLManager(newInMemoryStore())
        assertEquals(30_000L, ttl.parseTTL("30s"))
        assertEquals(300_000L, ttl.parseTTL("5m"))
        assertEquals(3_600_000L, ttl.parseTTL("1h"))
        assertEquals(86_400_000L, ttl.parseTTL("1d"))
        assertEquals(604_800_000L, ttl.parseTTL("7d"))
    }

    @Test
    fun `parseTTL accepts zero`() {
        assertEquals(0L, TTLManager(newInMemoryStore()).parseTTL("0s"))
    }

    @Test
    fun `parseTTL rejects malformed strings`() {
        val ttl = TTLManager(newInMemoryStore())
        for (bad in listOf("24", "h", "1x", "", "1.5h", "-1h", "24 h", "h24", "1hh")) {
            assertThrows(IllegalArgumentException::class.java) { ttl.parseTTL(bad) }
        }
    }

    // ── schedule + getTimeRemaining ─────────────────────────────────────

    @Test
    fun `schedule sets a future expiry and getTimeRemaining reflects it`() {
        val f = Fixture()
        f.put("s1")
        f.ttl.schedule("story", "s1", "1h")

        val remaining = f.ttl.getTimeRemaining("story", "s1")
        assertNotNull(remaining)
        // Allow slack for the clock ticking between schedule and read.
        assertTrue(remaining!! > 3_500_000L && remaining <= 3_600_000L, "remaining=$remaining")
        assertFalse(f.ttl.isExpired("story", "s1"))
    }

    @Test
    fun `getTimeRemaining is null for entries without a TTL`() {
        val f = Fixture()
        f.put("s1")
        assertNull(f.ttl.getTimeRemaining("story", "s1"))
    }

    @Test
    fun `getTimeRemaining is null for unknown entries`() {
        val f = Fixture()
        assertNull(f.ttl.getTimeRemaining("story", "does-not-exist"))
    }

    @Test
    fun `isExpired is false when no TTL is set`() {
        val f = Fixture()
        f.put("s1")
        assertFalse(f.ttl.isExpired("story", "s1"))
    }

    // ── expiry semantics ────────────────────────────────────────────────

    @Test
    fun `an entry whose expiry is in the past reads as expired with zero remaining`() {
        val f = Fixture()
        f.put("s1")
        f.store.setTTL("story", "s1", System.currentTimeMillis() - 1_000)

        assertTrue(f.ttl.isExpired("story", "s1"))
        assertEquals(0L, f.ttl.getTimeRemaining("story", "s1"))
    }

    @Test
    fun `expired entries return null from ModelStore find`() {
        val f = Fixture()
        f.put("s1")
        f.store.setTTL("story", "s1", System.currentTimeMillis() - 1_000)
        assertNull(f.store.find("story", "s1"))
    }

    @Test
    fun `expired entries are filtered out of getAll`() {
        val f = Fixture()
        f.put("alive")
        f.put("dead")
        f.store.setTTL("story", "dead", System.currentTimeMillis() - 1_000)

        assertEquals(listOf("alive"), f.store.getAll("story").map { it.id })
    }

    // ── cleanup ─────────────────────────────────────────────────────────

    @Test
    fun `cleanup deletes only expired entries and returns the count`() {
        val f = Fixture()
        f.put("alive")
        f.put("dead1")
        f.put("dead2")
        f.store.setTTL("story", "dead1", System.currentTimeMillis() - 1_000)
        f.store.setTTL("story", "dead2", System.currentTimeMillis() - 1_000)

        val cleaned = f.ttl.cleanup { f.story }

        assertEquals(2, cleaned)
        // Expired rows are gone from the expiry index...
        assertTrue(f.store.getExpired().isEmpty())
        // ...and the surviving entry is untouched.
        assertEquals(listOf("alive"), f.store.getAll("story").map { it.id })
    }

    @Test
    fun `cleanup is a no-op when nothing has expired`() {
        val f = Fixture()
        f.put("s1")
        f.ttl.schedule("story", "s1", "1h")
        assertEquals(0, f.ttl.cleanup { f.story })
    }
}
