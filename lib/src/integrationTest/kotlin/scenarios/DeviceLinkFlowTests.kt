package scenarios

import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.AuthState
import com.obscura.kit.crypto.SyncBlob
import com.obscura.kit.stores.FriendData
import com.obscura.kit.stores.FriendStatus
import com.obscura.kit.stores.MessageData
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Device link flow: provision second device, approve via link code,
 * verify server shows both devices, messages fan out.
 *
 * Device approval is enforced — loginAndProvision puts the device in
 * PENDING_APPROVAL state until an existing device approves via link code.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DeviceLinkFlowTests {

    companion object {
        private var serverUp = false

        @BeforeAll @JvmStatic fun check() {
            serverUp = checkServer()
        }
    }

    private fun need() = assumeTrue(serverUp)

    @Test @Order(1)
    fun `loginAndProvision puts device in PENDING_APPROVAL`() = runBlocking {
        need()

        val username = uniqueName("dlf_pend")
        val device1 = ObscuraClient(ObscuraConfig(API, deviceName = "Device 1"))
        device1.register(username, TEST_PASSWORD)
        assertEquals(AuthState.AUTHENTICATED, device1.authState.value)

        val device2 = ObscuraClient(ObscuraConfig(API, deviceName = "Device 2"))
        device2.loginAndProvision(username, TEST_PASSWORD, "Device 2")
        assertEquals(AuthState.PENDING_APPROVAL, device2.authState.value,
            "New device must be PENDING_APPROVAL until existing device approves")
        assertNotNull(device2.deviceId)
        assertNotEquals(device1.deviceId, device2.deviceId)

        // Server shows both devices even before approval (device is provisioned, just not approved)
        val devices = device1.api.listDevices()
        assertEquals(2, devices.length(), "Server should show 2 devices")
    }

    @Test @Order(2)
    fun `Full link code approval flow — PENDING to AUTHENTICATED`() = runBlocking {
        need()

        val username = uniqueName("dlf_approve")
        val device1 = ObscuraClient(ObscuraConfig(API, deviceName = "Device 1"))
        device1.register(username, TEST_PASSWORD)
        device1.connect()

        // Provision + approve via helper
        val device2 = provisionAndApprove(device1, username, "Device 2")
        assertEquals(AuthState.AUTHENTICATED, device2.authState.value)

        device1.disconnect()
        device2.disconnect()
    }

    @Test @Order(3)
    fun `Approved device can receive messages from friends`() = runBlocking {
        need()

        val username = uniqueName("dlf_recv")
        val device1 = ObscuraClient(ObscuraConfig(API, deviceName = "Device 1"))
        device1.register(username, TEST_PASSWORD)
        device1.connect()

        val device2 = provisionAndApprove(device1, username, "Device 2")

        val carol = registerAndConnect("dlf_carol")
        becomeFriends(device1, carol)

        // Drain any sync messages on device2
        try { while (true) { device2.waitForMessage(2_000) } } catch (_: Exception) {}

        // Carol sends — both devices should receive
        carol.send(username, "Hello both devices")

        val msg1 = device1.waitForMessage()
        assertEquals("TEXT", msg1.type)
        assertEquals("Hello both devices", msg1.text)

        val msg2 = device2.waitForMessage()
        assertEquals("TEXT", msg2.type)
        assertEquals("Hello both devices", msg2.text)

        device1.disconnect(); device2.disconnect(); carol.disconnect()
    }

    @Test @Order(4)
    fun `SyncBlob export includes messages`() {
        val friends = listOf(FriendData(
            userId = "u1", username = "alice",
            status = FriendStatus.ACCEPTED
        ))
        val messages = mapOf("alice" to listOf(MessageData(
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
