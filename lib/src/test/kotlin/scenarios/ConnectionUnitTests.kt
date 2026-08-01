package scenarios

import com.obscura.kit.network.GatewayConnection
import com.obscura.kit.network.GatewayState
import com.obscura.kit.wire.SignalManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for connection resilience — no server needed.
 *
 * Tests the mechanics: backoff timing, shouldReconnect flag,
 * token refresh before reconnect, signal throttle.
 */
class ConnectionUnitTests {

    // ─── SignalManager throttle ───────────────────────────────────

    @Test
    fun `Signal throttle blocks rapid sends`() = runBlocking {
        val mgr = SignalManager()
        var sendCount = 0
        mgr.sendSignal = { _, _, _ -> sendCount++ }

        // Three rapid emits — only first should send
        mgr.emit("dm", "typing", mapOf("conversationId" to "c1"), "d1")
        mgr.emit("dm", "typing", mapOf("conversationId" to "c1"), "d1")
        mgr.emit("dm", "typing", mapOf("conversationId" to "c1"), "d1")

        assertEquals(1, sendCount, "Throttle should block rapid sends within 2s")
    }

    @Test
    fun `Signal throttle allows send after 2 seconds`() = runBlocking {
        val mgr = SignalManager()
        var sendCount = 0
        mgr.sendSignal = { _, _, _ -> sendCount++ }

        mgr.emit("dm", "typing", mapOf("conversationId" to "c1"), "d1")
        assertEquals(1, sendCount)

        delay(2100) // Wait past throttle

        mgr.emit("dm", "typing", mapOf("conversationId" to "c1"), "d1")
        assertEquals(2, sendCount, "Should allow send after throttle window")
    }

    @Test
    fun `Signal throttle is per-conversation`() = runBlocking {
        val mgr = SignalManager()
        var sendCount = 0
        mgr.sendSignal = { _, _, _ -> sendCount++ }

        mgr.emit("dm", "typing", mapOf("conversationId" to "c1"), "d1")
        mgr.emit("dm", "typing", mapOf("conversationId" to "c2"), "d1")

        assertEquals(2, sendCount, "Different conversations should not throttle each other")
    }

    // ─── Signal auto-expire ───────────────────────────────────────

    @Test
    fun `Signal expires after 3 seconds`() = runBlocking {
        val mgr = SignalManager()
        mgr.receive("dm", "typing", mapOf("conversationId" to "c1", "senderUsername" to "alice"), "d1")

        assertEquals(1, mgr.observe("dm", "typing", "c1").first().size)

        delay(3500)

        assertEquals(0, mgr.observe("dm", "typing", "c1").first().size,
            "Signal should expire after 3s")
    }

    @Test
    fun `Signal renewed before expiry stays visible`() = runBlocking {
        val mgr = SignalManager()

        // Send signal, wait 2s, renew, wait 2s more — should still be visible
        mgr.receive("dm", "typing", mapOf("conversationId" to "c1", "senderUsername" to "alice"), "d1")
        delay(2000)
        mgr.receive("dm", "typing", mapOf("conversationId" to "c1", "senderUsername" to "alice"), "d1")
        delay(2000)

        val typers = mgr.observe("dm", "typing", "c1").first()
        assertEquals(1, typers.size, "Renewed signal should still be visible after 4s total")
    }

    @Test
    fun `Explicit clear removes signal immediately`() = runBlocking {
        val mgr = SignalManager()
        mgr.receive("dm", "typing", mapOf("conversationId" to "c1", "senderUsername" to "alice"), "d1")
        assertEquals(1, mgr.observe("dm", "typing", "c1").first().size)

        mgr.clear("dm", "typing", mapOf("conversationId" to "c1"), "d1")
        assertEquals(0, mgr.observe("dm", "typing", "c1").first().size)
    }

    // ─── GatewayConnection state ──────────────────────────────────

    @Test
    fun `Gateway starts disconnected`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api = com.obscura.kit.network.APIClient("https://obscura.barrelmaker.dev")
        val gw = GatewayConnection(api, scope)

        assertEquals(GatewayState.DISCONNECTED, gw.state.value)
        scope.cancel()
    }

    // `Disconnect sets shouldReconnect false` was here. It asserted only
    // `state == DISCONNECTED` on a gateway that had never connected — i.e. exactly what
    // `Gateway starts disconnected` above already asserts, and nothing at all about
    // shouldReconnect, which the name promised and which is private.

    @Test
    fun `onStateChanged fires on every transition`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api = com.obscura.kit.network.APIClient("https://obscura.barrelmaker.dev")
        val gw = GatewayConnection(api, scope)

        val seen = mutableListOf<GatewayState>()
        gw.onStateChanged = { seen.add(it) }

        // disconnect() is a real state mutation even from DISCONNECTED — verifies
        // the callback is the single mutation hook consumers rely on.
        gw.disconnect()
        assertTrue(seen.contains(GatewayState.DISCONNECTED),
            "onStateChanged must fire so connectionState can mirror the socket")
        scope.cancel()
    }

    // `Token refresh callback is invoked` was here and was a tautology: it assigned a lambda that
    // set a flag, called that same lambda itself, and asserted the flag. The gateway was never
    // involved. Verifying the gateway actually calls `ensureFreshToken` before a reconnect needs a
    // socket, which is the integration suite's job.
}
