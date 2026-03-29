package scenarios

import com.obscura.kit.AuthState
import com.obscura.kit.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Scenario 6: Attachments E2E.
 * Full lifecycle: register, befriend via becomeFriends(), upload, send CONTENT_REFERENCE, download, verify.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AttachmentTests {

    @Test @Order(1)
    fun `6-1 - Upload and download attachment content matches`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("a6")
        assertEquals(AuthState.AUTHENTICATED, alice.authState.value)
        assertEquals(ConnectionState.CONNECTED, alice.connectionState.value)

        // Upload attachment
        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(200)
        val (attId, expiresAt) = alice.uploadAttachment(payload)
        assertTrue(attId.isNotEmpty(), "Attachment ID should be non-empty")
        assertTrue(expiresAt > 0, "Expiration should be positive")

        // Download and verify exact content match
        val downloaded = alice.downloadAttachment(attId)
        assertArrayEquals(payload, downloaded, "Downloaded content must match uploaded content exactly")

        alice.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, alice.connectionState.value)
    }

    @Test @Order(2)
    fun `6-2 - Send CONTENT_REFERENCE to friend, friend receives and downloads`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("a6cr")
        val bob = registerAndConnect("b6cr")
        becomeFriends(alice, bob)

        // Alice uploads attachment
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(150)
        val (attId, _) = alice.uploadAttachment(jpeg)
        assertTrue(attId.isNotEmpty())

        // Alice sends CONTENT_REFERENCE to bob
        val contentKey = ByteArray(32)
        val nonce = ByteArray(12)
        alice.sendAttachment(bob.username!!, attId, contentKey, nonce, "image/jpeg", jpeg.size.toLong())

        // Bob receives the CONTENT_REFERENCE message
        val msg = bob.waitForMessage()
        assertEquals("CONTENT_REFERENCE", msg.type, "Message type should be CONTENT_REFERENCE")
        assertEquals(alice.userId, msg.sourceUserId, "Source should be alice")

        // Verify the attachment ID in the received message
        val ref = msg.raw!!.contentReference
        assertEquals(attId, ref.attachmentId, "Attachment ID in message should match uploaded ID")

        // Bob downloads and verifies size
        val downloaded = bob.downloadAttachment(ref.attachmentId)
        assertEquals(jpeg.size, downloaded.size, "Downloaded size should match original")
        assertArrayEquals(jpeg, downloaded, "Downloaded content should match original")

        alice.disconnect()
        bob.disconnect()
    }

    @Test @Order(3)
    fun `6-3 - Attachment events reflected in received messages`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("a6ev")
        val bob = registerAndConnect("b6ev")
        becomeFriends(alice, bob)

        // Upload and send
        val data = ByteArray(100) { it.toByte() }
        val (attId, _) = alice.uploadAttachment(data)
        alice.sendAttachment(bob.username!!, attId, ByteArray(32), ByteArray(12), "application/octet-stream", data.size.toLong())

        // Bob receives
        val msg = bob.waitForMessage()
        assertEquals("CONTENT_REFERENCE", msg.type)
        assertEquals(alice.userId, msg.sourceUserId)

        // Verify the content reference fields
        val ref = msg.raw!!.contentReference
        assertEquals(attId, ref.attachmentId)
        assertEquals("application/octet-stream", ref.contentType)
        assertEquals(data.size.toLong(), ref.sizeBytes)

        // Bob can download and verify
        val downloaded = bob.downloadAttachment(attId)
        assertArrayEquals(data, downloaded)

        alice.disconnect()
        bob.disconnect()
    }
}
