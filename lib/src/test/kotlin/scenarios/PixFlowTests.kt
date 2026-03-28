package scenarios

import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Scenario 9: Pix flow E2E.
 * Upload image, send as attachment to friend, friend downloads.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PixFlowTests {

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
                alice!!.register("kt_p9_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
                bob = ObscuraClient(ObscuraConfig(API))
                bob!!.register("kt_p9b_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
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
    fun `9-1 - Upload pix, send to friend, friend downloads`() = runBlocking {
        need()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(300)
        val (attId, _) = alice!!.uploadAttachment(jpeg)

        alice!!.sendAttachment(bob!!.username!!, attId, ByteArray(32), ByteArray(12), "image/jpeg", jpeg.size.toLong())

        val msg = bob!!.waitForMessage()
        assertEquals("CONTENT_REFERENCE", msg.type)

        val downloaded = bob!!.downloadAttachment(msg.raw!!.contentReference.attachmentId)
        assertEquals(0xFF.toByte(), downloaded[0])
        assertEquals(0xD8.toByte(), downloaded[1])
        assertEquals(jpeg.size, downloaded.size)
    }

    @Test @Order(2)
    fun `9-2 - Bidirectional pix exchange`() = runBlocking {
        need()
        val j1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(100)
        val (a1, _) = alice!!.uploadAttachment(j1)
        alice!!.sendAttachment(bob!!.username!!, a1, ByteArray(32), ByteArray(12), "image/jpeg", j1.size.toLong())
        val m1 = bob!!.waitForMessage()
        assertEquals("CONTENT_REFERENCE", m1.type)

        val j2 = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(150)
        val (a2, _) = bob!!.uploadAttachment(j2)
        bob!!.sendAttachment(alice!!.username!!, a2, ByteArray(32), ByteArray(12), "image/jpeg", j2.size.toLong())
        val m2 = alice!!.waitForMessage()
        assertEquals("CONTENT_REFERENCE", m2.type)
    }
}
