package scenarios

import com.obscura.kit.crypto.Bip39
import com.obscura.kit.crypto.RecoveryKeys
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Sync and backup tests. Server tests use full befriend lifecycle.
 * Crypto unit tests (BIP39, RecoveryKeys) kept as-is.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SyncAndBackupTests {

    companion object {
        private var serverUp = false
        private var alice: com.obscura.kit.ObscuraClient? = null
        private var bob: com.obscura.kit.ObscuraClient? = null

        @BeforeAll @JvmStatic fun setup() {
            serverUp = checkServer()

            if (serverUp) runBlocking {
                val recoveryConfig = com.obscura.kit.ObscuraConfig(API, enableRecoveryPhrase = true)
                alice = registerAndConnect("sab_alice", recoveryConfig)
                bob = registerAndConnect("sab_bob")
                becomeFriends(alice!!, bob!!)
            }
        }
    }

    private fun need() = assumeTrue(serverUp && alice != null)

    @Test @Order(1)
    fun `Backup upload and check`() = runBlocking {
        need()
        alice!!.uploadBackup()

        val (exists, _, _) = alice!!.checkBackup()
        assertTrue(exists, "Backup should exist after upload")
    }

    @Test @Order(2)
    fun `Messaging works after backup upload`() = runBlocking {
        need()
        sendAndVerify(alice!!, bob!!, "Message after backup")
        sendAndVerify(bob!!, alice!!, "Reply after backup")

        alice!!.disconnect(); bob!!.disconnect()
    }

    // --- Crypto unit tests (no server needed) ---

    @Test @Order(3)
    fun `BIP39 mnemonic generates and validates`() {
        val phrase = Bip39.generateMnemonic()
        val words = phrase.split(" ")
        assertEquals(12, words.size)
        assertTrue(Bip39.validateMnemonic(phrase))
    }

    @Test @Order(4)
    fun `Recovery keypair derives deterministically from phrase`() {
        val phrase = Bip39.generateMnemonic()
        val kp1 = RecoveryKeys.deriveKeypair(phrase)
        val kp2 = RecoveryKeys.deriveKeypair(phrase)
        assertArrayEquals(kp1.publicKey.serialize(), kp2.publicKey.serialize())
    }

    @Test @Order(5)
    fun `Recovery signature signs and verifies`() {
        val phrase = Bip39.generateMnemonic()
        val data = "test data to sign".toByteArray()
        val signature = RecoveryKeys.signWithPhrase(phrase, data)
        val publicKey = RecoveryKeys.getPublicKey(phrase)
        assertTrue(RecoveryKeys.verify(publicKey, data, signature))
    }

    @Test @Order(6)
    fun `Verify code generates 4-digit code`() {
        need()
        alice!!.generateRecoveryPhrase()
        val code = alice!!.getVerifyCode()
        assertNotNull(code)
        assertEquals(4, code!!.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test @Order(7)
    fun `Recovery phrase is one-time read`() {
        need()
        val phrase = alice!!.generateRecoveryPhrase()
        assertNotNull(phrase)
        assertEquals(phrase, alice!!.getRecoveryPhrase())
        assertNull(alice!!.getRecoveryPhrase(), "Second read should return null")
    }
}
