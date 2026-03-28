package scenarios

import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.network.HttpException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Edge cases: attachment limits, verify codes, profile ORM sync.
 * Covers: test-attachment-size.js, test-verify-persistence.js, test-profile-pictures.js
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EdgeCaseTests {

    companion object {
        private val API = "https://obscura.barrelmaker.dev"
        private var serverUp = false
        private var alice: ObscuraClient? = null
        private var bob: ObscuraClient? = null

        @BeforeAll @JvmStatic fun setup() {
            serverUp = try {
                java.net.URL("$API/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close(); true
            } catch (e: Exception) { false }

            if (serverUp) runBlocking {
                alice = ObscuraClient(ObscuraConfig(API))
                alice!!.register("kt_ec_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
                bob = ObscuraClient(ObscuraConfig(API))
                bob!!.register("kt_ec2_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")

                alice!!.connect(); bob!!.connect()
                alice!!.befriend(bob!!.userId!!, bob!!.username!!)
                bob!!.waitForMessage()
                bob!!.acceptFriend(alice!!.userId!!, alice!!.username!!)
                alice!!.waitForMessage()
            }
        }
    }

    private fun need() = assumeTrue(serverUp && alice != null)

    @Test @Order(1)
    fun `Small attachment upload succeeds`() = runBlocking {
        need()
        val small = ByteArray(100) { it.toByte() }
        val (id, _) = alice!!.uploadAttachment(small)
        assertTrue(id.isNotEmpty())
    }

    @Test @Order(2)
    fun `Medium attachment upload succeeds`() = runBlocking {
        need()
        val medium = ByteArray(500 * 1024) { (it % 256).toByte() } // 500KB
        val (id, _) = alice!!.uploadAttachment(medium)
        assertTrue(id.isNotEmpty())

        val downloaded = alice!!.downloadAttachment(id)
        assertEquals(medium.size, downloaded.size)
    }

    @Test @Order(3)
    fun `Verify code is stable for same recovery phrase`() {
        need()
        alice!!.generateRecoveryPhrase()
        val code1 = alice!!.getVerifyCode()
        val code2 = alice!!.getVerifyCode()
        assertNotNull(code1)
        assertEquals(code1, code2, "Verify code should be deterministic")
    }

    @Test @Order(4)
    fun `Profile data syncs via MODEL_SYNC`() = runBlocking {
        need()
        alice!!.sendModelSync(bob!!.username!!, "profile", "profile_${alice!!.userId}",
            data = mapOf("displayName" to "Alice Display", "avatarUrl" to "att-avatar-123"))

        val msg = bob!!.waitForMessage()
        assertEquals("MODEL_SYNC", msg.type)
        assertEquals("profile", msg.raw!!.modelSync.model)

        val data = org.json.JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
        assertEquals("Alice Display", data.getString("displayName"))
        assertEquals("att-avatar-123", data.getString("avatarUrl"))

        alice!!.disconnect(); bob!!.disconnect()
    }
}
