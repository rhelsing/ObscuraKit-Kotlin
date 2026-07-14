package scenarios

import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.AuthState
import com.obscura.kit.ReceivedMessage
import com.obscura.kit.stores.FriendStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*

/** Test-only: suspend until the next inbound message (or time out). */
suspend fun ObscuraClient.waitForMessage(timeoutMs: Long = 15_000): ReceivedMessage =
    withTimeout(timeoutMs) { incomingMessages.receive() }

// Override with OBSCURA_TEST_API to point at a locally containerized
// obscura-server (`docker compose up` → :3000) instead of the live server.
val API: String = System.getenv("OBSCURA_TEST_API") ?: "https://obscura.barrelmaker.dev"
const val TEST_PASSWORD = "testpass123!xyz"

fun checkServer(): Boolean = try {
    java.net.URL("$API/openapi.yaml").openConnection().apply {
        connectTimeout = 5000; readTimeout = 5000
    }.getInputStream().close(); true
} catch (e: Exception) { false }

fun uniqueName(prefix: String): String =
    "kt_${prefix}_${System.currentTimeMillis()}_${(1000..9999).random()}"

/**
 * The ORM model `ObscuraClient.send()` routes conversations through.
 *
 * `send()` has no legacy-TEXT fallback: it is an ORM API, and requires
 * [ObscuraConfig.conversationModel] to name a defined model. Test clients therefore declare it
 * (see [registerAndConnect]) and [sendAndVerify] defines the schema on both sides before sending.
 */
const val CONV_MODEL = "directMessage"

/** Default audience (friends) — [sendAndVerify] only ever sends between two friends. */
val conversationSchema = mapOf(
    CONV_MODEL to com.obscura.kit.orm.ModelConfig(
        fields = mapOf("content" to "string"),
        sync = com.obscura.kit.orm.SyncStrategy.GSET,
    )
)

/**
 * Provision a second device and approve it from the first device.
 * Returns the second device in AUTHENTICATED state.
 */
suspend fun provisionAndApprove(existingDevice: ObscuraClient, username: String, deviceName: String = "Device 2"): ObscuraClient {
    val device2 = ObscuraClient(ObscuraConfig(API, deviceName = deviceName))
    device2.loginAndProvision(username, TEST_PASSWORD, deviceName)
    assertEquals(AuthState.PENDING_APPROVAL, device2.authState.value)

    // Device 2 connects and generates link code
    device2.connect()
    val linkCode = device2.generateLinkCode()

    // Existing device validates and approves
    existingDevice.validateAndApproveLink(linkCode)

    // Device 2 receives the approval — drain messages until state changes
    val deadline = System.currentTimeMillis() + 15_000
    while (device2.authState.value == AuthState.PENDING_APPROVAL && System.currentTimeMillis() < deadline) {
        try { device2.waitForMessage(2_000) } catch (_: Exception) {}
    }
    assertEquals(AuthState.AUTHENTICATED, device2.authState.value,
        "Device should be AUTHENTICATED after approval")

    return device2
}

suspend fun registerAndConnect(
    prefix: String,
    config: ObscuraConfig = ObscuraConfig(API, conversationModel = CONV_MODEL),
): ObscuraClient {
    val client = ObscuraClient(config)
    client.register(uniqueName(prefix), TEST_PASSWORD)
    assertEquals(AuthState.AUTHENTICATED, client.authState.value)
    assertNotNull(client.userId)
    assertNotNull(client.deviceId)
    client.connect()
    return client
}

suspend fun becomeFriends(a: ObscuraClient, b: ObscuraClient) {
    val aFriendsBefore = a.friendList.value.size
    val bFriendsBefore = b.friendList.value.size

    a.befriend(b.userId!!, b.username!!)
    delay(300)

    b.waitForMessage() // FRIEND_REQUEST
    delay(300)
    assertTrue(b.pendingRequests.value.any { it.userId == a.userId },
        "Receiver should see pending request from sender")

    b.acceptFriend(a.userId!!, a.username!!)
    a.waitForMessage() // FRIEND_RESPONSE
    delay(300)

    assertTrue(a.friendList.value.any { it.userId == b.userId && it.status == FriendStatus.ACCEPTED },
        "Sender should see receiver as ACCEPTED friend")
    assertTrue(b.friendList.value.any { it.userId == a.userId && it.status == FriendStatus.ACCEPTED },
        "Receiver should see sender as ACCEPTED friend")
    assertEquals(aFriendsBefore + 1, a.friendList.value.size)
    assertEquals(bFriendsBefore + 1, b.friendList.value.size)
}

/**
 * Deliver a message and assert it arrives, for tests about the *transport* — offline queueing,
 * reconnect, multi-device fan-out, persistence.
 *
 * Uses [ObscuraClient.sendText] (legacy TEXT) deliberately. These tests care that *a* message
 * survives the round trip, and a TEXT envelope is the cleanest probe: it produces no MODEL_SYNC
 * of its own, so a test can still assert that no ORM entry leaked. Tests about the ORM send path
 * itself use [ObscuraClient.send] directly — see `ORMMessageTests`.
 *
 * (`send()` no longer falls back to TEXT silently; [ObscuraClient.sendText] is how you ask for it.)
 */
suspend fun sendAndVerify(sender: ObscuraClient, receiver: ObscuraClient, text: String, timeoutMs: Long = 15_000) {
    sender.sendText(receiver.username!!, text)
    val msg = receiver.waitForMessage(timeoutMs)
    assertEquals("TEXT", msg.type)
    assertEquals(text, msg.text)
    assertEquals(sender.userId, msg.sourceUserId)
    delay(500)

    // Verify receiver's conversations has it
    val recvMsgs = receiver.getMessages(sender.userId!!)
    assertTrue(recvMsgs.any { it.content == text },
        "Receiver's conversations should contain '$text'")
}
