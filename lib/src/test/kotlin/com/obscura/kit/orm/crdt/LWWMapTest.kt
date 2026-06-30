package com.obscura.kit.orm.crdt

import com.obscura.kit.newInMemoryStore
import com.obscura.kit.orm.OrmEntry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * LWWMap is the workhorse CRDT for mutable state (profiles, streaks,
 * settings, reactions). Wrong merge semantics here means user data loss
 * when devices reconcile after being offline. These tests exercise the
 * merge invariants without involving the network.
 */
class LWWMapTest {

    private fun entry(id: String, ts: Long, device: String = "d1", data: Map<String, Any?> = emptyMap()) =
        OrmEntry(id = id, data = data, timestamp = ts, authorDeviceId = device)

    @Test
    fun `set keeps newer timestamp wins`() = runTest {
        val map = LWWMap(newInMemoryStore(), "profile")
        val old = entry("u1", 100, data = mapOf("name" to "alice"))
        val new = entry("u1", 200, data = mapOf("name" to "alice-updated"))

        map.set(old)
        map.set(new)

        assertEquals("alice-updated", map.get("u1")?.data?.get("name"))
        assertEquals(200L, map.get("u1")?.timestamp)
    }

    @Test
    fun `set with older timestamp does not overwrite newer`() = runTest {
        val map = LWWMap(newInMemoryStore(), "profile")
        val new = entry("u1", 200, data = mapOf("name" to "current"))
        val old = entry("u1", 100, data = mapOf("name" to "stale"))

        map.set(new)
        map.set(old)

        assertEquals("current", map.get("u1")?.data?.get("name"),
            "An out-of-order delivery of older data must NOT clobber newer data")
    }

    @Test
    fun `merge applies each entry under the same LWW rule`() = runTest {
        val map = LWWMap(newInMemoryStore(), "profile")
        map.set(entry("u1", 100, data = mapOf("v" to "a")))
        map.set(entry("u2", 100, data = mapOf("v" to "b")))

        val updated = map.merge(listOf(
            entry("u1", 200, data = mapOf("v" to "a2")),  // newer, wins
            entry("u2",  50, data = mapOf("v" to "b2")),  // older, loses
            entry("u3", 100, data = mapOf("v" to "c"))    // new id, wins
        ))

        assertEquals(2, updated.size, "merge returns only entries that took effect")
        val ids = updated.map { it.id }.toSet()
        assertTrue("u1" in ids && "u3" in ids)
        assertFalse("u2" in ids)

        assertEquals("a2", map.get("u1")?.data?.get("v"))
        assertEquals("b",  map.get("u2")?.data?.get("v"), "u2 must still hold the original value")
        assertEquals("c",  map.get("u3")?.data?.get("v"))
    }

    @Test
    fun `set is convergent regardless of insertion order`() = runTest {
        // Classic CRDT property: two devices receiving the same set of ops
        // in different orders must end up with the same state. Property
        // testing in miniature: same three ops, three orderings.
        fun runOrder(ops: List<OrmEntry>): Map<String, Any?> {
            val m = LWWMap(newInMemoryStore(), "profile")
            ops.forEach { kotlinx.coroutines.runBlocking { m.set(it) } }
            return kotlinx.coroutines.runBlocking { m.get("u1")?.data ?: emptyMap() }
        }

        val a = entry("u1", 100, data = mapOf("v" to "first"))
        val b = entry("u1", 200, data = mapOf("v" to "second"))
        val c = entry("u1", 150, data = mapOf("v" to "middle"))

        val r1 = runOrder(listOf(a, b, c))
        val r2 = runOrder(listOf(c, b, a))
        val r3 = runOrder(listOf(b, a, c))
        assertEquals(r1, r2)
        assertEquals(r2, r3)
        assertEquals("second", r1["v"], "Highest-timestamp entry must win regardless of arrival order")
    }

    @Test
    fun `set clamps far-future timestamp to limit clock-skew attacks`() = runTest {
        // A malicious or buggy peer could send t=Long.MAX_VALUE to win all
        // future LWW conflicts forever. LWWMap clamps to now + 60s skew.
        val map = LWWMap(newInMemoryStore(), "profile")
        val malicious = entry("u1", Long.MAX_VALUE / 2, data = mapOf("v" to "evil"))

        val stored = map.set(malicious)
        val nowPlusSkew = System.currentTimeMillis() + 60_000

        assertTrue(stored.timestamp <= nowPlusSkew,
            "Far-future timestamp must be clamped; was ${stored.timestamp}")
    }

    @Test
    fun `delete writes a tombstone with the deleting device id`() = runTest {
        val map = LWWMap(newInMemoryStore(), "profile")
        map.set(entry("u1", 100, data = mapOf("name" to "alice")))

        map.delete("u1", authorDeviceId = "device-99")

        // getAll filters tombstones; get returns the tombstone itself.
        assertEquals(0, map.getAll().size, "getAll must hide tombstones")
        val tomb = map.get("u1")!!
        assertTrue(tomb.isDeleted, "Tombstone must carry isDeleted=true")
        assertEquals("device-99", tomb.authorDeviceId,
            "Tombstone must record which device performed the delete")
    }

    @Test
    fun `getAll and size exclude tombstones`() = runTest {
        val map = LWWMap(newInMemoryStore(), "profile")
        map.set(entry("u1", 100, data = mapOf("v" to "a")))
        map.set(entry("u2", 100, data = mapOf("v" to "b")))
        map.delete("u1", "d1")

        assertEquals(1, map.size())
        assertEquals(setOf("u2"), map.getAll().map { it.id }.toSet())
    }

    @Test
    fun `filter respects includeTombstones flag`() = runTest {
        val map = LWWMap(newInMemoryStore(), "profile")
        map.set(entry("u1", 100))
        map.delete("u1", "d1")

        assertEquals(0, map.filter({ true }).size)
        assertEquals(1, map.filter({ true }, includeTombstones = true).size)
    }

    @Test
    fun `getAllSorted descending returns newest first`() = runTest {
        val map = LWWMap(newInMemoryStore(), "profile")
        map.set(entry("a", 100))
        map.set(entry("b", 300))
        map.set(entry("c", 200))

        assertEquals(listOf("b", "c", "a"), map.getAllSorted(descending = true).map { it.id })
        assertEquals(listOf("a", "c", "b"), map.getAllSorted(descending = false).map { it.id })
    }

    @Test
    fun `get on missing id returns null without loading errors`() = runTest {
        val map = LWWMap(newInMemoryStore(), "profile")
        assertNull(map.get("never-set"))
    }
}
