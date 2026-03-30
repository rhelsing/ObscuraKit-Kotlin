package scenarios

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.db.ObscuraDatabase
import com.obscura.kit.orm.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tier 1: TypedModel tests — compile-time safe ORM.
 *
 * This is the Kotlin equivalent of iOS's Codable + SyncModel.
 * Instead of mapOf("content" to "..."), you write Story(content = "...").
 */
class TypedModelTests {

    // ─── Model definitions (like iOS structs) ─────────────────────

    @Serializable
    data class Story(val content: String, val authorUsername: String, val mediaUrl: String? = null)

    @Serializable
    data class DirectMessage(val conversationId: String, val content: String, val senderUsername: String)

    @Serializable
    data class Profile(val displayName: String, val bio: String? = null)

    @Serializable
    data class AppSettings(val theme: String, val notificationsEnabled: Boolean)

    // ─── Setup ────────────────────────────────────────────────────

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
            "story" to ModelConfig(fields = mapOf("content" to "string", "authorUsername" to "string", "mediaUrl" to "string?"), sync = "gset", ttl = "24h"),
            "directMessage" to ModelConfig(fields = mapOf("conversationId" to "string", "content" to "string", "senderUsername" to "string"), sync = "gset"),
            "profile" to ModelConfig(fields = mapOf("displayName" to "string", "bio" to "string?"), sync = "lww"),
            "settings" to ModelConfig(fields = mapOf("theme" to "string", "notificationsEnabled" to "boolean"), sync = "lww", private = true)
        ))
    }

    // ─── Typed create + find ──────────────────────────────────────

    @Test
    fun `Create typed Story`() = runBlocking {
        val stories = TypedModel.wrap<Story>(schema.model("story"))
        val entry = stories.create(Story(content = "Beach day!", authorUsername = "alice"))

        assertEquals("Beach day!", entry.value.content)
        assertEquals("alice", entry.value.authorUsername)
        assertTrue(entry.id.startsWith("story_"))
    }

    @Test
    fun `Find typed Story by ID`() = runBlocking {
        val stories = TypedModel.wrap<Story>(schema.model("story"))
        val created = stories.create(Story(content = "Hello", authorUsername = "bob"))

        val found = stories.find(created.id)
        assertNotNull(found)
        assertEquals("Hello", found!!.value.content)
        assertEquals("bob", found.value.authorUsername)
    }

    @Test
    fun `All returns typed entries`() = runBlocking {
        val stories = TypedModel.wrap<Story>(schema.model("story"))
        stories.create(Story(content = "First", authorUsername = "alice"))
        stories.create(Story(content = "Second", authorUsername = "bob"))

        val all = stories.all()
        assertEquals(2, all.size)
        assertTrue(all.any { it.value.content == "First" && it.value.authorUsername == "alice" })
        assertTrue(all.any { it.value.content == "Second" && it.value.authorUsername == "bob" })
    }

    // ─── Typed DirectMessage ──────────────────────────────────────

    @Test
    fun `Create typed DirectMessage`() = runBlocking {
        val messages = TypedModel.wrap<DirectMessage>(schema.model("directMessage"))
        val entry = messages.create(DirectMessage(
            conversationId = "user-123",
            content = "Hey there!",
            senderUsername = "alice"
        ))

        assertEquals("user-123", entry.value.conversationId)
        assertEquals("Hey there!", entry.value.content)
        assertEquals("alice", entry.value.senderUsername)
    }

    // ─── Typed upsert (LWW) ──────────────────────────────────────

    @Test
    fun `Upsert typed Profile`() = runBlocking {
        val profiles = TypedModel.wrap<Profile>(schema.model("profile"))
        profiles.upsert("profile_alice", Profile(displayName = "Alice", bio = "hello"))

        val found = profiles.find("profile_alice")
        assertNotNull(found)
        assertEquals("Alice", found!!.value.displayName)
        assertEquals("hello", found.value.bio)
    }

    @Test
    fun `Upsert typed Settings`() = runBlocking {
        val settings = TypedModel.wrap<AppSettings>(schema.model("settings"))
        settings.upsert("my_settings", AppSettings(theme = "dark", notificationsEnabled = true))

        val found = settings.find("my_settings")
        assertEquals("dark", found!!.value.theme)
        assertTrue(found.value.notificationsEnabled)

        // Update
        Thread.sleep(5)
        settings.upsert("my_settings", AppSettings(theme = "light", notificationsEnabled = false))
        val updated = settings.find("my_settings")
        assertEquals("light", updated!!.value.theme)
        assertFalse(updated.value.notificationsEnabled)
    }

    // ─── Typed query ──────────────────────────────────────────────

    @Test
    fun `Query typed stories with DSL`() = runBlocking {
        val stories = TypedModel.wrap<Story>(schema.model("story"))
        stories.create(Story(content = "Alice post", authorUsername = "alice"))
        stories.create(Story(content = "Bob post", authorUsername = "bob"))
        stories.create(Story(content = "Alice again", authorUsername = "alice"))

        val aliceStories = stories.where { "authorUsername" eq "alice" }.exec()
        assertEquals(2, aliceStories.size)
        assertTrue(aliceStories.all { it.value.authorUsername == "alice" })
    }

    @Test
    fun `Query first typed entry`() = runBlocking {
        val stories = TypedModel.wrap<Story>(schema.model("story"))
        stories.create(Story(content = "Only one", authorUsername = "alice"))

        val first = stories.where { "authorUsername" eq "alice" }.first()
        assertNotNull(first)
        assertEquals("Only one", first!!.value.content)
    }

    // ─── Typed observation ────────────────────────────────────────

    @Test
    fun `Observe returns typed entries`() = runBlocking {
        val stories = TypedModel.wrap<Story>(schema.model("story"))
        stories.create(Story(content = "Observable", authorUsername = "alice"))

        val observed = stories.observe().first()
        assertEquals(1, observed.size)
        assertEquals("Observable", observed[0].value.content)
        assertEquals("alice", observed[0].value.authorUsername)
    }

    // ─── Optional fields ──────────────────────────────────────────

    @Test
    fun `Optional field null round-trips`() = runBlocking {
        val stories = TypedModel.wrap<Story>(schema.model("story"))
        val entry = stories.create(Story(content = "No media", authorUsername = "alice", mediaUrl = null))
        val found = stories.find(entry.id)
        assertNull(found!!.value.mediaUrl)
    }

    @Test
    fun `Optional field with value round-trips`() = runBlocking {
        val stories = TypedModel.wrap<Story>(schema.model("story"))
        val entry = stories.create(Story(content = "With media", authorUsername = "alice", mediaUrl = "https://example.com/photo.jpg"))
        val found = stories.find(entry.id)
        assertEquals("https://example.com/photo.jpg", found!!.value.mediaUrl)
    }
}
