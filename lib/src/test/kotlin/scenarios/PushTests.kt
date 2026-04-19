package scenarios

import com.obscura.kit.ConnectionState
import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.orm.ModelConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Push notification integration tests — against live server.
 *
 * These cover the kit's contract with the bridge layer: `registerPushToken(token)` and
 * `processPendingMessages(timeoutMs)`. No FCM/APNS involvement — we simulate the
 * "silent push wakes the app" scenario by disconnecting Bob, sending from Alice,
 * then having Bob call `processPendingMessages()` to drain and classify.
 *
 * Cross-platform contract mirror: these tests match iOS `PushTests.swift` 1:1.
 */
class PushTests {

    @Test
    fun `registerPushToken succeeds with device-scoped JWT`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("push_reg")
        // Server accepts any string as a token — we're testing idempotent upsert
        alice.registerPushToken("test-fcm-token-abc123")

        // Idempotent — call again with a different token
        alice.registerPushToken("test-fcm-token-xyz789")
        // No exception = server returned 200 both times

        alice.disconnect()
    }

    @Test
    fun `processPendingMessages categorizes pix and message envelopes`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("push_a")
        val bob = registerAndConnect("push_b")
        becomeFriends(alice, bob)

        // Both must define the same ORM schema so sync decoding works
        val schema = mapOf(
            "pix" to ModelConfig(
                fields = mapOf(
                    "recipientUsername" to "string",
                    "senderUsername" to "string",
                    "mediaRef" to "string"
                ),
                sync = "lww"
            ),
            "directMessage" to ModelConfig(
                fields = mapOf(
                    "conversationId" to "string",
                    "content" to "string",
                    "senderUsername" to "string"
                ),
                sync = "gset"
            )
        )
        alice.orm.define(schema)
        bob.orm.define(schema)

        // Bob goes offline — simulates app being backgrounded/killed
        bob.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, bob.connectionState.value)
        delay(300)

        // Alice sends 2 pix + 1 direct message to Bob while he's offline
        val pix = alice.orm.model("pix")
        pix.create(mapOf(
            "recipientUsername" to bob.username!!,
            "senderUsername" to alice.username!!,
            "mediaRef" to "fake-ref-1"
        ))
        pix.create(mapOf(
            "recipientUsername" to bob.username!!,
            "senderUsername" to alice.username!!,
            "mediaRef" to "fake-ref-2"
        ))
        val dm = alice.orm.model("directMessage")
        dm.create(mapOf(
            "conversationId" to "conv1",
            "content" to "hey there",
            "senderUsername" to alice.username!!
        ))
        delay(500)

        // Bob's push wake: drain queued envelopes and classify
        val counts = bob.processPendingMessages(timeoutMs = 10_000)

        assertEquals(2, counts.pixCount, "Should have drained exactly 2 pix envelopes")
        assertEquals(1, counts.messageCount, "Should have drained exactly 1 directMessage envelope")
        assertEquals(0, counts.otherCount, "No non-ORM envelopes expected in this scenario")

        // Kit must NOT have disconnected — OS will freeze the app when done
        assertEquals(ConnectionState.CONNECTED, bob.connectionState.value,
            "processPendingMessages must leave the connection open")

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `processPendingMessages returns fast when queue is empty`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("push_empty")
        // No messages queued — idle detection should kick in before timeout
        val start = System.currentTimeMillis()
        val counts = alice.processPendingMessages(timeoutMs = 10_000)
        val elapsed = System.currentTimeMillis() - start

        assertEquals(0, counts.pixCount)
        assertEquals(0, counts.messageCount)
        assertEquals(0, counts.otherCount)
        assertTrue(elapsed < 2_000,
            "Should return within 2s via idle detection, not full 10s timeout (took ${elapsed}ms)")

        alice.disconnect()
    }

    @Test
    fun `processPendingMessages connects if not connected`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("push_cold")
        alice.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, alice.connectionState.value)

        // Should reconnect as part of drain
        val counts = alice.processPendingMessages(timeoutMs = 10_000)
        assertEquals(0, counts.pixCount)
        assertEquals(ConnectionState.CONNECTED, alice.connectionState.value,
            "Should have connected during drain")

        alice.disconnect()
    }
}
