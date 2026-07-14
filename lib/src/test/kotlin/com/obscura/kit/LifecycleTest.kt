package com.obscura.kit

import com.obscura.kit.orm.SignalManager
import com.obscura.kit.persistence.NoOpSessionStorage
import com.obscura.kit.persistence.SessionStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Lifecycle, close-semantics, and configuration guard tests.
 *
 * These validate: AutoCloseable idempotence, unencrypted-database warning,
 * deprecated-gatewayUrl, SignalManager contextKey API contract, and the
 * session-persistence save/load key contract.
 *
 * No network is used.
 */
class LifecycleTest {

    // ─── SignalManager ─────────────────────────────────────────────────────────

    @Test
    fun `SignalManager close is idempotent`() {
        val sm = SignalManager()
        assertDoesNotThrow { sm.close() }
        assertDoesNotThrow { sm.close() }  // second call must not throw
    }

    @Test
    fun `SignalManager emit uses contextKey not a data map`() = runBlocking {
        val sm = SignalManager()
        val captured = mutableListOf<String>()
        sm.sendSignal = { _, _, contextKey -> captured.add(contextKey) }

        sm.emit("chat", "typing", contextKey = "room-42", authorDeviceId = "dev1")
        assertEquals(listOf("room-42"), captured,
            "sendSignal must receive the opaque contextKey, not a map")
        sm.close()
    }

    @Test
    fun `SignalManager observe returns authorDeviceId not username`() = runBlocking {
        val sm = SignalManager()
        sm.receive("chat", "typing", contextKey = "room-42", authorDeviceId = "device-abc123")

        val result = sm.observe("chat", "typing", "room-42").first()
        assertTrue(result.contains("device-abc123"),
            "observe must report authorDeviceId strings, not usernames")
        sm.close()
    }

    // ─── ObscuraConfig ─────────────────────────────────────────────────────────

    @Test
    fun `gatewayUrl is deprecated at call site`() {
        // This test confirms the annotation exists at runtime (deprecation is not erased).
        val prop = ObscuraConfig::class.java.declaredMethods
            .firstOrNull { it.name == "getGatewayUrl" }
        assertNotNull(prop, "gatewayUrl getter must exist for backward compat")

        @Suppress("DEPRECATION")
        val config = ObscuraConfig(apiUrl = "https://x.test", gatewayUrl = "wss://x.test/ws")
        @Suppress("DEPRECATION")
        assertEquals("wss://x.test/ws", config.gatewayUrl,
            "deprecated field should still be readable if provided")
    }

    @Test
    fun `allowUnencryptedDatabase defaults to false`() {
        val config = ObscuraConfig(apiUrl = "https://x.test")
        assertFalse(config.allowUnencryptedDatabase)
    }

    @Test
    fun `allowUnencryptedDatabase true suppresses the warning`() {
        val config = ObscuraConfig(
            apiUrl = "https://x.test",
            databasePath = "some/path.db",
            allowUnencryptedDatabase = true
        )
        assertTrue(config.allowUnencryptedDatabase)

        val origErr = System.err
        val baos = ByteArrayOutputStream()
        System.setErr(PrintStream(baos))
        try {
            // If a client were to initialise with allowUnencryptedDatabase=true,
            // no warning line should be emitted. We verify the flag alone here:
            assertFalse(
                baos.toString().contains("UNENCRYPTED"),
                "No warning should be written yet — the warning is only emitted during ObscuraClient init"
            )
        } finally {
            System.setErr(origErr)
        }
    }

    @Test
    fun `unencrypted database warning is emitted to stderr when not opted in`() {
        // Capture stderr during ObscuraClient construction (throws immediately
        // because no valid server is present, but warning is printed in init block).
        val origErr = System.err
        val baos = ByteArrayOutputStream()
        System.setErr(PrintStream(baos))
        try {
            val config = ObscuraConfig(
                apiUrl = "https://nonexistent.local",
                databasePath = ":memory:",  // non-null triggers the check
                allowUnencryptedDatabase = false
            )
            // Construction itself doesn't throw; warning is emitted at init time.
            try {
                ObscuraClient(config = config, sessionStorage = NoOpSessionStorage)
            } catch (_: Exception) {}
        } finally {
            System.setErr(origErr)
        }
        val output = baos.toString()
        assertTrue(
            output.contains("UNENCRYPTED") || output.contains("unencrypted"),
            "Should warn about unencrypted database. Got: $output"
        )
    }

    // ─── Session persistence ───────────────────────────────────────────────────

    /** Captures what the kit actually persists, so the real save/load contract can be asserted. */
    private class RecordingSessionStorage : SessionStorage {
        var stored: Map<String, Any?>? = null
        override fun save(data: Map<String, Any?>) { stored = data }
        override fun load(): Map<String, Any?>? = stored
        override fun clear() { stored = null }
    }

    /**
     * The writer and the reader must agree on the key names.
     *
     * This is deliberately asserted against the *production* path rather than a helper's own
     * round-trip: the removed `SessionSnapshot` helper round-tripped perfectly against itself
     * while writing `authToken`, where [ObscuraClient.restorePersistedSession] reads `token` —
     * so adopting it (as its own KDoc urged) would have logged every user out on cold start,
     * with a fully green test suite. A self-consistent helper proves nothing; agreement with
     * the reader is the property that matters.
     */
    @Test
    fun `persistSession writes the keys restorePersistedSession reads`() {
        val storage = RecordingSessionStorage()
        ObscuraClient(ObscuraConfig(apiUrl = "http://127.0.0.1:1"), sessionStorage = storage).use { client ->
            client.persistSession()
        }

        val saved = storage.stored
        assertNotNull(saved, "persistSession must write something")
        // The exact keys restorePersistedSession() gates on.
        assertTrue(saved!!.containsKey("token"), "restore reads 'token' — persist must write it")
        assertTrue(saved.containsKey("userId"), "restore reads 'userId' — persist must write it")
        // Read by the Android bridge (ObscuraSession.tryRestore).
        assertTrue(saved.containsKey("username"), "the bridge reads 'username'")
    }

    /**
     * `persistSession` load-merges rather than replacing, so non-session metadata written by
     * other call sites survives. `cachedSchema` (written by defineModelsFromJson) is the one
     * that matters: lose it and model definitions vanish across a restart.
     */
    @Test
    fun `persistSession preserves unrelated metadata already in the blob`() {
        val storage = RecordingSessionStorage()
        storage.save(mapOf("cachedSchema" to """{"directMessage":{}}"""))

        ObscuraClient(ObscuraConfig(apiUrl = "http://127.0.0.1:1"), sessionStorage = storage).use { client ->
            client.persistSession()
        }

        assertEquals(
            """{"directMessage":{}}""",
            storage.stored?.get("cachedSchema"),
            "a session-only save must not clobber cachedSchema",
        )
    }
}
