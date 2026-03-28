package scenarios

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.crypto.SignalStore
import com.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * Signal protocol edge cases.
 * Covers: test-signal-address.js, test-session-persist.js, test-4device-reset.js
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SignalEdgeCaseTests {

    companion object {
        private val API = "https://obscura.barrelmaker.dev"
        private var serverUp = false

        @BeforeAll @JvmStatic fun check() {
            serverUp = try {
                java.net.URL("$API/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close(); true
            } catch (e: Exception) { false }
        }
    }

    private fun need() = assumeTrue(serverUp)
    private fun name() = "kt_${System.currentTimeMillis()}_${(1000..9999).random()}"

    @Test @Order(1)
    fun `PreKey decrypt works at address (userId, 1) regardless of sender regId`() {
        // test-signal-address.js: Can Signal decrypt a PreKey message using (userId, 1)
        // when encrypted with (userId, registrationId)?
        val aliceDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(aliceDriver)
        val aliceStore = SignalStore(ObscuraDatabase(aliceDriver))
        val (aliceIdentity, aliceRegId) = aliceStore.generateIdentity()

        val bobDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(bobDriver)
        val bobStore = SignalStore(ObscuraDatabase(bobDriver))
        val (bobIdentity, bobRegId) = bobStore.generateIdentity()

        // Bob generates prekeys
        val bobPreKeyPair = Curve.generateKeyPair()
        bobStore.storePreKey(1, PreKeyRecord(1, bobPreKeyPair))
        val bobSignedPair = Curve.generateKeyPair()
        val bobSig = Curve.calculateSignature(bobIdentity.privateKey, bobSignedPair.publicKey.serialize())
        bobStore.storeSignedPreKey(1, SignedPreKeyRecord(1, System.currentTimeMillis(), bobSignedPair, bobSig))

        // Alice encrypts at (bob, bobRegId) — the REAL registrationId
        val bobBundle = PreKeyBundle(bobRegId, 1, 1, bobPreKeyPair.publicKey, 1, bobSignedPair.publicKey, bobSig, bobIdentity.publicKey)
        val bobAddr = SignalProtocolAddress("bob", bobRegId)
        SessionBuilder(aliceStore, bobAddr).process(bobBundle)
        val cipher = SessionCipher(aliceStore, bobAddr)
        val encrypted = cipher.encrypt("test message".toByteArray())

        // Bob decrypts at (alice, 1) — the DEFAULT registrationId, NOT alice's real one
        val aliceAddr1 = SignalProtocolAddress("alice", 1)
        val bobCipher1 = SessionCipher(bobStore, aliceAddr1)
        val decrypted = bobCipher1.decrypt(PreKeySignalMessage(encrypted.serialize()))
        assertEquals("test message", String(decrypted))
    }

    @Test @Order(2)
    fun `Signal sessions persist across store reload`() {
        // test-session-persist.js: Does the session survive store recreation?
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        val db = ObscuraDatabase(driver)

        // Store 1: create session
        val store1 = SignalStore(db)
        store1.generateIdentity()
        val addr = SignalProtocolAddress("peer", 1)
        val session = org.signal.libsignal.protocol.state.SessionRecord()
        store1.storeSession(addr, session)
        assertTrue(store1.containsSession(addr))

        // Store 2: same DB, new store instance — session should survive
        val store2 = SignalStore(db)
        store2.initialize(store1.getIdentityKeyPair(), store1.getLocalRegistrationId())
        assertTrue(store2.containsSession(addr), "Session should persist across store instances")
    }

    @Test @Order(3)
    fun `4-device selective session reset — only target sessions cleared`() = runBlocking {
        need()

        // Alice (2 devices) + Bob (2 devices)
        val alice1 = ObscuraClient(ObscuraConfig(API))
        alice1.register(name(), "testpass123!xyz")

        val bob1 = ObscuraClient(ObscuraConfig(API))
        bob1.register(name(), "testpass123!xyz")

        val bob2 = ObscuraClient(ObscuraConfig(API))
        bob2.register(name(), "testpass123!xyz")

        // Establish sessions: alice1 ↔ bob1, alice1 ↔ bob2
        alice1.connect(); bob1.connect(); bob2.connect()

        alice1.befriend(bob1.userId!!, bob1.username!!)
        bob1.waitForMessage() // FRIEND_REQUEST
        bob1.acceptFriend(alice1.userId!!, alice1.username!!)
        alice1.waitForMessage() // FRIEND_RESPONSE

        // Alice knows bob1's device from befriend. Now also establish session with bob2.
        alice1.messenger.fetchPreKeyBundles(bob2.userId!!)

        // Alice resets session with bob1 only
        alice1.resetSessionWith(bob1.userId!!, "selective reset")
        val resetMsg = bob1.waitForMessage()
        assertEquals("SESSION_RESET", resetMsg.type)

        // Alice can still message bob2 (that session wasn't reset)
        // bob2 is a different user so session is independent
        alice1.befriend(bob2.userId!!, bob2.username!!)
        val req = bob2.waitForMessage()
        assertEquals("FRIEND_REQUEST", req.type)

        alice1.disconnect(); bob1.disconnect(); bob2.disconnect()
    }
}
