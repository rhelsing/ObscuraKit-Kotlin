package scenarios

import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.crypto.SyncBlob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Device link flow: DEVICE_LINK_APPROVAL delivery + SYNC_BLOB round-trip.
 * Covers: test-same-user-msg.js, iOS DeviceLinkFlowTests
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DeviceLinkFlowTests {

    companion object {
        private val API = "https://obscura.barrelmaker.dev"
        private var serverUp = false

        @BeforeAll @JvmStatic fun check() {
            serverUp = try {
                java.net.URL("$API/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close(); true
            } catch (e: Exception) { false }
        }
    }

    private fun need() = assumeTrue(serverUp)
    private fun name() = "kt_${System.currentTimeMillis()}_${(1000..9999).random()}"

    @Test @Order(1)
    fun `DEVICE_LINK_APPROVAL delivered between devices`() = runBlocking {
        need()

        val username = name()
        val device1 = ObscuraClient(ObscuraConfig(API))
        device1.register(username, "testpass123!xyz")

        // device2 is same user, new device
        val device2 = ObscuraClient(ObscuraConfig(API))
        device2.loginAndProvision(username, "testpass123!xyz", "Device 2")

        device2.connect()

        // device1 sends approval to device2
        val challenge = ByteArray(32) { it.toByte() }
        device1.messenger.fetchPreKeyBundles(device1.userId!!) // own user's bundles (both devices)
        device1.approveLink(device2.deviceId!!, challenge)

        // device2 should receive DEVICE_LINK_APPROVAL (first) or SYNC_BLOB
        val msg = device2.waitForMessage()
        assertTrue(
            msg.type == "DEVICE_LINK_APPROVAL" || msg.type == "SYNC_BLOB",
            "Should receive DEVICE_LINK_APPROVAL or SYNC_BLOB, got ${msg.type}"
        )

        device2.disconnect()
    }

    @Test @Order(2)
    fun `SYNC_BLOB export and import round-trip through server`() = runBlocking {
        need()

        val username = name()
        val device1 = ObscuraClient(ObscuraConfig(API))
        device1.register(username, "testpass123!xyz")

        // device1 befriends a third user to have friend state
        val carol = ObscuraClient(ObscuraConfig(API))
        carol.register(name(), "testpass123!xyz")
        device1.connect(); carol.connect()
        device1.befriend(carol.userId!!, carol.username!!)
        carol.waitForMessage()
        carol.acceptFriend(device1.userId!!, device1.username!!)
        device1.waitForMessage()
        device1.disconnect(); carol.disconnect()

        // device2 is same user
        val device2 = ObscuraClient(ObscuraConfig(API))
        device2.loginAndProvision(username, "testpass123!xyz", "Device 2")
        device2.connect()

        // device1 pushes history to device2
        device1.messenger.fetchPreKeyBundles(device1.userId!!)
        device1.pushHistoryToDevice(device2.deviceId!!)

        val msg = device2.waitForMessage()
        assertEquals("SYNC_BLOB", msg.type)

        val syncData = msg.raw!!.syncBlob.compressedData.toByteArray()
        val parsed = SyncBlob.parse(syncData)
        assertNotNull(parsed)
        assertTrue(parsed!!.friends.isNotEmpty(), "Sync blob should contain friends")

        device2.disconnect()
    }

    @Test @Order(3)
    fun `SyncBlob export includes messages`() {
        // Local test — verify serialization includes messages
        val friends = listOf(com.obscura.kit.stores.FriendData(
            userId = "u1", username = "alice",
            status = com.obscura.kit.stores.FriendStatus.ACCEPTED
        ))
        val messages = mapOf("alice" to listOf(com.obscura.kit.stores.MessageData(
            id = "m1", conversationId = "alice", authorDeviceId = "dev1",
            content = "hello", timestamp = 1000, type = "text"
        )))

        val compressed = SyncBlob.export(friends, messages)
        val parsed = SyncBlob.parse(compressed)

        assertNotNull(parsed)
        assertEquals(1, parsed!!.friends.size)
        assertEquals(1, parsed.messages.size)
        assertEquals("hello", parsed.messages[0]["content"])
    }
}
