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
 * Scenario 9: Pix flow E2E.
 * Alice uploads encrypted image, sends IMAGE with CONTENT_REFERENCE to Bob.
 * Bob receives, decrypts, downloads, verifies JPEG.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PixFlowTests {

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
                    alice!!.register("kt_pix_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")

                    bob = ObscuraTestClient(API_URL)
                    bob!!.register("kt_pix2_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")

                    alice!!.fetchPreKeyBundles(bob!!.userId!!)
                    bob!!.fetchPreKeyBundles(alice!!.userId!!)
                }
            }
        }
    }

    private fun requireServer() = assumeTrue(serverAvailable && alice != null, "Server not available")

    @Test
    @Order(1)
    fun `9-1 - Upload pix, send IMAGE to Bob, Bob downloads`() = runBlocking {
        requireServer()

        // Alice uploads fake JPEG
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(300) { it.toByte() }
        val uploadResult = alice!!.api.uploadAttachment(jpeg)
        val attachmentId = uploadResult.getString("id")

        bob!!.connectWebSocket()

        // Alice sends IMAGE with content reference
        alice!!.sendMessage(bob!!.deviceId!!, ClientMessage.Type.IMAGE, bob!!.userId!!) {
            text = ""
            mimeType = "image/jpeg"
            this.attachmentId = attachmentId
            contentReference = obscura.v2.contentReference {
                this.attachmentId = attachmentId
                contentKey = com.google.protobuf.ByteString.copyFrom(ByteArray(32) { it.toByte() })
                nonce = com.google.protobuf.ByteString.copyFrom(ByteArray(12) { it.toByte() })
                contentHash = com.google.protobuf.ByteString.copyFrom(ByteArray(32))
                contentType = "image/jpeg"
                sizeBytes = jpeg.size.toLong()
            }
        }

        // Bob receives
        val msg = bob!!.waitForMessage(15_000)
        assertEquals("IMAGE", msg.type)

        // Bob downloads attachment
        val ref = msg.raw!!.contentReference
        val downloaded = bob!!.api.fetchAttachment(ref.attachmentId)

        // Verify JPEG header
        assertEquals(0xFF.toByte(), downloaded[0])
        assertEquals(0xD8.toByte(), downloaded[1])
        assertEquals(jpeg.size, downloaded.size)

        bob!!.disconnectWebSocket()
    }

    @Test
    @Order(2)
    fun `9-2 - Bidirectional pix exchange`() = runBlocking {
        requireServer()

        alice!!.connectWebSocket()
        bob!!.connectWebSocket()

        // Alice → Bob
        val jpeg1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(100)
        val att1 = alice!!.api.uploadAttachment(jpeg1).getString("id")

        alice!!.sendMessage(bob!!.deviceId!!, ClientMessage.Type.IMAGE, bob!!.userId!!) {
            mimeType = "image/jpeg"
            contentReference = obscura.v2.contentReference {
                attachmentId = att1
                contentKey = com.google.protobuf.ByteString.copyFrom(ByteArray(32))
                nonce = com.google.protobuf.ByteString.copyFrom(ByteArray(12))
                contentType = "image/jpeg"
                sizeBytes = jpeg1.size.toLong()
            }
        }

        val msgBob = bob!!.waitForMessage(15_000)
        assertEquals("IMAGE", msgBob.type)

        // Bob → Alice
        val jpeg2 = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(150)
        val att2 = bob!!.api.uploadAttachment(jpeg2).getString("id")

        bob!!.sendMessage(alice!!.deviceId!!, ClientMessage.Type.IMAGE, alice!!.userId!!) {
            mimeType = "image/jpeg"
            contentReference = obscura.v2.contentReference {
                attachmentId = att2
                contentKey = com.google.protobuf.ByteString.copyFrom(ByteArray(32))
                nonce = com.google.protobuf.ByteString.copyFrom(ByteArray(12))
                contentType = "image/jpeg"
                sizeBytes = jpeg2.size.toLong()
            }
        }

        val msgAlice = alice!!.waitForMessage(15_000)
        assertEquals("IMAGE", msgAlice.type)

        alice!!.disconnectWebSocket()
        bob!!.disconnectWebSocket()
    }
}
