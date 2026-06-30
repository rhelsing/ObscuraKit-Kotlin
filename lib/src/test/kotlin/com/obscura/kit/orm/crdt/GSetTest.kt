package com.obscura.kit.orm.crdt

import com.obscura.kit.newInMemoryStore
import com.obscura.kit.orm.OrmEntry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * GSet (grow-only set) is the CRDT for immutable content: messages,
 * stories, comments, friend requests. Idempotent add + merge are the
 * core guarantees — re-delivering the same message must not duplicate it.
 */
class GSetTest {

    private fun entry(id: String, ts: Long = 100, data: Map<String, Any?> = emptyMap()) =
        OrmEntry(id = id, data = data, timestamp = ts, authorDeviceId = "d1")

    @Test
    fun `add stores a new entry`() = runTest {
        val set = GSet(newInMemoryStore(), "message")
        set.add(entry("m1", data = mapOf("text" to "hi")))
        assertEquals(1, set.size())
        assertEquals("hi", set.get("m1")?.data?.get("text"))
    }

    @Test
    fun `add is idempotent on duplicate id`() = runTest {
        val set = GSet(newInMemoryStore(), "message")
        set.add(entry("m1", data = mapOf("text" to "original")))
        set.add(entry("m1", data = mapOf("text" to "different")))

        assertEquals(1, set.size(), "Duplicate add must not grow the set")
        assertEquals("original", set.get("m1")?.data?.get("text"),
            "First-write-wins for GSet — subsequent adds with same id are ignored")
    }

    @Test
    fun `merge returns only newly-added entries`() = runTest {
        val set = GSet(newInMemoryStore(), "message")
        set.add(entry("m1", data = mapOf("text" to "a")))

        val added = set.merge(listOf(
            entry("m1", data = mapOf("text" to "dup")), // already present, skipped
            entry("m2", data = mapOf("text" to "b")),
            entry("m3", data = mapOf("text" to "c"))
        ))

        assertEquals(setOf("m2", "m3"), added.map { it.id }.toSet(),
            "merge must report only the genuinely-new entries")
        assertEquals(3, set.size())
        assertEquals("a", set.get("m1")?.data?.get("text"),
            "Existing entry must remain unchanged after merge")
    }

    @Test
    fun `merge is convergent across delivery orders`() = runTest {
        val a = entry("m1", data = mapOf("v" to "1"))
        val b = entry("m2", data = mapOf("v" to "2"))
        val c = entry("m3", data = mapOf("v" to "3"))

        suspend fun runOrder(ops: List<List<OrmEntry>>): Set<String> {
            val s = GSet(newInMemoryStore(), "message")
            ops.forEach { s.merge(it) }
            return s.getAll().map { it.id }.toSet()
        }

        val r1 = runOrder(listOf(listOf(a, b), listOf(b, c)))
        val r2 = runOrder(listOf(listOf(c), listOf(a, b)))
        val r3 = runOrder(listOf(listOf(a), listOf(b), listOf(c), listOf(a, b, c)))
        assertEquals(r1, r2)
        assertEquals(r2, r3)
        assertEquals(setOf("m1", "m2", "m3"), r1)
    }

    @Test
    fun `getAllSorted descending returns newest first`() = runTest {
        val set = GSet(newInMemoryStore(), "message")
        set.add(entry("old", ts = 100))
        set.add(entry("new", ts = 300))
        set.add(entry("mid", ts = 200))

        assertEquals(listOf("new", "mid", "old"), set.getAllSorted(descending = true).map { it.id })
    }

    @Test
    fun `has reports membership correctly`() = runTest {
        val set = GSet(newInMemoryStore(), "message")
        set.add(entry("m1"))
        assertTrue(set.has("m1"))
        assertFalse(set.has("never-added"))
    }

    @Test
    fun `filter applies predicate over all entries`() = runTest {
        val set = GSet(newInMemoryStore(), "message")
        set.add(entry("m1", data = mapOf("topic" to "weather")))
        set.add(entry("m2", data = mapOf("topic" to "sports")))
        set.add(entry("m3", data = mapOf("topic" to "weather")))

        val weather = set.filter { it.data["topic"] == "weather" }
        assertEquals(2, weather.size)
    }
}
