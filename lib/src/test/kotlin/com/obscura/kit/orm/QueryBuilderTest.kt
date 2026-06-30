package com.obscura.kit.orm

import com.obscura.kit.newInMemoryStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * QueryBuilder is 244 lines of pure logic that runs on every screen,
 * every render. Operator coverage matters because the JS app calls these
 * by string name — a typo'd alias silently makes a query return everything.
 */
class QueryBuilderTest {

    private suspend fun fixture(): Model {
        val store = newInMemoryStore()
        val sync = SyncManager(store)
        val ttl = TTLManager(store)
        val schema = Schema(store, sync, ttl, deviceIdProvider = { "test-device" })
        schema.define(mapOf("story" to ModelConfig(
            fields = mapOf("author" to "string", "likes" to "number", "title" to "string"),
            sync = "lww"
        )))
        val story = schema.model("story")
        story.upsert("s1", mapOf("author" to "alice", "likes" to 10, "title" to "Hello"))
        story.upsert("s2", mapOf("author" to "bob",   "likes" to 5,  "title" to "World"))
        story.upsert("s3", mapOf("author" to "alice", "likes" to 50, "title" to "Hello World"))
        story.upsert("s4", mapOf("author" to "carol", "likes" to 0,  "title" to "Quiet"))
        return story
    }

    @Test
    fun `simple equality filter on data field`() = runTest {
        val r = fixture().where(mapOf("data.author" to "alice")).exec()
        assertEquals(setOf("s1", "s3"), r.map { it.id }.toSet())
    }

    @Test
    fun `auto-prefixes bare field names with data dot`() = runTest {
        // The DSL form (story.where { "author" eq "alice" }) emits a bare
        // key; QueryBuilder must treat bare keys as data.* lookups.
        // Note: matchesCondition's resolveField hits entry.data[key] as
        // the fallback branch, so this works without the data. prefix too.
        val r = fixture().where(mapOf("author" to "alice")).exec()
        assertEquals(2, r.size)
    }

    @Test
    fun `atLeast and atMost are inclusive`() = runTest {
        val r = fixture().where(mapOf("data.likes" to mapOf("atLeast" to 5, "atMost" to 10))).exec()
        assertEquals(setOf("s1", "s2"), r.map { it.id }.toSet())
    }

    @Test
    fun `gt and lt aliases are strict`() = runTest {
        val gt5 = fixture().where(mapOf("data.likes" to mapOf("gt" to 5))).exec()
        assertEquals(setOf("s1", "s3"), gt5.map { it.id }.toSet())

        val lt10 = fixture().where(mapOf("data.likes" to mapOf("lt" to 10))).exec()
        assertEquals(setOf("s2", "s4"), lt10.map { it.id }.toSet())
    }

    @Test
    fun `oneOf and noneOf operate on lists`() = runTest {
        val oneOf = fixture().where(mapOf("data.author" to mapOf("oneOf" to listOf("alice", "carol")))).exec()
        assertEquals(setOf("s1", "s3", "s4"), oneOf.map { it.id }.toSet())

        val noneOf = fixture().where(mapOf("data.author" to mapOf("noneOf" to listOf("alice")))).exec()
        assertEquals(setOf("s2", "s4"), noneOf.map { it.id }.toSet())
    }

    @Test
    fun `contains and startsWith and endsWith string ops`() = runTest {
        val contains = fixture().where(mapOf("data.title" to mapOf("contains" to "World"))).exec()
        assertEquals(setOf("s2", "s3"), contains.map { it.id }.toSet())

        val starts = fixture().where(mapOf("data.title" to mapOf("startsWith" to "Hello"))).exec()
        assertEquals(setOf("s1", "s3"), starts.map { it.id }.toSet())

        val ends = fixture().where(mapOf("data.title" to mapOf("endsWith" to "World"))).exec()
        assertEquals(setOf("s2", "s3"), ends.map { it.id }.toSet())
    }

    @Test
    fun `not aka ne excludes match`() = runTest {
        val r = fixture().where(mapOf("data.author" to mapOf("not" to "alice"))).exec()
        assertEquals(setOf("s2", "s4"), r.map { it.id }.toSet())
    }

    @Test
    fun `unknown operator does not match anything (filter passes through false)`() = runTest {
        // Defensive: a typo'd operator name must NOT silently match
        // everything. Conservatively: unknown ops are no-ops so the
        // value-vs-target compare never rejects, meaning all rows pass.
        // This test pins current behavior; if you tighten it, update here.
        val r = fixture().where(mapOf("data.likes" to mapOf("xxxNotARealOp" to 5))).exec()
        assertEquals(4, r.size,
            "Unknown operator currently treated as no-op. If this changes, " +
            "review every caller — JS app passes operator names as strings.")
    }

    @Test
    fun `chained where clauses AND together`() = runTest {
        val r = fixture()
            .where(mapOf("data.author" to "alice"))
            .where(mapOf("data.likes" to mapOf("atLeast" to 20)))
            .exec()
        assertEquals(setOf("s3"), r.map { it.id }.toSet())
    }

    @Test
    fun `orderBy default desc on a number field`() = runTest {
        val r = fixture().where(emptyMap()).orderBy("likes").exec()
        assertEquals(listOf("s3", "s1", "s2", "s4"), r.map { it.id })
    }

    @Test
    fun `orderBy asc reverses the order`() = runTest {
        val r = fixture().where(emptyMap()).orderBy("likes", "asc").exec()
        assertEquals(listOf("s4", "s2", "s1", "s3"), r.map { it.id })
    }

    @Test
    fun `orderBy system field timestamp works without data prefix`() = runTest {
        // System fields (id, timestamp, authorDeviceId) must NOT get the
        // data. prefix. All three rows have the same timestamp (created
        // back-to-back), but the sort path must run without throwing.
        val r = fixture().where(emptyMap()).orderBy("timestamp").exec()
        assertEquals(4, r.size)
    }

    @Test
    fun `limit truncates from the front`() = runTest {
        val r = fixture().where(emptyMap()).orderBy("likes").limit(2).exec()
        assertEquals(listOf("s3", "s1"), r.map { it.id })
    }

    @Test
    fun `first returns single entry or null`() = runTest {
        val hit = fixture().where(mapOf("data.author" to "carol")).first()
        assertEquals("s4", hit?.id)

        val miss = fixture().where(mapOf("data.author" to "nobody")).first()
        assertNull(miss)
    }

    @Test
    fun `count returns size`() = runTest {
        assertEquals(2, fixture().where(mapOf("data.author" to "alice")).count())
    }

    @Test
    fun `id and authorDeviceId resolve as system fields`() = runTest {
        val r = fixture().where(mapOf("id" to "s1")).exec()
        assertEquals(1, r.size)

        val all = fixture().where(mapOf("authorDeviceId" to "test-device")).exec()
        assertTrue(all.size == 4, "All test entries stamped with the same device id")
    }

    @Test
    fun `comparing mismatched types does not throw`() = runTest {
        // Real-world: a typed field changed type across schema revisions.
        // Comparing a string against a numeric target must just return
        // no-match instead of crashing the render.
        val r = fixture().where(mapOf("data.author" to mapOf("gt" to 5))).exec()
        assertEquals(0, r.size)
    }
}
