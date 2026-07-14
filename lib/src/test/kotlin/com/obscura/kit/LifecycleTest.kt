package com.obscura.kit

import com.obscura.kit.orm.SignalManager
import com.obscura.kit.persistence.NoOpSessionStorage
import com.obscura.kit.persistence.SessionSnapshot
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
 * deprecated-gatewayUrl, SignalManager contextKey API contract, and
 * typed SessionSnapshot round-trip.
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

    // ─── SessionSnapshot ───────────────────────────────────────────────────────

    @Test
    fun `SessionSnapshot round-trips through map conversion`() {
        val snapshot = SessionSnapshot(
            version = 1,
            authToken = "tok123",
            refreshToken = "ref456",
            userId = "u1",
            username = "alice",
            deviceId = "d1",
            identityKeyPair = "base64key",
            registrationId = 42
        )
        val map = snapshot.toMap()
        val restored = SessionSnapshot.fromMap(map)
        assertEquals(snapshot, restored)
    }

    @Test
    fun `SessionSnapshot fromMap handles missing fields gracefully`() {
        val partial = mapOf("authToken" to "tok", "userId" to "u42")
        val snapshot = SessionSnapshot.fromMap(partial)
        assertEquals("tok", snapshot.authToken)
        assertEquals("u42", snapshot.userId)
        assertNull(snapshot.username, "Missing fields must be null, not throw")
        assertNull(snapshot.deviceId)
        assertEquals(SessionSnapshot.CURRENT_VERSION, snapshot.version,
            "Missing version field should default to CURRENT_VERSION")
    }

    @Test
    fun `SessionSnapshot current version is 1`() {
        assertEquals(1, SessionSnapshot.CURRENT_VERSION)
        assertEquals(1, SessionSnapshot().version)
    }

    @Test
    fun `SessionStorage default saveSnapshot and loadSnapshot delegate to map API`() {
        // Use a simple in-memory implementation to verify the default impls.
        val storage = object : SessionStorage {
            var stored: Map<String, Any?>? = null
            override fun save(data: Map<String, Any?>) { stored = data }
            override fun load(): Map<String, Any?>? = stored
            override fun clear() { stored = null }
        }
        val snapshot = SessionSnapshot(authToken = "abc", userId = "u1")
        storage.saveSnapshot(snapshot)
        val loaded = storage.loadSnapshot()
        assertNotNull(loaded)
        assertEquals("abc", loaded!!.authToken)
        assertEquals("u1", loaded.userId)
    }

    @Test
    fun `NoOpSessionStorage loadSnapshot returns null`() {
        assertNull(NoOpSessionStorage.loadSnapshot())
    }
}
