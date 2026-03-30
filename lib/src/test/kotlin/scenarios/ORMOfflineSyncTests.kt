package scenarios

import com.obscura.kit.orm.ModelConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * ORM Offline Sync — proves the "just works" promise survives disconnect/reconnect.
 *
 * The developer never thinks about offline queuing.
 * model.create() while friend is offline → friend reconnects → data arrives.
 */
class ORMOfflineSyncTests {

    @Test
    fun `model create while friend offline — friend gets it on reconnect`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("ooff_a")
        val bob = registerAndConnect("ooff_b")
        becomeFriends(alice, bob)

        // Both define the same schema
        val storyConfig = mapOf(
            "story" to ModelConfig(
                fields = mapOf("content" to "string", "author" to "string"),
                sync = "gset"
            )
        )
        alice.orm.define(storyConfig)
        bob.orm.define(storyConfig)

        // Bob goes offline
        bob.disconnect()
        delay(500)

        // Alice creates a story while Bob is gone
        val story = alice.orm.model("story")
        val entry = story.create(mapOf("content" to "Posted while you were away", "author" to alice.username!!))

        // Give server time to queue it
        delay(1000)

        // Bob comes back
        bob.connect()

        // Bob should receive the queued MODEL_SYNC
        val msg = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg.type)
        assertEquals(alice.userId, msg.sourceUserId)
        assertEquals("story", msg.raw!!.modelSync.model)
        assertEquals(entry.id, msg.raw!!.modelSync.id)

        val data = JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
        assertEquals("Posted while you were away", data.getString("content"))

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `multiple model creates while offline — all arrive on reconnect`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("ooff_ma")
        val bob = registerAndConnect("ooff_mb")
        becomeFriends(alice, bob)

        val storyConfig = mapOf(
            "story" to ModelConfig(
                fields = mapOf("content" to "string"),
                sync = "gset"
            )
        )
        alice.orm.define(storyConfig)
        bob.orm.define(storyConfig)

        // Bob goes offline
        bob.disconnect()
        delay(500)

        // Alice creates 3 stories while Bob is offline
        val story = alice.orm.model("story")
        story.create(mapOf("content" to "First"))
        delay(200)
        story.create(mapOf("content" to "Second"))
        delay(200)
        story.create(mapOf("content" to "Third"))
        delay(500)

        // Bob reconnects
        bob.connect()

        // Drain all 3
        val received = mutableListOf<String>()
        repeat(3) {
            val msg = bob.waitForMessage(15_000)
            assertEquals("MODEL_SYNC", msg.type)
            val d = JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
            received.add(d.getString("content"))
        }

        assertTrue(received.contains("First"), "Should receive First")
        assertTrue(received.contains("Second"), "Should receive Second")
        assertTrue(received.contains("Third"), "Should receive Third")

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `ORM works normally after reconnect — bidirectional`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("ooff_ba")
        val bob = registerAndConnect("ooff_bb")
        becomeFriends(alice, bob)

        val storyConfig = mapOf(
            "story" to ModelConfig(
                fields = mapOf("content" to "string"),
                sync = "gset"
            )
        )
        alice.orm.define(storyConfig)
        bob.orm.define(storyConfig)

        // Alice disconnects and reconnects
        alice.disconnect()
        delay(500)
        alice.connect()
        delay(500)

        // Alice creates after reconnect — should still auto-sync
        alice.orm.model("story").create(mapOf("content" to "Back online"))

        val msg = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg.type)
        val d = JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
        assertEquals("Back online", d.getString("content"))

        // Bob creates — Alice should receive
        bob.orm.model("story").create(mapOf("content" to "Bob replies"))

        val msg2 = alice.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg2.type)
        val d2 = JSONObject(String(msg2.raw!!.modelSync.data.toByteArray()))
        assertEquals("Bob replies", d2.getString("content"))

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `LWW conflict resolution after offline — newer timestamp wins`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("ooff_lwa")
        val bob = registerAndConnect("ooff_lwb")
        becomeFriends(alice, bob)

        val profileConfig = mapOf(
            "profile" to ModelConfig(
                fields = mapOf("displayName" to "string"),
                sync = "lww"
            )
        )
        alice.orm.define(profileConfig)
        bob.orm.define(profileConfig)

        // Alice sets profile while both online
        alice.orm.model("profile").upsert("shared_profile", mapOf("displayName" to "v1"))
        val msg1 = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg1.type)

        // Bob goes offline
        bob.disconnect()
        delay(500)

        // Alice updates profile twice while Bob is away (newer wins)
        alice.orm.model("profile").upsert("shared_profile", mapOf("displayName" to "v2"))
        delay(200)
        alice.orm.model("profile").upsert("shared_profile", mapOf("displayName" to "v3"))
        delay(500)

        // Bob reconnects — should get both updates, final state should be v3
        bob.connect()
        val received = mutableListOf<String>()
        repeat(2) {
            val msg = bob.waitForMessage(15_000)
            assertEquals("MODEL_SYNC", msg.type)
            val d = org.json.JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
            received.add(d.getString("displayName"))
        }

        // Both v2 and v3 should arrive (order may vary), but v3 is the latest
        assertTrue(received.contains("v3"), "Final update (v3) should arrive after reconnect")

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `Both sides create while other is offline — both arrive on reconnect`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("ooff_bsa")
        val bob = registerAndConnect("ooff_bsb")
        becomeFriends(alice, bob)

        val storyConfig = mapOf(
            "story" to ModelConfig(
                fields = mapOf("content" to "string"),
                sync = "gset"
            )
        )
        alice.orm.define(storyConfig)
        bob.orm.define(storyConfig)

        // Bob goes offline, Alice creates
        bob.disconnect()
        delay(500)
        alice.orm.model("story").create(mapOf("content" to "Alice while Bob offline"))
        delay(500)

        // Alice goes offline, Bob comes back and creates
        bob.connect()
        val fromAlice = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", fromAlice.type)
        val aliceContent = org.json.JSONObject(String(fromAlice.raw!!.modelSync.data.toByteArray()))
        assertEquals("Alice while Bob offline", aliceContent.getString("content"))

        alice.disconnect()
        delay(500)
        bob.orm.model("story").create(mapOf("content" to "Bob while Alice offline"))
        delay(500)

        // Alice comes back — should get Bob's story
        alice.connect()
        val fromBob = alice.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", fromBob.type)
        val bobContent = org.json.JSONObject(String(fromBob.raw!!.modelSync.data.toByteArray()))
        assertEquals("Bob while Alice offline", bobContent.getString("content"))

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `private model still private after reconnect`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("ooff_pa")
        val bob = registerAndConnect("ooff_pb")
        becomeFriends(alice, bob)

        alice.orm.define(mapOf(
            "settings" to ModelConfig(
                fields = mapOf("theme" to "string"),
                sync = "lww",
                private = true
            )
        ))

        // Alice disconnects and reconnects
        alice.disconnect()
        delay(500)
        alice.connect()
        delay(500)

        // Alice creates private settings after reconnect
        alice.orm.model("settings").create(mapOf("theme" to "dark"))

        // Send a text to prove the pipe works
        alice.send(bob.username!!, "still here")
        val msg = bob.waitForMessage(15_000)
        assertEquals("TEXT", msg.type, "Bob should get text, not MODEL_SYNC — settings are private")

        alice.disconnect()
        bob.disconnect()
    }
}
