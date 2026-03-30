package scenarios

import com.obscura.kit.AuthState
import com.obscura.kit.orm.ModelConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * ORM Auto-Sync Integration Tests — proves the "Rails for Signal" promise.
 *
 * The developer calls model.create() and the friend receives it.
 * No sendModelSync(). No encryption calls. No device targeting.
 * It just works.
 */
class ORMAutoSyncTests {

    @Test
    fun `model create auto-syncs to friend — no manual sendModelSync`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("oas_a")
        val bob = registerAndConnect("oas_b")
        becomeFriends(alice, bob)

        // Alice defines a schema and creates a story
        alice.orm.define(mapOf(
            "story" to ModelConfig(
                fields = mapOf("content" to "string", "author" to "string"),
                sync = "gset"
            )
        ))

        // Bob also defines the same schema (both apps know the model)
        bob.orm.define(mapOf(
            "story" to ModelConfig(
                fields = mapOf("content" to "string", "author" to "string"),
                sync = "gset"
            )
        ))

        // Alice creates a story — this should auto-sync to Bob
        val story = alice.orm.model("story")
        val entry = story.create(mapOf("content" to "Hello from the ORM!", "author" to alice.username!!))

        // Bob should receive MODEL_SYNC automatically
        val msg = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg.type, "Bob should receive MODEL_SYNC from alice's create()")
        assertEquals(alice.userId, msg.sourceUserId)

        val modelSync = msg.raw!!.modelSync
        assertEquals("story", modelSync.model)
        assertEquals(entry.id, modelSync.id)

        val data = JSONObject(String(modelSync.data.toByteArray()))
        assertEquals("Hello from the ORM!", data.getString("content"))
        assertEquals(alice.username, data.getString("author"))

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `private model does NOT sync to friend`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("oas_pa")
        val bob = registerAndConnect("oas_pb")
        becomeFriends(alice, bob)

        alice.orm.define(mapOf(
            "settings" to ModelConfig(
                fields = mapOf("theme" to "string"),
                sync = "lww",
                private = true  // Only own devices
            )
        ))

        // Alice creates private settings
        val settings = alice.orm.model("settings")
        settings.create(mapOf("theme" to "dark"))

        // Send a regular text to prove the channel works
        alice.send(bob.username!!, "ping")
        val ping = bob.waitForMessage(15_000)
        assertEquals("TEXT", ping.type, "Bob should receive the text message")

        // If Bob had received a MODEL_SYNC before the text, waitForMessage
        // would have returned it. The fact that we got TEXT means no MODEL_SYNC leaked.

        alice.disconnect()
        bob.disconnect()
    }
}
