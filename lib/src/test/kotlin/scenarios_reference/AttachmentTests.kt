package scenarios_reference

import com.obscura.kit.ObscuraTestClient
import kotlinx.coroutines.runBlocking
import obscura.v2.Client.ClientMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Scenario 6: Attachment E2E.
 * Upload blob, send CONTENT_REFERENCE encrypted to friend, friend downloads.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AttachmentTests {

    companion object {
        private val API_URL = System.getenv("OBSCURA_API_URL") ?: "https://obscura.barrelmaker.dev"
        private var serverAvailable = false
        private var alice: ObscuraTestClient? = null
        private var bob: ObscuraTestClient? = null

        @BeforeAll
        @JvmStatic
        fun setup() {
            serverAvailable = try {
                java.net.URL("$API_URL/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close()
                true
            } catch (e: Exception) { false }

            if (serverAvailable) {
                kotlinx.coroutines.runBlocking {
                    alice = ObscuraTestClient(API_URL)
                    alice!!.register("kt_att_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")

                    bob = ObscuraTestClient(API_URL)
                    bob!!.register("kt_att2_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")

                    // Establish sessions
                    alice!!.fetchPreKeyBundles(bob!!.userId!!)
                    bob!!.fetchPreKeyBundles(alice!!.userId!!)

                    alice!!.storeFriend(bob!!.username!!, bob!!.userId!!, listOf(
                        ObscuraTestClient.DeviceRef(bob!!.deviceId!!, bob!!.registrationId)
                    ))
                }
            }
        }
    }

    private fun requireServer() = assumeTrue(serverAvailable && alice != null, "Server not available")

    @Test
    @Order(1)
    fun `6-1 - Upload attachment returns ID and expiresAt`() = runBlocking {
        requireServer()

        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(100) { it.toByte() }

        val result = alice!!.api.uploadAttachment(fakeJpeg)
        assertTrue(result.has("id"))
        assertTrue(result.has("expiresAt"))
        assertTrue(result.getString("id").isNotEmpty())
    }

    @Test
    @Order(2)
    fun `6-2 - Download matches upload`() = runBlocking {
        requireServer()

        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(200) { it.toByte() }

        val uploadResult = alice!!.api.uploadAttachment(fakeJpeg)
        val attachmentId = uploadResult.getString("id")

        val downloaded = alice!!.api.fetchAttachment(attachmentId)
        assertEquals(0xFF.toByte(), downloaded[0])
        assertEquals(0xD8.toByte(), downloaded[1])
        assertArrayEquals(fakeJpeg, downloaded)
    }

    @Test
    @Order(3)
    fun `6-3 - Send CONTENT_REFERENCE encrypted to friend, friend downloads`() = runBlocking {
        requireServer()

        // Alice uploads an attachment
        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(150) { it.toByte() }
        val uploadResult = alice!!.api.uploadAttachment(payload)
        val attachmentId = uploadResult.getString("id")

        bob!!.connectWebSocket()

        // Alice sends CONTENT_REFERENCE to Bob (encrypted)
        alice!!.sendMessage(bob!!.deviceId!!, ClientMessage.Type.CONTENT_REFERENCE, bob!!.userId!!) {
            contentReference = obscura.v2.contentReference {
                this.attachmentId = attachmentId
                contentKey = com.google.protobuf.ByteString.copyFrom(ByteArray(32) { it.toByte() })
                nonce = com.google.protobuf.ByteString.copyFrom(ByteArray(12) { it.toByte() })
                contentHash = com.google.protobuf.ByteString.copyFrom(ByteArray(32))
                contentType = "image/jpeg"
                sizeBytes = payload.size.toLong()
            }
        }

        // Bob receives and decrypts
        val msg = bob!!.waitForMessage(15_000)
        assertEquals("CONTENT_REFERENCE", msg.type)

        // Bob extracts attachment ID from the message and downloads
        val ref = msg.raw!!.contentReference
        assertEquals(attachmentId, ref.attachmentId)

        val downloaded = bob!!.api.fetchAttachment(ref.attachmentId)
        assertEquals(0xFF.toByte(), downloaded[0])
        assertEquals(0xD8.toByte(), downloaded[1])
        assertEquals(payload.size, downloaded.size)

        bob!!.disconnectWebSocket()
    }
}
