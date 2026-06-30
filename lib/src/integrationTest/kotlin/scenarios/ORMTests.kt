package scenarios

import com.obscura.kit.AuthState
import com.obscura.kit.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Scenario 8: ORM MODEL_SYNC E2E.
 * Full lifecycle: register, befriend via becomeFriends(), send MODEL_SYNC, verify fields, bidirectional.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ORMTests {

    @Test @Order(1)
    fun `8-1 - MODEL_SYNC CREATE delivered to friend with correct fields`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("o8a")
        val bob = registerAndConnect("o8b")
        assertEquals(AuthState.AUTHENTICATED, alice.authState.value)
        assertEquals(AuthState.AUTHENTICATED, bob.authState.value)

        becomeFriends(alice, bob)

        // Alice sends MODEL_SYNC CREATE
        val entryId = "story_${System.currentTimeMillis()}"
        alice.sendModelSync(bob.username!!, "story", entryId,
            data = mapOf("content" to "Hello ORM"))

        // Bob receives MODEL_SYNC
        val msg = bob.waitForMessage()
        assertEquals("MODEL_SYNC", msg.type, "Message type should be MODEL_SYNC")
        assertEquals(alice.userId, msg.sourceUserId, "Source should be alice")

        // Verify model and data fields
        val modelSync = msg.raw!!.modelSync
        assertEquals("story", modelSync.model, "Model name should be 'story'")
        assertEquals(entryId, modelSync.id, "Entry ID should match")

        val data = JSONObject(String(modelSync.data.toByteArray()))
        assertEquals("Hello ORM", data.getString("content"), "Content should match")

        alice.disconnect()
        bob.disconnect()
    }

    @Test @Order(2)
    fun `8-2 - Bidirectional MODEL_SYNC exchange`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("o8ba")
        val bob = registerAndConnect("o8bb")
        becomeFriends(alice, bob)

        // Alice sends model to bob
        alice.sendModelSync(bob.username!!, "story", "from_alice",
            data = mapOf("content" to "Alice story"))
        val atBob = bob.waitForMessage()
        assertEquals("MODEL_SYNC", atBob.type)
        assertEquals(alice.userId, atBob.sourceUserId)
        assertEquals("from_alice", atBob.raw!!.modelSync.id)
        val bobData = JSONObject(String(atBob.raw!!.modelSync.data.toByteArray()))
        assertEquals("Alice story", bobData.getString("content"))

        // Bob sends model back to alice
        bob.sendModelSync(alice.username!!, "story", "from_bob",
            data = mapOf("content" to "Bob story"))
        val atAlice = alice.waitForMessage()
        assertEquals("MODEL_SYNC", atAlice.type)
        assertEquals(bob.userId, atAlice.sourceUserId)
        assertEquals("from_bob", atAlice.raw!!.modelSync.id)
        val aliceData = JSONObject(String(atAlice.raw!!.modelSync.data.toByteArray()))
        assertEquals("Bob story", aliceData.getString("content"))

        alice.disconnect()
        bob.disconnect()
    }

    @Test @Order(3)
    fun `8-3 - MODEL_SYNC UPDATE (LWW) delivers latest value`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("o8ua")
        val bob = registerAndConnect("o8ub")
        becomeFriends(alice, bob)

        val id = "streak_${System.currentTimeMillis()}"

        // Initial CREATE
        alice.sendModelSync(bob.username!!, "streak", id,
            data = mapOf("count" to 5))
        val m1 = bob.waitForMessage()
        assertEquals("MODEL_SYNC", m1.type)
        val d1 = JSONObject(String(m1.raw!!.modelSync.data.toByteArray()))
        assertEquals(5, d1.getInt("count"), "Initial count should be 5")

        // UPDATE with LWW
        alice.sendModelSync(bob.username!!, "streak", id, op = "UPDATE",
            data = mapOf("count" to 10))
        val m2 = bob.waitForMessage()
        assertEquals("MODEL_SYNC", m2.type)
        assertEquals(id, m2.raw!!.modelSync.id, "Entry ID should match on update")
        val d2 = JSONObject(String(m2.raw!!.modelSync.data.toByteArray()))
        assertEquals(10, d2.getInt("count"), "Updated count should be 10")

        alice.disconnect()
        bob.disconnect()
    }

    @Test @Order(4)
    fun `8-4 - MODEL_SYNC events reflect in received messages`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("o8ea")
        val bob = registerAndConnect("o8eb")
        becomeFriends(alice, bob)

        // Send multiple model syncs
        alice.sendModelSync(bob.username!!, "story", "event_1",
            data = mapOf("content" to "First"))
        val msg1 = bob.waitForMessage()
        assertEquals("MODEL_SYNC", msg1.type)
        assertEquals("story", msg1.raw!!.modelSync.model)

        alice.sendModelSync(bob.username!!, "story", "event_2",
            data = mapOf("content" to "Second"))
        val msg2 = bob.waitForMessage()
        assertEquals("MODEL_SYNC", msg2.type)
        assertEquals("event_2", msg2.raw!!.modelSync.id)

        val d2 = JSONObject(String(msg2.raw!!.modelSync.data.toByteArray()))
        assertEquals("Second", d2.getString("content"))

        alice.disconnect()
        bob.disconnect()
    }
}
