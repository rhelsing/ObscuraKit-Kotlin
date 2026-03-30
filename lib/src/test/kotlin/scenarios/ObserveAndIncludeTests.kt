package scenarios

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.db.ObscuraDatabase
import com.obscura.kit.orm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tier 1: observe() + include() tests — no server.
 *
 * observe() proves reactive Compose binding works.
 * include() proves has_many/belongs_to eager loading works.
 */
class ObserveAndIncludeTests {

    private lateinit var db: ObscuraDatabase
    private lateinit var store: ModelStore
    private lateinit var schema: Schema

    @BeforeEach
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        db = ObscuraDatabase(driver)
        store = ModelStore(db)
        val syncManager = SyncManager(store)
        val ttlManager = TTLManager(store)
        schema = Schema(store, syncManager, ttlManager, "test-device")
    }

    // ─── observe() ────────────────────────────────────────────────

    @Test
    fun `observe emits current entries`() = runBlocking {
        schema.define(mapOf(
            "story" to ModelConfig(fields = mapOf("content" to "string"), sync = "gset")
        ))
        val story = schema.model("story")

        story.create(mapOf("content" to "First"))
        story.create(mapOf("content" to "Second"))

        val entries = story.observe().first()
        assertEquals(2, entries.size)
    }

    @Test
    fun `observe with where filters reactively`() = runBlocking {
        schema.define(mapOf(
            "post" to ModelConfig(
                fields = mapOf("content" to "string", "author" to "string"),
                sync = "gset"
            )
        ))
        val post = schema.model("post")

        post.create(mapOf("content" to "Alice post", "author" to "alice"))
        post.create(mapOf("content" to "Bob post", "author" to "bob"))

        val alicePosts = post.where(mapOf("data.author" to "alice")).observe().first()
        assertEquals(1, alicePosts.size)
        assertEquals("Alice post", alicePosts[0].data["content"])
    }

    @Test
    fun `observe with orderBy and limit`() = runBlocking {
        schema.define(mapOf(
            "post" to ModelConfig(
                fields = mapOf("title" to "string", "likes" to "number"),
                sync = "gset"
            )
        ))
        val post = schema.model("post")

        post.create(mapOf("title" to "Low", "likes" to 5))
        post.create(mapOf("title" to "High", "likes" to 100))
        post.create(mapOf("title" to "Mid", "likes" to 50))

        val top2 = post.where(mapOf()).orderBy("likes").limit(2).observe().first()
        assertEquals(2, top2.size)
        assertEquals("High", top2[0].data["title"])
        assertEquals("Mid", top2[1].data["title"])
    }

    // ─── include() — has_many / belongs_to ────────────────────────

    @Test
    fun `create with belongsTo auto-registers association`() = runBlocking {
        schema.define(mapOf(
            "story" to ModelConfig(
                fields = mapOf("content" to "string"),
                sync = "gset",
                hasMany = listOf("comment")
            ),
            "comment" to ModelConfig(
                fields = mapOf("text" to "string", "storyId" to "string"),
                sync = "gset",
                belongsTo = listOf("story")
            )
        ))

        val story = schema.model("story")
        val comment = schema.model("comment")

        val s = story.create(mapOf("content" to "My story"))
        comment.create(mapOf("text" to "Nice!", "storyId" to s.id))
        comment.create(mapOf("text" to "Great!", "storyId" to s.id))

        // Direct association query
        val associated = store.getAssociated("story", s.id)
        assertEquals(2, associated.size, "Should find 2 comments associated with story")
    }

    @Test
    fun `include loads child entries on parent`() = runBlocking {
        schema.define(mapOf(
            "story" to ModelConfig(
                fields = mapOf("content" to "string"),
                sync = "gset",
                hasMany = listOf("comment")
            ),
            "comment" to ModelConfig(
                fields = mapOf("text" to "string", "storyId" to "string"),
                sync = "gset",
                belongsTo = listOf("story")
            )
        ))

        val story = schema.model("story")
        val comment = schema.model("comment")

        val s1 = story.create(mapOf("content" to "Story one"))
        val s2 = story.create(mapOf("content" to "Story two"))
        comment.create(mapOf("text" to "Comment on s1", "storyId" to s1.id))
        comment.create(mapOf("text" to "Another on s1", "storyId" to s1.id))
        comment.create(mapOf("text" to "Comment on s2", "storyId" to s2.id))

        val stories = story.where(mapOf()).include("comment").exec()
        assertEquals(2, stories.size)

        val s1Result = stories.find { it.id == s1.id }!!
        assertEquals(2, s1Result.associations["comment"]!!.size,
            "Story one should have 2 comments")

        val s2Result = stories.find { it.id == s2.id }!!
        assertEquals(1, s2Result.associations["comment"]!!.size,
            "Story two should have 1 comment")
    }

    @Test
    fun `include on story with no comments returns empty list`() = runBlocking {
        schema.define(mapOf(
            "story" to ModelConfig(
                fields = mapOf("content" to "string"),
                sync = "gset",
                hasMany = listOf("comment")
            ),
            "comment" to ModelConfig(
                fields = mapOf("text" to "string", "storyId" to "string"),
                sync = "gset",
                belongsTo = listOf("story")
            )
        ))

        val story = schema.model("story")
        val s = story.create(mapOf("content" to "Lonely story"))

        val stories = story.where(mapOf()).include("comment").exec()
        assertEquals(1, stories.size)
        assertEquals(0, stories[0].associations["comment"]?.size ?: 0)
    }

    @Test
    fun `include multiple associations`() = runBlocking {
        schema.define(mapOf(
            "story" to ModelConfig(
                fields = mapOf("content" to "string"),
                sync = "gset",
                hasMany = listOf("comment", "reaction")
            ),
            "comment" to ModelConfig(
                fields = mapOf("text" to "string", "storyId" to "string"),
                sync = "gset",
                belongsTo = listOf("story")
            ),
            "reaction" to ModelConfig(
                fields = mapOf("emoji" to "string", "storyId" to "string"),
                sync = "lww",
                belongsTo = listOf("story")
            )
        ))

        val story = schema.model("story")
        val s = story.create(mapOf("content" to "Popular story"))
        schema.model("comment").create(mapOf("text" to "Nice!", "storyId" to s.id))
        schema.model("reaction").create(mapOf("emoji" to "heart", "storyId" to s.id))
        schema.model("reaction").create(mapOf("emoji" to "fire", "storyId" to s.id))

        val stories = story.where(mapOf()).include("comment", "reaction").exec()
        val result = stories[0]

        assertEquals(1, result.associations["comment"]!!.size)
        assertEquals(2, result.associations["reaction"]!!.size)
    }
}
