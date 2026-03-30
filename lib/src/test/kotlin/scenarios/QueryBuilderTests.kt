package scenarios

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.db.ObscuraDatabase
import com.obscura.kit.orm.*
import com.obscura.kit.orm.crdt.GSet
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tier 1: QueryBuilder tests — the Rails-like query surface.
 *
 * A Rails dev should look at these tests and think:
 *   "Oh, this is just ActiveRecord scopes."
 *
 * Tests written BEFORE implementation — TDD for the query API.
 */
class QueryBuilderTests {

    private lateinit var schema: Schema

    @BeforeEach
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        val db = ObscuraDatabase(driver)
        val store = ModelStore(db)
        val syncManager = SyncManager(store)
        val ttlManager = TTLManager(store)
        schema = Schema(store, syncManager, ttlManager, "test-device")

        schema.define(mapOf(
            "post" to ModelConfig(
                fields = mapOf(
                    "title" to "string",
                    "author" to "string",
                    "likes" to "number",
                    "published" to "boolean"
                ),
                sync = "gset"
            )
        ))

        // Seed test data
        runBlocking {
            val post = schema.model("post")
            post.create(mapOf("title" to "First post", "author" to "alice", "likes" to 10, "published" to true))
            Thread.sleep(1) // ensure distinct timestamps for ordering tests
            post.create(mapOf("title" to "Second post", "author" to "bob", "likes" to 5, "published" to true))
            Thread.sleep(1)
            post.create(mapOf("title" to "Draft", "author" to "alice", "likes" to 0, "published" to false))
            Thread.sleep(1)
            post.create(mapOf("title" to "Popular", "author" to "carol", "likes" to 100, "published" to true))
            Thread.sleep(1)
            post.create(mapOf("title" to "Mid tier", "author" to "bob", "likes" to 25, "published" to true))
        }
    }

    private fun posts() = schema.model("post")

    // ─── Operators (readable names) ──────────────────────────────

    @Test
    fun `greaterThan`() = runBlocking {
        val popular = posts().where(mapOf("data.likes" to mapOf("greaterThan" to 20))).exec()
        assertEquals(2, popular.size, "Should find posts with likes > 20")
        assertTrue(popular.all { (it.data["likes"] as Number).toInt() > 20 })
    }

    @Test
    fun `atLeast`() = runBlocking {
        val result = posts().where(mapOf("data.likes" to mapOf("atLeast" to 25))).exec()
        assertEquals(2, result.size, "Should find posts with likes >= 25")
    }

    @Test
    fun `lessThan`() = runBlocking {
        val low = posts().where(mapOf("data.likes" to mapOf("lessThan" to 10))).exec()
        assertEquals(2, low.size, "Should find posts with likes < 10")
    }

    @Test
    fun `atMost`() = runBlocking {
        val result = posts().where(mapOf("data.likes" to mapOf("atMost" to 5))).exec()
        assertEquals(2, result.size, "Should find posts with likes <= 5")
    }

    @Test
    fun `equals — explicit equality`() = runBlocking {
        val result = posts().where(mapOf("data.author" to mapOf("equals" to "alice"))).exec()
        assertEquals(2, result.size)
    }

    @Test
    fun `not — exclude a value`() = runBlocking {
        val result = posts().where(mapOf("data.author" to mapOf("not" to "alice"))).exec()
        assertEquals(3, result.size)
    }

    @Test
    fun `contains — substring match`() = runBlocking {
        val result = posts().where(mapOf("data.title" to mapOf("contains" to "post"))).exec()
        assertEquals(2, result.size, "Should find 'First post' and 'Second post'")
    }

    @Test
    fun `startsWith`() = runBlocking {
        val result = posts().where(mapOf("data.title" to mapOf("startsWith" to "First"))).exec()
        assertEquals(1, result.size)
        assertEquals("First post", result[0].data["title"])
    }

    @Test
    fun `endsWith`() = runBlocking {
        val result = posts().where(mapOf("data.title" to mapOf("endsWith" to "post"))).exec()
        assertEquals(2, result.size)
    }

    @Test
    fun `oneOf — value in list`() = runBlocking {
        val result = posts().where(mapOf("data.author" to mapOf("oneOf" to listOf("alice", "carol")))).exec()
        assertEquals(3, result.size, "Should find alice (2) + carol (1)")
    }

    @Test
    fun `noneOf — value not in list`() = runBlocking {
        val result = posts().where(mapOf("data.author" to mapOf("noneOf" to listOf("alice", "carol")))).exec()
        assertEquals(2, result.size, "Should find bob's 2 posts")
    }

    @Test
    fun `Combined operators — range query`() = runBlocking {
        val result = posts().where(mapOf("data.likes" to mapOf("atLeast" to 5, "atMost" to 25))).exec()
        assertEquals(3, result.size, "Should find posts with 5 <= likes <= 25")
    }

    @Test
    fun `Short aliases still work (gt, lte, in)`() = runBlocking {
        // Programmer shorthand is still supported for those who prefer it
        val result = posts().where(mapOf("data.likes" to mapOf("gt" to 20, "lte" to 100))).exec()
        assertEquals(2, result.size)
        val authors = posts().where(mapOf("data.author" to mapOf("in" to listOf("carol")))).exec()
        assertEquals(1, authors.size)
    }

    // ─── orderBy ──────────────────────────────────────────────────

    @Test
    fun `orderBy descending (default)`() = runBlocking {
        val result = posts().where(mapOf()).orderBy("likes").exec()
        val likes = result.map { (it.data["likes"] as Number).toInt() }
        assertEquals(listOf(100, 25, 10, 5, 0), likes)
    }

    @Test
    fun `orderBy ascending`() = runBlocking {
        val result = posts().where(mapOf()).orderBy("data.likes", "asc").exec()
        val likes = result.map { (it.data["likes"] as Number).toInt() }
        assertEquals(listOf(0, 5, 10, 25, 100), likes)
    }

    @Test
    fun `orderBy string field`() = runBlocking {
        val result = posts().where(mapOf()).orderBy("author", "asc").exec()
        val authors = result.map { it.data["author"] as String }
        assertEquals(authors, authors.sorted())
    }

    @Test
    fun `orderBy timestamp`() = runBlocking {
        val result = posts().where(mapOf()).orderBy("timestamp", "asc").exec()
        val timestamps = result.map { it.timestamp }
        assertEquals(timestamps, timestamps.sorted())
    }

    @Test
    fun `orderBy with explicit data prefix still works`() = runBlocking {
        val result = posts().where(mapOf()).orderBy("data.likes").exec()
        val likes = result.map { (it.data["likes"] as Number).toInt() }
        assertEquals(listOf(100, 25, 10, 5, 0), likes)
    }

    // ─── limit ────────────────────────────────────────────────────

    @Test
    fun `limit returns at most N results`() = runBlocking {
        val result = posts().where(mapOf()).limit(2).exec()
        assertEquals(2, result.size)
    }

    @Test
    fun `limit with orderBy — top N pattern`() = runBlocking {
        val top3 = posts().where(mapOf()).orderBy("likes").limit(3).exec()
        assertEquals(3, top3.size)
        val likes = top3.map { (it.data["likes"] as Number).toInt() }
        assertEquals(listOf(100, 25, 10), likes, "Top 3 by likes descending")
    }

    @Test
    fun `limit larger than result set returns all`() = runBlocking {
        val result = posts().where(mapOf()).limit(999).exec()
        assertEquals(5, result.size)
    }

    // ─── Chaining ─────────────────────────────────────────────────

    @Test
    fun `Full chain — where + orderBy + limit`() = runBlocking {
        val result = posts()
            .where(mapOf("data.published" to true))
            .orderBy("likes")
            .limit(2)
            .exec()

        assertEquals(2, result.size)
        val likes = result.map { (it.data["likes"] as Number).toInt() }
        assertEquals(listOf(100, 25), likes, "Top 2 published posts by likes")
    }

    @Test
    fun `Multiple where clauses AND together`() = runBlocking {
        val result = posts()
            .where(mapOf("data.author" to "bob"))
            .where(mapOf("data.published" to true))
            .exec()

        assertEquals(2, result.size, "Bob has 2 published posts")
    }

    // ─── Simple equality still works ──────────────────────────────

    @Test
    fun `Plain value equality (no operator map)`() = runBlocking {
        val result = posts().where(mapOf("data.author" to "alice")).exec()
        assertEquals(2, result.size)
    }

    @Test
    fun `first still works`() = runBlocking {
        val result = posts().where(mapOf("data.author" to "carol")).first()
        assertNotNull(result)
        assertEquals("Popular", result!!.data["title"])
    }

    @Test
    fun `count still works`() = runBlocking {
        assertEquals(5, posts().where(mapOf()).count())
        assertEquals(2, posts().where(mapOf("data.author" to "alice")).count())
    }

    // ─── Kotlin DSL syntax ────────────────────────────────────────

    @Test
    fun `DSL — simple equality`() = runBlocking {
        val result = posts().where { "author" eq "alice" }.exec()
        assertEquals(2, result.size)
    }

    @Test
    fun `DSL — not`() = runBlocking {
        val result = posts().where { "author" not "alice" }.exec()
        assertEquals(3, result.size)
    }

    @Test
    fun `DSL — range with atLeast and atMost`() = runBlocking {
        val result = posts().where { "likes" atLeast 5; "likes" atMost 25 }.exec()
        assertEquals(3, result.size)
    }

    @Test
    fun `DSL — greaterThan`() = runBlocking {
        val result = posts().where { "likes" greaterThan 20 }.exec()
        assertEquals(2, result.size)
    }

    @Test
    fun `DSL — oneOf`() = runBlocking {
        val result = posts().where { "author" oneOf listOf("alice", "carol") }.exec()
        assertEquals(3, result.size)
    }

    @Test
    fun `DSL — contains`() = runBlocking {
        val result = posts().where { "title" contains "post" }.exec()
        assertEquals(2, result.size)
    }

    @Test
    fun `DSL — full chain`() = runBlocking {
        val result = posts()
            .where { "published" eq true }
            .orderBy("likes")
            .limit(2)
            .exec()

        assertEquals(2, result.size)
        val likes = result.map { (it.data["likes"] as Number).toInt() }
        assertEquals(listOf(100, 25), likes)
    }
}
