package scenarios

import com.obscura.kit.AuthState
import com.obscura.kit.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Edge cases: attachment size limits, verify code stability, profile ORM sync.
 * Full lifecycle with state verification after every mutation.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EdgeCaseTests {

    @Test @Order(1)
    fun `EC-1 - Small attachment upload and download`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("eca")
        assertEquals(AuthState.AUTHENTICATED, alice.authState.value)
        assertEquals(ConnectionState.CONNECTED, alice.connectionState.value)

        val small = ByteArray(100) { it.toByte() }
        val (id, expiresAt) = alice.uploadAttachment(small)
        assertTrue(id.isNotEmpty(), "Attachment ID should be non-empty")
        assertTrue(expiresAt > 0, "Expiration should be positive")

        val downloaded = alice.downloadAttachment(id)
        assertArrayEquals(small, downloaded, "Downloaded content must match uploaded")

        alice.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, alice.connectionState.value)
    }

    @Test @Order(2)
    fun `EC-2 - Medium attachment upload, download, and size verification`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("ecm")
        assertEquals(AuthState.AUTHENTICATED, alice.authState.value)

        val medium = ByteArray(500 * 1024) { (it % 256).toByte() } // 500KB
        val (id, _) = alice.uploadAttachment(medium)
        assertTrue(id.isNotEmpty(), "Attachment ID should be non-empty")

        val downloaded = alice.downloadAttachment(id)
        assertEquals(medium.size, downloaded.size, "Downloaded size should match 500KB")
        assertArrayEquals(medium, downloaded, "Downloaded content must match uploaded")

        alice.disconnect()
    }

    @Test @Order(3)
    fun `EC-3 - Verify code is stable for same recovery phrase`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("ecv")
        assertEquals(AuthState.AUTHENTICATED, alice.authState.value)

        alice.generateRecoveryPhrase()
        val code1 = alice.getVerifyCode()
        val code2 = alice.getVerifyCode()
        assertNotNull(code1, "Verify code should not be null")
        assertNotNull(code2, "Verify code should not be null on second call")
        assertEquals(code1, code2, "Verify code should be deterministic for same recovery phrase")

        alice.disconnect()
    }

    @Test @Order(4)
    fun `EC-4 - Profile data syncs via MODEL_SYNC with full lifecycle`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("ecpa")
        val bob = registerAndConnect("ecpb")
        assertEquals(AuthState.AUTHENTICATED, alice.authState.value)
        assertEquals(AuthState.AUTHENTICATED, bob.authState.value)

        becomeFriends(alice, bob)

        // Alice sends profile MODEL_SYNC
        alice.sendModelSync(bob.username!!, "profile", "profile_${alice.userId}",
            data = mapOf("displayName" to "Alice Display", "avatarUrl" to "att-avatar-123"))

        // Bob receives and verifies
        val msg = bob.waitForMessage()
        assertEquals("MODEL_SYNC", msg.type, "Message type should be MODEL_SYNC")
        assertEquals(alice.userId, msg.sourceUserId, "Source should be alice")
        assertEquals("profile", msg.raw!!.modelSync.model, "Model should be 'profile'")

        val data = JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
        assertEquals("Alice Display", data.getString("displayName"), "Display name should match")
        assertEquals("att-avatar-123", data.getString("avatarUrl"), "Avatar URL should match")

        alice.disconnect()
        bob.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, alice.connectionState.value)
        assertEquals(ConnectionState.DISCONNECTED, bob.connectionState.value)
    }

    @Test @Order(5)
    fun `EC-5 - Attachment sent to friend with size check`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("ecsa")
        val bob = registerAndConnect("ecsb")
        becomeFriends(alice, bob)

        // Upload varying sizes and send to friend
        val payload = ByteArray(1024) { (it % 256).toByte() }
        val (attId, _) = alice.uploadAttachment(payload)
        assertTrue(attId.isNotEmpty())

        alice.sendAttachment(bob.username!!, attId, ByteArray(32), ByteArray(12), "application/octet-stream", payload.size.toLong())

        val msg = bob.waitForMessage()
        assertEquals("CONTENT_REFERENCE", msg.type)
        assertEquals(alice.userId, msg.sourceUserId)

        val ref = msg.raw!!.contentReference
        assertEquals(payload.size.toLong(), ref.sizeBytes, "Size in reference should match uploaded size")

        val downloaded = bob.downloadAttachment(ref.attachmentId)
        assertEquals(payload.size, downloaded.size, "Downloaded size should match")
        assertArrayEquals(payload, downloaded, "Content should match exactly")

        alice.disconnect()
        bob.disconnect()
    }
}
