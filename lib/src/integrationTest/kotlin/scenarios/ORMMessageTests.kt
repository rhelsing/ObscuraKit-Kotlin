package scenarios

import com.obscura.kit.orm.ModelConfig
import com.obscura.kit.orm.SyncStrategy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * ORM Message Tests — proves DirectMessage model works as chat transport.
 *
 * These validate that chat messages sent as MODEL_SYNC (type 30) arrive
 * correctly, show up in conversations, and survive offline/reconnect.
 * This is the interop path — iOS sends DirectMessage the same way.
 */
class ORMMessageTests {

    private val messageSchema = mapOf(
        "directMessage" to ModelConfig(
            fields = mapOf("content" to "string"),
            sync = SyncStrategy.GSET
        )
    )

    @Test
    fun `send via ORM DirectMessage arrives as MODEL_SYNC`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("omsg_a")
        val bob = registerAndConnect("omsg_b")
        becomeFriends(alice, bob)

        alice.orm.define(messageSchema)
        bob.orm.define(messageSchema)

        // send() routes through ObscuraConfig.conversationModel (set by registerAndConnect).
        alice.send(bob.username!!, "Hello via ORM!")

        val msg = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg.type, "Message should arrive as MODEL_SYNC, not TEXT")

        val sync = msg.raw!!.modelSync
        assertEquals("directMessage", sync.model)

        val data = JSONObject(sync.data.toStringUtf8())
        assertEquals("Hello via ORM!", data.getString("content"),
            "content field must match the sent text")
        // This model declares the default (friends) audience, so no routing field is required.
        // A model declaring a conversation/recipient audience would additionally get the field
        // *its own schema names* populated by send() — see ObscuraClient.send.

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `ORM message shows up in conversations`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("omsg_ca")
        val bob = registerAndConnect("omsg_cb")
        becomeFriends(alice, bob)

        alice.orm.define(messageSchema)
        bob.orm.define(messageSchema)

        alice.send(bob.username!!, "Check my conversations")
        bob.waitForMessage(15_000)
        delay(500)

        // Bob's conversations should have the message
        val msgs = bob.getMessages(alice.userId!!)
        assertTrue(msgs.any { it.content == "Check my conversations" },
            "Message should appear in Bob's conversation with Alice")

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `Bidirectional ORM messages`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("omsg_ba")
        val bob = registerAndConnect("omsg_bb")
        becomeFriends(alice, bob)

        alice.orm.define(messageSchema)
        bob.orm.define(messageSchema)

        // Alice → Bob
        alice.send(bob.username!!, "Hey Bob")
        val msg1 = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg1.type)

        // Bob → Alice
        bob.send(alice.username!!, "Hey Alice")
        val msg2 = alice.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg2.type)
        val data = JSONObject(String(msg2.raw!!.modelSync.data.toByteArray()))
        assertEquals("Hey Alice", data.getString("content"))

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `ORM message arrives after offline reconnect`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("omsg_oa")
        val bob = registerAndConnect("omsg_ob")
        becomeFriends(alice, bob)

        alice.orm.define(messageSchema)
        bob.orm.define(messageSchema)

        // Bob goes offline
        bob.disconnect()
        delay(500)

        // Alice sends while Bob is away
        alice.send(bob.username!!, "You there?")
        delay(500)

        // Bob reconnects
        bob.connect()

        val msg = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg.type)
        val data = JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
        assertEquals("You there?", data.getString("content"))

        // Should also be in conversations
        delay(500)
        val msgs = bob.getMessages(alice.userId!!)
        assertTrue(msgs.any { it.content == "You there?" })

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `Multiple ORM messages survive offline`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("omsg_ma")
        val bob = registerAndConnect("omsg_mb")
        becomeFriends(alice, bob)

        alice.orm.define(messageSchema)
        bob.orm.define(messageSchema)

        bob.disconnect()
        delay(500)

        alice.send(bob.username!!, "Message 1")
        delay(200)
        alice.send(bob.username!!, "Message 2")
        delay(200)
        alice.send(bob.username!!, "Message 3")
        delay(500)

        bob.connect()

        val received = mutableListOf<String>()
        repeat(3) {
            val msg = bob.waitForMessage(15_000)
            assertEquals("MODEL_SYNC", msg.type)
            val d = JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
            received.add(d.getString("content"))
        }

        assertTrue(received.containsAll(listOf("Message 1", "Message 2", "Message 3")))

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `send fails loud when the conversation model is not defined`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("omsg_la")
        val bob = registerAndConnect("omsg_lb")
        becomeFriends(alice, bob)

        // Deliberately do NOT define the conversation model.
        //
        // This used to silently fall back to a legacy TEXT envelope. That looked harmless but
        // was not: per SPEC §6 only MODEL_SYNC contributes to push counts, so a TEXT message
        // lands in the ignored `otherCount` and the recipient is never notified — the message
        // appears sent and arrives silently. Failing loudly is the correct behaviour.
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { alice.send(bob.username!!, "Legacy hello") }
        }
        assertTrue(
            error.message!!.contains("conversationModel"),
            "the error must name the missing config. Got: ${error.message}",
        )

        alice.disconnect()
        bob.disconnect()
    }
}
