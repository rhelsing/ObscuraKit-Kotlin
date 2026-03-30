package scenarios

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.crypto.SignalStore
import com.obscura.kit.db.ObscuraDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.state.*
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * Tier 1: Signal store unit tests — no server, in-memory DB.
 *
 * Proves the Signal Protocol integration is correctly wired:
 * identity persistence, prekey lifecycle, session storage.
 * LibSignalClient does the hard crypto — we test that we store and
 * retrieve it correctly.
 */
class SignalStoreTests {

    private lateinit var store: SignalStore

    @BeforeEach
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        val db = ObscuraDatabase(driver)
        store = SignalStore(db)
    }

    // ─── Identity ─────────────────────────────────────────────────

    @Test
    fun `generateIdentity creates and persists keypair`() {
        val (keyPair, regId) = store.generateIdentity()
        assertNotNull(keyPair)
        assertTrue(regId > 0, "Registration ID should be positive")
        assertEquals(keyPair, store.getIdentityKeyPair())
        assertEquals(regId, store.getLocalRegistrationId())
    }

    @Test
    fun `Identity survives store reload`() {
        // Use a file-backed driver so we can prove persistence across instances
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        val db = ObscuraDatabase(driver)

        val store1 = SignalStore(db)
        val (keyPair, regId) = store1.generateIdentity()

        // Second instance reads from same DB — simulates restart
        val store2 = SignalStore(db)
        assertEquals(keyPair.serialize().toList(), store2.getIdentityKeyPair().serialize().toList(),
            "Identity keypair should survive store reload")
        assertEquals(regId, store2.getLocalRegistrationId())
    }

    @Test
    fun `saveIdentity TOFU — first save returns false (not replaced)`() {
        store.generateIdentity()
        val remoteKey = IdentityKeyPair.generate()
        val address = SignalProtocolAddress("alice-user-id", 1)
        val replaced = store.saveIdentity(address, remoteKey.publicKey)
        assertFalse(replaced, "First identity save should return false (TOFU, not a replacement)")
    }

    @Test
    fun `saveIdentity — same key returns true but no change callback`() {
        store.generateIdentity()
        var callbackFired = false
        store.onIdentityChanged = { _, _, _ -> callbackFired = true }

        val remoteKey = IdentityKeyPair.generate()
        val address = SignalProtocolAddress("alice", 1)
        store.saveIdentity(address, remoteKey.publicKey)
        store.saveIdentity(address, remoteKey.publicKey)

        assertFalse(callbackFired, "Re-saving same key should NOT fire identity changed callback")
    }

    @Test
    fun `saveIdentity — different key fires onIdentityChanged`() {
        store.generateIdentity()
        var changedAddress: String? = null
        store.onIdentityChanged = { addr, _, _ -> changedAddress = addr }

        val key1 = IdentityKeyPair.generate()
        val key2 = IdentityKeyPair.generate()
        val address = SignalProtocolAddress("alice", 1)

        store.saveIdentity(address, key1.publicKey)
        store.saveIdentity(address, key2.publicKey)

        assertEquals("alice.1", changedAddress,
            "Identity change callback should fire with correct address")
    }

    @Test
    fun `isTrustedIdentity — TOFU trusts unknown address`() {
        store.generateIdentity()
        val key = IdentityKeyPair.generate()
        val address = SignalProtocolAddress("unknown-user", 1)
        assertTrue(store.isTrustedIdentity(address, key.publicKey, IdentityKeyStore.Direction.RECEIVING),
            "Unknown address should be trusted on first use (TOFU)")
    }

    @Test
    fun `isTrustedIdentity — rejects different key for known address`() {
        store.generateIdentity()
        val key1 = IdentityKeyPair.generate()
        val key2 = IdentityKeyPair.generate()
        val address = SignalProtocolAddress("alice", 1)

        store.saveIdentity(address, key1.publicKey)
        assertFalse(store.isTrustedIdentity(address, key2.publicKey, IdentityKeyStore.Direction.RECEIVING),
            "Different key for known address should NOT be trusted")
    }

    // ─── PreKeys ──────────────────────────────────────────────────

    @Test
    fun `Prekey store-load round trip`() {
        store.generateIdentity()
        val keyPair = Curve.generateKeyPair()
        val record = PreKeyRecord(1, keyPair)
        store.storePreKey(1, record)

        val loaded = store.loadPreKey(1)
        assertEquals(1, loaded.id)
        assertTrue(store.containsPreKey(1))
    }

    @Test
    fun `Prekey removal works`() {
        store.generateIdentity()
        val keyPair = Curve.generateKeyPair()
        store.storePreKey(42, PreKeyRecord(42, keyPair))
        assertTrue(store.containsPreKey(42))

        store.removePreKey(42)
        assertFalse(store.containsPreKey(42))
    }

    @Test
    fun `Missing prekey throws InvalidKeyIdException`() {
        store.generateIdentity()
        assertThrows(InvalidKeyIdException::class.java) {
            store.loadPreKey(999)
        }
    }

    @Test
    fun `Prekey count tracks additions and removals`() {
        store.generateIdentity()
        assertEquals(0L, store.getPreKeyCount())

        for (i in 1..10) {
            store.storePreKey(i, PreKeyRecord(i, Curve.generateKeyPair()))
        }
        assertEquals(10L, store.getPreKeyCount())

        store.removePreKey(1)
        store.removePreKey(2)
        assertEquals(8L, store.getPreKeyCount())
    }

    @Test
    fun `Highest prekey ID tracks correctly`() {
        store.generateIdentity()
        store.storePreKey(5, PreKeyRecord(5, Curve.generateKeyPair()))
        store.storePreKey(50, PreKeyRecord(50, Curve.generateKeyPair()))
        store.storePreKey(25, PreKeyRecord(25, Curve.generateKeyPair()))

        assertEquals(50L, store.getHighestPreKeyId())
    }

    // ─── Signed PreKeys ───────────────────────────────────────────

    @Test
    fun `Signed prekey store-load round trip`() {
        val (identity, _) = store.generateIdentity()
        val keyPair = Curve.generateKeyPair()
        val signature = Curve.calculateSignature(identity.privateKey, keyPair.publicKey.serialize())
        val record = SignedPreKeyRecord(1, System.currentTimeMillis(), keyPair, signature)
        store.storeSignedPreKey(1, record)

        val loaded = store.loadSignedPreKey(1)
        assertEquals(1, loaded.id)
        assertTrue(store.containsSignedPreKey(1))
    }

    @Test
    fun `Missing signed prekey throws InvalidKeyIdException`() {
        store.generateIdentity()
        assertThrows(InvalidKeyIdException::class.java) {
            store.loadSignedPreKey(999)
        }
    }

    // ─── Sessions ─────────────────────────────────────────────────

    @Test
    fun `Session store-load round trip`() {
        store.generateIdentity()
        val address = SignalProtocolAddress("bob", 1)
        val record = SessionRecord()
        store.storeSession(address, record)

        assertTrue(store.containsSession(address))
        val loaded = store.loadSession(address)
        assertNotNull(loaded)
    }

    @Test
    fun `Missing session returns empty SessionRecord (not exception)`() {
        store.generateIdentity()
        val address = SignalProtocolAddress("nobody", 1)
        assertFalse(store.containsSession(address))
        // Signal pattern: missing session returns empty record, not exception
        val loaded = store.loadSession(address)
        assertNotNull(loaded)
    }

    @Test
    fun `Delete session removes it`() {
        store.generateIdentity()
        val address = SignalProtocolAddress("bob", 1)
        store.storeSession(address, SessionRecord())
        assertTrue(store.containsSession(address))

        store.deleteSession(address)
        assertFalse(store.containsSession(address))
    }

    @Test
    fun `deleteAllSessions removes all sessions for a user`() {
        store.generateIdentity()
        // Bob has 3 devices
        store.storeSession(SignalProtocolAddress("bob", 1), SessionRecord())
        store.storeSession(SignalProtocolAddress("bob", 2), SessionRecord())
        store.storeSession(SignalProtocolAddress("bob", 3), SessionRecord())
        // Alice has 1
        store.storeSession(SignalProtocolAddress("alice", 1), SessionRecord())

        store.deleteAllSessions("bob")

        assertFalse(store.containsSession(SignalProtocolAddress("bob", 1)))
        assertFalse(store.containsSession(SignalProtocolAddress("bob", 2)))
        assertFalse(store.containsSession(SignalProtocolAddress("bob", 3)))
        assertTrue(store.containsSession(SignalProtocolAddress("alice", 1)),
            "Alice's session should be untouched")
    }

    @Test
    fun `getSubDeviceSessions returns non-primary devices`() {
        store.generateIdentity()
        store.storeSession(SignalProtocolAddress("bob", 1), SessionRecord())
        store.storeSession(SignalProtocolAddress("bob", 2), SessionRecord())
        store.storeSession(SignalProtocolAddress("bob", 3), SessionRecord())

        val subDevices = store.getSubDeviceSessions("bob")
        assertEquals(listOf(2, 3), subDevices.sorted(),
            "Sub-devices should exclude primary device (1)")
    }

    @Test
    fun `getAllSessionRegistrationIds returns all devices`() {
        store.generateIdentity()
        store.storeSession(SignalProtocolAddress("bob", 1), SessionRecord())
        store.storeSession(SignalProtocolAddress("bob", 7), SessionRecord())

        val all = store.getAllSessionRegistrationIds("bob")
        assertEquals(listOf(1, 7), all.sorted())
    }
}
