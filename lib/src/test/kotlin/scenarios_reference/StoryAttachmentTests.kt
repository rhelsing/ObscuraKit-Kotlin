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
 * Scenario 10: Story with attachment E2E.
 * Alice creates story with media via MODEL_SYNC, Bob receives and downloads attachment.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class StoryAttachmentTests {

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
                    alice!!.register("kt_sty_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")

                    bob = ObscuraTestClient(API_URL)
                    bob!!.register("kt_sty2_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")

                    alice!!.fetchPreKeyBundles(bob!!.userId!!)
                    bob!!.fetchPreKeyBundles(alice!!.userId!!)
                }
            }
        }
    }

    private fun requireServer() = assumeTrue(serverAvailable && alice != null, "Server not available")

    @Test
    @Order(1)
    fun `10-1 - Story with image created and synced via MODEL_SYNC`() = runBlocking {
        requireServer()

        // Alice uploads image
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(250) { it.toByte() }
        val uploadResult = alice!!.api.uploadAttachment(jpeg)
        val attachmentId = uploadResult.getString("id")

        bob!!.connectWebSocket()

        // Alice sends MODEL_SYNC for story with mediaRef
        val storyId = "story_${System.currentTimeMillis()}_test"
        alice!!.sendMessage(bob!!.deviceId!!, ClientMessage.Type.MODEL_SYNC, bob!!.userId!!) {
            modelSync = obscura.v2.modelSync {
                model = "story"
                id = storyId
                op = obscura.v2.Client.ModelSync.Op.CREATE
                timestamp = System.currentTimeMillis()
                data = com.google.protobuf.ByteString.copyFrom(
                    """{"content":"My vacation","mediaRef":"$attachmentId","mimeType":"image/jpeg"}""".toByteArray()
                )
                authorDeviceId = alice!!.deviceId!!
            }
        }

        // Bob receives the story sync
        val msg = bob!!.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg.type)
        assertEquals("story", msg.raw!!.modelSync.model)

        val storyData = org.json.JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
        assertEquals("My vacation", storyData.getString("content"))
        assertEquals(attachmentId, storyData.getString("mediaRef"))

        // Bob downloads the attachment
        val downloaded = bob!!.api.fetchAttachment(attachmentId)
        assertEquals(0xFF.toByte(), downloaded[0])
        assertEquals(0xD8.toByte(), downloaded[1])
        assertEquals(jpeg.size, downloaded.size)

        bob!!.disconnectWebSocket()
    }

    @Test
    @Order(2)
    fun `10-2 - Story with text only syncs via MODEL_SYNC`() = runBlocking {
        requireServer()

        bob!!.connectWebSocket()

        alice!!.sendMessage(bob!!.deviceId!!, ClientMessage.Type.MODEL_SYNC, bob!!.userId!!) {
            modelSync = obscura.v2.modelSync {
                model = "story"
                id = "story_text_${System.currentTimeMillis()}"
                op = obscura.v2.Client.ModelSync.Op.CREATE
                timestamp = System.currentTimeMillis()
                data = com.google.protobuf.ByteString.copyFrom(
                    """{"content":"Just a text story"}""".toByteArray()
                )
                authorDeviceId = alice!!.deviceId!!
            }
        }

        val msg = bob!!.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg.type)
        val data = org.json.JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
        assertEquals("Just a text story", data.getString("content"))

        bob!!.disconnectWebSocket()
    }

    @Test
    @Order(3)
    fun `10-3 - Multiple stories synced in sequence`() = runBlocking {
        requireServer()

        bob!!.connectWebSocket()

        // Send 3 stories
        for (i in 1..3) {
            alice!!.sendMessage(bob!!.deviceId!!, ClientMessage.Type.MODEL_SYNC, bob!!.userId!!) {
                modelSync = obscura.v2.modelSync {
                    model = "story"
                    id = "story_seq_${i}_${System.currentTimeMillis()}"
                    op = obscura.v2.Client.ModelSync.Op.CREATE
                    timestamp = System.currentTimeMillis()
                    data = com.google.protobuf.ByteString.copyFrom("""{"content":"Story $i"}""".toByteArray())
                    authorDeviceId = alice!!.deviceId!!
                }
            }
            Thread.sleep(100) // Small gap
        }

        // Bob receives all 3
        val received = mutableListOf<String>()
        for (i in 1..3) {
            val msg = bob!!.waitForMessage(15_000)
            assertEquals("MODEL_SYNC", msg.type)
            val data = org.json.JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
            received.add(data.getString("content"))
        }

        assertTrue(received.containsAll(listOf("Story 1", "Story 2", "Story 3")))

        bob!!.disconnectWebSocket()
    }
}
