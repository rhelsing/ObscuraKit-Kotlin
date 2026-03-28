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
 * Scenario 6: Attachments E2E.
 * Upload, download, send CONTENT_REFERENCE encrypted to friend.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AttachmentTests {

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
                alice!!.register("kt_a6_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
                bob = ObscuraClient(ObscuraConfig(API))
                bob!!.register("kt_a6b_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")

                // Befriend so we can send
                alice!!.connect(); bob!!.connect()
                alice!!.befriend(bob!!.userId!!, bob!!.username!!)
                bob!!.waitForMessage() // FRIEND_REQUEST
                bob!!.acceptFriend(alice!!.userId!!, alice!!.username!!)
                alice!!.waitForMessage() // FRIEND_RESPONSE
            }
        }
    }

    private fun need() = assumeTrue(serverUp && alice != null)

    @Test @Order(1)
    fun `6-1 - Upload and download match`() = runBlocking {
        need()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(200)
        val (attId, _) = alice!!.uploadAttachment(jpeg)
        val downloaded = alice!!.downloadAttachment(attId)
        assertArrayEquals(jpeg, downloaded)
    }

    @Test @Order(2)
    fun `6-2 - Send CONTENT_REFERENCE to friend, friend downloads`() = runBlocking {
        need()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(150)
        val (attId, _) = alice!!.uploadAttachment(jpeg)

        alice!!.sendAttachment(bob!!.username!!, attId, ByteArray(32), ByteArray(12), "image/jpeg", jpeg.size.toLong())

        val msg = bob!!.waitForMessage()
        assertEquals("CONTENT_REFERENCE", msg.type)
        val ref = msg.raw!!.contentReference
        assertEquals(attId, ref.attachmentId)

        val downloaded = bob!!.downloadAttachment(ref.attachmentId)
        assertEquals(jpeg.size, downloaded.size)
    }
}
