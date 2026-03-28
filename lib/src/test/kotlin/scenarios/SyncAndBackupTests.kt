package scenarios

import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
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

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SyncAndBackupTests {

    companion object {
        private val API = "https://obscura.barrelmaker.dev"
        private var serverUp = false
        private var client: ObscuraClient? = null

        @BeforeAll @JvmStatic fun setup() {
            serverUp = try {
                java.net.URL("$API/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close(); true
            } catch (e: Exception) { false }

            if (serverUp) runBlocking {
                client = ObscuraClient(ObscuraConfig(API))
                client!!.register("kt_sb_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
            }
        }
    }

    private fun need() = assumeTrue(serverUp && client != null)

    @Test @Order(1)
    fun `Backup upload and check`() = runBlocking {
        need()
        client!!.uploadBackup()

        // Verify backup exists on server
        val (exists, _, _) = client!!.checkBackup()
        assertTrue(exists, "Backup should exist after upload")
    }

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
        client!!.generateRecoveryPhrase()
        val code = client!!.getVerifyCode()
        assertNotNull(code)
        assertEquals(4, code!!.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test @Order(7)
    fun `Recovery phrase is one-time read`() {
        need()
        val phrase = client!!.generateRecoveryPhrase()
        assertNotNull(phrase)
        assertEquals(phrase, client!!.getRecoveryPhrase())
        assertNull(client!!.getRecoveryPhrase()) // second read returns null
    }
}
