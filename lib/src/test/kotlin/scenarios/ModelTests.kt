package scenarios

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.db.ObscuraDatabase
import com.obscura.kit.orm.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tier 1: Model + QueryBuilder unit tests — no server.
 *
 * This is the "Rails developer" surface. These tests prove the API
 * that an app developer actually touches: define a model, create entries,
 * query them, validate them.
 *
 * If a Rails dev can't look at these tests and immediately understand
 * the API, the API is wrong.
 */
class ModelTests {

    private lateinit var db: ObscuraDatabase
    private lateinit var store: ModelStore
    private lateinit var syncManager: SyncManager
    private lateinit var ttlManager: TTLManager

    @BeforeEach
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        db = ObscuraDatabase(driver)
        store = ModelStore(db)
        syncManager = SyncManager(store)
        ttlManager = TTLManager(store)
    }

    private fun defineSchema(vararg models: Pair<String, ModelConfig>): Schema {
        val schema = Schema(store, syncManager, ttlManager, "test-device")
        schema.define(models.toMap())
        return schema
    }

    // ─── Schema Definition ────────────────────────────────────────

    @Test
    fun `Define a model and retrieve it`() {
        val schema = defineSchema(
            "story" to ModelConfig(
                fields = mapOf("content" to "string", "mediaUrl" to "string?"),
                sync = "gset"
            )
        )
        val story = schema.model("story")
        assertNotNull(story)
        assertEquals("story", story!!.name)
    }

    @Test
    fun `Unknown model throws with helpful message`() {
        val schema = defineSchema("story" to ModelConfig(fields = mapOf("content" to "string")))
        val ex = assertThrows(IllegalArgumentException::class.java) {
            schema.model("nonexistent")
        }
        assertTrue(ex.message!!.contains("nonexistent"))
        assertTrue(ex.message!!.contains("story"), "Error should list available models")
    }

    @Test
    fun `modelOrNull returns null for unknown`() {
        val schema = defineSchema("story" to ModelConfig(fields = mapOf("content" to "string")))
        assertNull(schema.modelOrNull("nonexistent"))
    }

    // ─── Create ───────────────────────────────────────────────────

    @Test
    fun `Create entry generates ID and timestamp`() = runBlocking {
        val schema = defineSchema(
            "story" to ModelConfig(fields = mapOf("content" to "string"), sync = "gset")
        )
        val story = schema.model("story")

        val entry = story.create(mapOf("content" to "Hello world!"))

        assertTrue(entry.id.startsWith("story_"), "ID should be prefixed with model name")
        assertTrue(entry.timestamp > 0, "Timestamp should be set")
        assertEquals("test-device", entry.authorDeviceId)
        assertEquals("Hello world!", entry.data["content"])
    }

    @Test
    fun `Created entry is immediately findable`() = runBlocking {
        val schema = defineSchema(
            "story" to ModelConfig(fields = mapOf("content" to "string"), sync = "gset")
        )
        val story = schema.model("story")
        val entry = story.create(mapOf("content" to "find me"))

        val found = story.find(entry.id)
        assertNotNull(found)
        assertEquals("find me", found!!.data["content"])
    }

    @Test
    fun `Create multiple entries — all queryable`() = runBlocking {
        val schema = defineSchema(
            "story" to ModelConfig(fields = mapOf("content" to "string"), sync = "gset")
        )
        val story = schema.model("story")

        story.create(mapOf("content" to "First"))
        story.create(mapOf("content" to "Second"))
        story.create(mapOf("content" to "Third"))

        val all = story.all()
        assertEquals(3, all.size)
    }

    // ─── Validation ───────────────────────────────────────────────

    @Test
    fun `Required field missing throws ValidationException`() = runBlocking {
        val schema = defineSchema(
            "story" to ModelConfig(fields = mapOf("content" to "string"), sync = "gset")
        )
        val story = schema.model("story")

        assertThrows(ValidationException::class.java) {
            runBlocking { story.create(mapOf()) }  // missing "content"
        }
    }

    @Test
    fun `Optional field can be null`() = runBlocking {
        val schema = defineSchema(
            "story" to ModelConfig(
                fields = mapOf("content" to "string", "mediaUrl" to "string?"),
                sync = "gset"
            )
        )
        val story = schema.model("story")

        // Should NOT throw — mediaUrl is optional
        val entry = story.create(mapOf("content" to "text only", "mediaUrl" to null))
        assertEquals("text only", entry.data["content"])
    }

    @Test
    fun `Wrong type throws ValidationException`() = runBlocking {
        val schema = defineSchema(
            "story" to ModelConfig(fields = mapOf("count" to "number"), sync = "lww")
        )
        val model = schema.model("story")

        assertThrows(ValidationException::class.java) {
            runBlocking { model.create(mapOf("count" to "not a number")) }
        }
    }

    // ─── Upsert (LWW) ────────────────────────────────────────────

    @Test
    fun `Upsert creates if not exists`() = runBlocking {
        val schema = defineSchema(
            "settings" to ModelConfig(
                fields = mapOf("theme" to "string"),
                sync = "lww"
            )
        )
        val settings = schema.model("settings")

        val entry = settings.upsert("my_settings", mapOf("theme" to "dark"))
        assertEquals("my_settings", entry.id)
        assertEquals("dark", entry.data["theme"])
    }

    @Test
    fun `Upsert updates existing (newer timestamp wins)`() = runBlocking {
        val schema = defineSchema(
            "settings" to ModelConfig(
                fields = mapOf("theme" to "string"),
                sync = "lww"
            )
        )
        val settings = schema.model("settings")

        settings.upsert("my_settings", mapOf("theme" to "dark"))
        Thread.sleep(5) // Ensure timestamp advances
        settings.upsert("my_settings", mapOf("theme" to "light"))

        val current = settings.find("my_settings")!!
        assertEquals("light", current.data["theme"])
    }

    // ─── Delete (LWW only) ────────────────────────────────────────

    @Test
    fun `Delete creates tombstone on LWW model`() = runBlocking {
        val schema = defineSchema(
            "reaction" to ModelConfig(
                fields = mapOf("emoji" to "string"),
                sync = "lww"
            )
        )
        val reaction = schema.model("reaction")
        val entry = reaction.create(mapOf("emoji" to "heart"))
        reaction.delete(entry.id)

        // all() should exclude deleted
        val all = reaction.all()
        assertTrue(all.none { it.id == entry.id })
    }

    @Test
    fun `Delete on GSet model throws`() = runBlocking {
        val schema = defineSchema(
            "story" to ModelConfig(fields = mapOf("content" to "string"), sync = "gset")
        )
        val story = schema.model("story")
        val entry = story.create(mapOf("content" to "permanent"))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { story.delete(entry.id) }
        }
    }

    // ─── QueryBuilder ─────────────────────────────────────────────

    @Test
    fun `where filters by equality`() = runBlocking {
        val schema = defineSchema(
            "story" to ModelConfig(
                fields = mapOf("content" to "string", "author" to "string"),
                sync = "gset"
            )
        )
        val story = schema.model("story")

        story.create(mapOf("content" to "Alice post", "author" to "alice"))
        story.create(mapOf("content" to "Bob post", "author" to "bob"))
        story.create(mapOf("content" to "Alice again", "author" to "alice"))

        val aliceStories = story.where(mapOf("data.author" to "alice")).exec()
        assertEquals(2, aliceStories.size)
        assertTrue(aliceStories.all { it.data["author"] == "alice" })
    }

    @Test
    fun `where on authorDeviceId`() = runBlocking {
        val schema = defineSchema(
            "story" to ModelConfig(fields = mapOf("content" to "string"), sync = "gset")
        )
        val story = schema.model("story")
        story.create(mapOf("content" to "mine"))

        val mine = story.where(mapOf("authorDeviceId" to "test-device")).exec()
        assertEquals(1, mine.size)
    }

    @Test
    fun `first returns single entry or null`() = runBlocking {
        val schema = defineSchema(
            "story" to ModelConfig(fields = mapOf("content" to "string"), sync = "gset")
        )
        val story = schema.model("story")

        assertNull(story.where(mapOf("data.content" to "nope")).first())

        story.create(mapOf("content" to "exists"))
        val found = story.where(mapOf("data.content" to "exists")).first()
        assertNotNull(found)
    }

    @Test
    fun `count returns correct number`() = runBlocking {
        val schema = defineSchema(
            "story" to ModelConfig(fields = mapOf("content" to "string"), sync = "gset")
        )
        val story = schema.model("story")

        assertEquals(0, story.where(mapOf()).count())
        story.create(mapOf("content" to "a"))
        story.create(mapOf("content" to "b"))
        assertEquals(2, story.where(mapOf()).count())
    }

    // ─── handleSync (incoming MODEL_SYNC) ─────────────────────────

    @Test
    fun `GSet handleSync merges remote entry`() = runBlocking {
        val schema = defineSchema(
            "story" to ModelConfig(fields = mapOf("content" to "string"), sync = "gset")
        )
        val story = schema.model("story")

        // Simulate incoming MODEL_SYNC from a friend
        val synced = story.handleSync(ModelSyncData(
            model = "story",
            id = "remote_story_1",
            timestamp = System.currentTimeMillis(),
            data = """{"content":"from friend"}""".toByteArray(),
            authorDeviceId = "friend-device"
        ))

        assertNotNull(synced)
        val found = story.find("remote_story_1")
        assertNotNull(found)
        assertEquals("from friend", found!!.data["content"])
    }

    @Test
    fun `LWWMap handleSync respects timestamps`() = runBlocking {
        val schema = defineSchema(
            "settings" to ModelConfig(fields = mapOf("theme" to "string"), sync = "lww")
        )
        val settings = schema.model("settings")

        // Local write at t=1000
        settings.upsert("cfg", mapOf("theme" to "dark"))

        // Remote sync arrives with older timestamp — should be rejected
        settings.handleSync(ModelSyncData(
            model = "settings", id = "cfg", timestamp = 500L,
            data = """{"theme":"old_value"}""".toByteArray(),
            authorDeviceId = "remote"
        ))

        val current = settings.find("cfg")!!
        assertNotEquals("old_value", current.data["theme"],
            "Older remote sync should NOT overwrite newer local data")
    }

    // ─── Model with TTL ───────────────────────────────────────────

    @Test
    fun `Create on TTL model schedules expiration`() = runBlocking {
        val schema = defineSchema(
            "story" to ModelConfig(
                fields = mapOf("content" to "string"),
                sync = "gset",
                ttl = "24h"
            )
        )
        val story = schema.model("story")
        val entry = story.create(mapOf("content" to "ephemeral"))

        val remaining = ttlManager.getTimeRemaining("story", entry.id)
        assertNotNull(remaining, "TTL should be scheduled")
        assertTrue(remaining!! > 0, "Entry should not yet be expired")
    }

    // ─── Multi-Model Schema ───────────────────────────────────────

    @Test
    fun `Multiple models in same schema are isolated`() = runBlocking {
        val schema = defineSchema(
            "story" to ModelConfig(fields = mapOf("content" to "string"), sync = "gset"),
            "settings" to ModelConfig(fields = mapOf("theme" to "string"), sync = "lww")
        )

        schema.model("story").create(mapOf("content" to "a story"))
        schema.model("settings").upsert("cfg", mapOf("theme" to "dark"))

        assertEquals(1, schema.model("story").all().size)
        assertEquals(1, schema.model("settings").all().size)
    }

    @Test
    fun `allModels returns the full registry`() {
        val schema = defineSchema(
            "story" to ModelConfig(fields = mapOf("content" to "string"), sync = "gset"),
            "settings" to ModelConfig(fields = mapOf("theme" to "string"), sync = "lww"),
            "reaction" to ModelConfig(fields = mapOf("emoji" to "string"), sync = "lww")
        )
        assertEquals(3, schema.allModels().size)
        assertTrue(schema.allModels().containsKey("story"))
        assertTrue(schema.allModels().containsKey("settings"))
        assertTrue(schema.allModels().containsKey("reaction"))
    }
}
