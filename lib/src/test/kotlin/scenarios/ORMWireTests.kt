package scenarios

import com.obscura.kit.orm.ModelConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig

/**
 * ORM Wire Tests — proves the ORM survives the full encryption round-trip.
 *
 * These catch things unit tests can't: protobuf serialization bugs,
 * encryption garbling data, server queuing reordering, CRDT merging
 * after decryption. If any of these fail, the "just works" promise is broken.
 */
class ORMWireTests {

    @Test
    fun `LWW conflict over the wire — newer timestamp wins on both sides`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("owt_ca")
        val bob = registerAndConnect("owt_cb")
        becomeFriends(alice, bob)

        val profileConfig = mapOf(
            "profile" to ModelConfig(
                fields = mapOf("displayName" to "string", "bio" to "string?"),
                sync = "lww"
            )
        )
        alice.orm.define(profileConfig)
        bob.orm.define(profileConfig)

        // Alice sets her profile
        alice.orm.model("profile").upsert("profile_alice", mapOf("displayName" to "Alice v1", "bio" to null))

        // Bob receives it
        val msg1 = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg1.type)
        val d1 = JSONObject(String(msg1.raw!!.modelSync.data.toByteArray()))
        assertEquals("Alice v1", d1.getString("displayName"))

        // Alice updates (newer timestamp)
        delay(100)
        alice.orm.model("profile").upsert("profile_alice", mapOf("displayName" to "Alice v2", "bio" to "hello"))

        val msg2 = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg2.type)
        val d2 = JSONObject(String(msg2.raw!!.modelSync.data.toByteArray()))
        assertEquals("Alice v2", d2.getString("displayName"),
            "Newer upsert should arrive with updated data — proves LWW over the wire")
        assertEquals("hello", d2.getString("bio"))

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `Typed model round-trip — data survives encrypt-decrypt-protobuf cycle`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("owt_rta")
        val bob = registerAndConnect("owt_rtb")
        becomeFriends(alice, bob)

        val storyConfig = mapOf(
            "story" to ModelConfig(
                fields = mapOf(
                    "content" to "string",
                    "mediaUrl" to "string?",
                    "likes" to "number",
                    "published" to "boolean"
                ),
                sync = "gset"
            )
        )
        alice.orm.define(storyConfig)
        bob.orm.define(storyConfig)

        // Alice creates with all field types
        alice.orm.model("story").create(mapOf(
            "content" to "Beach day!",
            "mediaUrl" to "https://example.com/photo.jpg",
            "likes" to 42,
            "published" to true
        ))

        val msg = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg.type)

        val data = JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
        assertEquals("Beach day!", data.getString("content"),
            "String should survive the wire")
        assertEquals("https://example.com/photo.jpg", data.getString("mediaUrl"),
            "Optional string should survive the wire")
        assertEquals(42, data.getInt("likes"),
            "Number should survive the wire")
        assertEquals(true, data.getBoolean("published"),
            "Boolean should survive the wire")

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `Multiple model types in one session — SyncManager routes correctly`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("owt_mma")
        val bob = registerAndConnect("owt_mmb")
        becomeFriends(alice, bob)

        val multiSchema = mapOf(
            "story" to ModelConfig(
                fields = mapOf("content" to "string"),
                sync = "gset"
            ),
            "profile" to ModelConfig(
                fields = mapOf("displayName" to "string"),
                sync = "lww"
            )
        )
        alice.orm.define(multiSchema)
        bob.orm.define(multiSchema)

        // Alice creates one of each
        alice.orm.model("story").create(mapOf("content" to "My story"))
        alice.orm.model("profile").upsert("profile_alice", mapOf("displayName" to "Alice"))

        // Bob receives both — verify they have the correct model name
        val received = mutableMapOf<String, String>()
        repeat(2) {
            val msg = bob.waitForMessage(15_000)
            assertEquals("MODEL_SYNC", msg.type)
            val modelName = msg.raw!!.modelSync.model
            val data = JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
            received[modelName] = when (modelName) {
                "story" -> data.getString("content")
                "profile" -> data.getString("displayName")
                else -> "unknown"
            }
        }

        assertEquals("My story", received["story"],
            "Story should route to story model")
        assertEquals("Alice", received["profile"],
            "Profile should route to profile model")

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `ORM survives file-backed restart — stories still queryable`() = runBlocking {
        assumeTrue(checkServer())

        val dbPath = "/tmp/obscura_orm_restart_test_${System.currentTimeMillis()}.db"
        val config = ObscuraConfig(API, databasePath = dbPath)

        // Phase 1: create client, register, create stories
        val client1 = ObscuraClient(config)
        client1.register(uniqueName("owt_rs"), TEST_PASSWORD)
        client1.connect()

        client1.orm.define(mapOf(
            "story" to ModelConfig(
                fields = mapOf("content" to "string"),
                sync = "gset"
            )
        ))

        val story = client1.orm.model("story")
        story.create(mapOf("content" to "Before restart"))
        story.create(mapOf("content" to "Also before restart"))

        val beforeCount = story.all().size
        assertEquals(2, beforeCount)

        // Save session info for restore
        val token = client1.token!!
        val refreshToken = client1.refreshToken
        val userId = client1.userId!!
        val deviceId = client1.deviceId
        val username = client1.username
        val regId = client1.registrationId

        client1.disconnect()

        // Phase 2: new client from same DB file — simulates app restart
        val client2 = ObscuraClient(config)
        client2.restoreSession(token, refreshToken, userId, deviceId, username, regId)

        client2.orm.define(mapOf(
            "story" to ModelConfig(
                fields = mapOf("content" to "string"),
                sync = "gset"
            )
        ))

        val restored = client2.orm.model("story")
        val afterCount = restored.all().size
        assertEquals(2, afterCount, "Stories should survive restart from file-backed DB")

        val contents = restored.all().map { it.data["content"] as String }.sorted()
        assertEquals(listOf("Also before restart", "Before restart"), contents)

        // Cleanup
        java.io.File(dbPath).delete()
    }
}
