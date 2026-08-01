package scenarios

import com.obscura.kit.ConnectionState
import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
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
 * then having Bob call `processPendingMessages()` to drain.
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
    fun `processPendingMessages drains opaque model envelopes`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("push_a")
        val bob = registerAndConnect("push_b")
        becomeFriends(alice, bob)


        // Bob goes offline — simulates app being backgrounded/killed
        bob.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, bob.connectionState.value)
        delay(300)

        // Model keys and payloads are opaque to the kit.
        repeat(2) { i ->
            alice.send(
                recipientUserIds = listOf(bob.userId!!),
                modelKey = "model-a",
                entryId = "entry-a-$i",
                payload = "opaque-a-$i".toByteArray(),
            )
        }
        alice.send(
            recipientUserIds = listOf(bob.userId!!),
            modelKey = "model-b",
            entryId = "entry-b-1",
            payload = "opaque-b".toByteArray(),
        )
        delay(500)

        // Bob's push wake: drain queued envelopes without consuming the public event channel.
        val processed = bob.processPendingMessages(timeoutMs = 10_000)
        assertEquals(3, processed, "Should have processed exactly 3 opaque envelopes")
        val queuedModels = List(3) {
            bob.incomingMessages.tryReceive().getOrNull()?.raw?.modelSync?.model
        }
        assertEquals(
            listOf("model-a", "model-a", "model-b"),
            queuedModels,
            "Push draining must not consume the app's event channel",
        )

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
        val processed = alice.processPendingMessages(timeoutMs = 10_000)
        val elapsed = System.currentTimeMillis() - start

        assertEquals(0, processed)
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
        val processed = alice.processPendingMessages(timeoutMs = 10_000)
        assertEquals(0, processed)
        assertEquals(ConnectionState.CONNECTED, alice.connectionState.value,
            "Should have connected during drain")

        alice.disconnect()
    }
}
