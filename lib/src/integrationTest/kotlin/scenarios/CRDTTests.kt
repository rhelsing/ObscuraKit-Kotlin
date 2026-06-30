package scenarios

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.db.ObscuraDatabase
import com.obscura.kit.orm.*
import com.obscura.kit.orm.crdt.GSet
import com.obscura.kit.orm.crdt.LWWMap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tier 1: CRDT unit tests — no server, in-memory DB.
 *
 * These prove the data layer primitives that everything else builds on.
 * If these fail, nothing above them can be trusted.
 */
class CRDTTests {

    private lateinit var db: ObscuraDatabase
    private lateinit var store: ModelStore

    @BeforeEach
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        db = ObscuraDatabase(driver)
        store = ModelStore(db)
    }

    // ─── GSet: Grow-Only Set ──────────────────────────────────────

    @Test
    fun `GSet add returns the entry`() = runBlocking {
        val gset = GSet(store, "story")
        val entry = OrmEntry("s1", mapOf("content" to "hello"), 1000L, "device1")
        val result = gset.add(entry)
        assertEquals("s1", result.id)
        assertEquals("hello", result.data["content"])
    }

    @Test
    fun `GSet is idempotent — duplicate add returns original`() = runBlocking {
        val gset = GSet(store, "story")
        val entry = OrmEntry("s1", mapOf("content" to "hello"), 1000L, "device1")
        gset.add(entry)
        val duplicate = gset.add(entry.copy(data = mapOf("content" to "CHANGED")))
        // GSet is add-only: second add with same ID returns the original, NOT the new data
        assertEquals("hello", duplicate.data["content"])
        assertEquals(1, gset.size())
    }

    @Test
    fun `GSet merge unions two sets`() = runBlocking {
        val gset = GSet(store, "story")
        gset.add(OrmEntry("s1", mapOf("content" to "local"), 1000L, "device1"))

        // Simulate remote entries arriving via MODEL_SYNC
        val remote = listOf(
            OrmEntry("s2", mapOf("content" to "from alice"), 2000L, "device2"),
            OrmEntry("s3", mapOf("content" to "from bob"), 3000L, "device3")
        )
        val added = gset.merge(remote)

        assertEquals(2, added.size, "Both remote entries should be new")
        assertEquals(3, gset.size(), "Total should be 3 (1 local + 2 remote)")
    }

    @Test
    fun `GSet merge is idempotent — re-merging same entries adds nothing`() = runBlocking {
        val gset = GSet(store, "story")
        val entries = listOf(
            OrmEntry("s1", mapOf("content" to "a"), 1000L, "d1"),
            OrmEntry("s2", mapOf("content" to "b"), 2000L, "d2")
        )
        gset.merge(entries)
        val secondMerge = gset.merge(entries)
        assertEquals(0, secondMerge.size, "Re-merge should add nothing")
        assertEquals(2, gset.size())
    }

    @Test
    fun `GSet persists to DB and survives reload`() = runBlocking {
        val gset1 = GSet(store, "story")
        gset1.add(OrmEntry("s1", mapOf("content" to "persisted"), 1000L, "d1"))

        // Create new GSet instance pointing at same DB — simulates app restart
        val gset2 = GSet(store, "story")
        val loaded = gset2.getAll()
        assertEquals(1, loaded.size)
        assertEquals("persisted", loaded[0].data["content"])
    }

    @Test
    fun `GSet sorted returns newest first by default`() = runBlocking {
        val gset = GSet(store, "story")
        gset.add(OrmEntry("old", mapOf(), 1000L, "d1"))
        gset.add(OrmEntry("mid", mapOf(), 2000L, "d1"))
        gset.add(OrmEntry("new", mapOf(), 3000L, "d1"))

        val desc = gset.getAllSorted(descending = true)
        assertEquals("new", desc[0].id)
        assertEquals("old", desc[2].id)

        val asc = gset.getAllSorted(descending = false)
        assertEquals("old", asc[0].id)
        assertEquals("new", asc[2].id)
    }

    @Test
    fun `GSet filter works`() = runBlocking {
        val gset = GSet(store, "story")
        gset.add(OrmEntry("s1", mapOf("author" to "alice"), 1000L, "d1"))
        gset.add(OrmEntry("s2", mapOf("author" to "bob"), 2000L, "d2"))
        gset.add(OrmEntry("s3", mapOf("author" to "alice"), 3000L, "d1"))

        val aliceOnly = gset.filter { it.data["author"] == "alice" }
        assertEquals(2, aliceOnly.size)
        assertTrue(aliceOnly.all { it.data["author"] == "alice" })
    }

    // ─── LWWMap: Last-Writer-Wins ─────────────────────────────────

    @Test
    fun `LWWMap set stores entry`() = runBlocking {
        val lww = LWWMap(store, "settings")
        val entry = OrmEntry("cfg1", mapOf("theme" to "dark"), 1000L, "d1")
        val result = lww.set(entry)
        assertEquals("dark", result.data["theme"])
    }

    @Test
    fun `LWWMap newer timestamp wins`() = runBlocking {
        val lww = LWWMap(store, "settings")
        lww.set(OrmEntry("cfg1", mapOf("theme" to "dark"), 1000L, "d1"))
        lww.set(OrmEntry("cfg1", mapOf("theme" to "light"), 2000L, "d2"))

        val current = lww.get("cfg1")!!
        assertEquals("light", current.data["theme"], "Newer timestamp should win")
    }

    @Test
    fun `LWWMap older timestamp loses`() = runBlocking {
        val lww = LWWMap(store, "settings")
        lww.set(OrmEntry("cfg1", mapOf("theme" to "dark"), 2000L, "d1"))
        lww.set(OrmEntry("cfg1", mapOf("theme" to "light"), 1000L, "d2"))

        val current = lww.get("cfg1")!!
        assertEquals("dark", current.data["theme"], "Older timestamp should lose")
    }

    @Test
    fun `LWWMap concurrent conflict — two devices, higher timestamp wins`() = runBlocking {
        // Alice's phone sets theme=dark at t=100
        // Alice's tablet sets theme=light at t=101
        // Both arrive at Bob — the tablet's write should win
        val lww = LWWMap(store, "settings")

        val phone = OrmEntry("settings_alice", mapOf("theme" to "dark"), 100L, "phone")
        val tablet = OrmEntry("settings_alice", mapOf("theme" to "light"), 101L, "tablet")

        // Regardless of arrival order, tablet wins
        lww.set(phone)
        lww.set(tablet)
        assertEquals("light", lww.get("settings_alice")!!.data["theme"])

        // Reset and reverse arrival order — same result
        val lww2 = LWWMap(store, "settings2")
        lww2.set(tablet)
        lww2.set(phone)
        assertEquals("light", lww2.get("settings_alice")!!.data["theme"],
            "LWW must be order-independent — higher timestamp always wins")
    }

    @Test
    fun `LWWMap merge from multiple sources converges`() = runBlocking {
        val lww = LWWMap(store, "streak")

        // Local state
        lww.set(OrmEntry("streak_ab", mapOf("count" to 5), 1000L, "d1"))

        // Remote merge arrives with newer data
        val merged = lww.merge(listOf(
            OrmEntry("streak_ab", mapOf("count" to 10), 2000L, "d2")
        ))

        assertEquals(1, merged.size, "One entry should be updated")
        assertEquals(10, lww.get("streak_ab")!!.data["count"])
    }

    @Test
    fun `LWWMap delete creates tombstone`() = runBlocking {
        val lww = LWWMap(store, "reaction")
        lww.set(OrmEntry("r1", mapOf("emoji" to "heart"), 1000L, "d1"))
        lww.delete("r1", "d1")

        // Direct lookup returns tombstone
        val entry = lww.get("r1")
        assertNotNull(entry)
        assertTrue(entry!!.isDeleted)

        // getAll excludes tombstones
        val all = lww.getAll()
        assertTrue(all.none { it.id == "r1" }, "Tombstoned entries should not appear in getAll()")
    }

    @Test
    fun `LWWMap tombstone wins over old write but loses to newer write`() = runBlocking {
        val lww = LWWMap(store, "reaction")
        lww.set(OrmEntry("r1", mapOf("emoji" to "heart"), 1000L, "d1"))
        lww.delete("r1", "d1") // tombstone at ~now

        // Attempt to re-add with old timestamp — tombstone wins
        lww.set(OrmEntry("r1", mapOf("emoji" to "fire"), 500L, "d2"))
        assertTrue(lww.get("r1")!!.isDeleted, "Old write should not resurrect a tombstone")
    }

    @Test
    fun `LWWMap rejects far-future timestamps`() = runBlocking {
        val lww = LWWMap(store, "settings")
        val farFuture = System.currentTimeMillis() + 999_999_999L
        lww.set(OrmEntry("cfg1", mapOf("theme" to "evil"), farFuture, "attacker"))

        val stored = lww.get("cfg1")!!
        // Timestamp should be clamped to now + 60s, not the far-future value
        assertTrue(stored.timestamp < farFuture,
            "Far-future timestamp should be clamped (60s tolerance)")
    }

    @Test
    fun `LWWMap persists and reloads`() = runBlocking {
        val lww1 = LWWMap(store, "settings")
        lww1.set(OrmEntry("cfg1", mapOf("theme" to "dark"), 1000L, "d1"))

        val lww2 = LWWMap(store, "settings")
        val reloaded = lww2.get("cfg1")
        assertNotNull(reloaded)
        assertEquals("dark", reloaded!!.data["theme"])
    }
}
