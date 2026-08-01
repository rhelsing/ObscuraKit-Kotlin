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
/**
 * The human-readable content of a received message, wherever it actually lives.
 *
 * `ReceivedMessage.text` is the legacy TEXT arm's field and is empty for a MODEL_SYNC — the content
 * is inside the opaque payload, which only the APP knows how to read. Tests that used to assert
 * `msg.text` are asserting on app data, so they go through here.
 */
fun ReceivedMessage.content(): String =
    if (type == "MODEL_SYNC") {
        runCatching {
            org.json.JSONObject(String(raw!!.modelSync.data.toByteArray())).optString("content", "")
        }.getOrDefault("")
    } else text

/**
 * Wait for the next message OF A PARTICULAR KIND, skipping anything else already queued.
 *
 * `waitForMessage` takes whatever is at the head of `incomingMessages`, which was fine when
 * `sendAndVerify` consumed its own notification. It no longer does — it asserts on the inbox row
 * instead, because the row is the delivery path and the notification is droppable (SPEC §0.9
 * rule 4). So a test that sends a message and then waits for a SESSION_RESET now finds the earlier
 * MODEL_SYNC notification sitting in front of it.
 *
 * Skipping by kind is more honest than draining: the test says which message it is waiting for.
 */
suspend fun ObscuraClient.waitForType(type: String, timeoutMs: Long = 15_000): ReceivedMessage =
    withTimeout(timeoutMs) {
        while (true) {
            val msg = incomingMessages.receive()
            if (msg.type == type) return@withTimeout msg
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

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

suspend fun registerAndConnect(prefix: String, config: ObscuraConfig = ObscuraConfig(API)): ObscuraClient {
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
 * Send a message the way obscura-pix does, and assert it arrived.
 *
 * This used to call `sender.send(receiver.username, text)` — the kit resolving a friend from a
 * USERNAME and creating an ORM entry. That is gone (SPEC §0.4: the caller names the recipients), so
 * the helper now does what the app does: name the recipient by userId, carry a canonical
 * conversation id in the payload, and read the result out of the receiver's INBOX.
 *
 * Keeping the helper rather than rewriting 25 call sites is deliberate — those tests are about
 * transport, acking and identity, not about how a send is addressed.
 */
/**
 * Send without asserting delivery — for tests where the receiver is deliberately OFFLINE and the
 * point is that the SERVER queues it. [sendAndVerify] polls the receiver's inbox, which cannot
 * succeed until they reconnect.
 */
/**
 * Whether [client] has received an entry whose payload carries [content].
 *
 * Replaces `getMessages(...).any { it.content == ... }`. `MessageDomain` is populated only by the
 * legacy TEXT arm and SENT_SYNC, so a MODEL_SYNC never reaches it — received app data lives in the
 * INBOX now, and `MessageDomain` is itself on HISTORY.md's deletion list.
 */
suspend fun ObscuraClient.hasReceived(content: String, timeoutMs: Long = 10_000): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val hit = inbox.peek(200).any {
            runCatching {
                org.json.JSONObject(String(it.payload)).optString("content", "") == content
            }.getOrDefault(false)
        }
        if (hit) return true
        delay(250)
    }
    return false
}

suspend fun sendOnly(sender: ObscuraClient, receiver: ObscuraClient, text: String): String {
    val entryId = "dm_${System.currentTimeMillis()}_${(0..99999).random()}"
    sender.send(
        recipientUserIds = listOf(receiver.userId!!),
        modelKey = "directMessage",
        entryId = entryId,
        payload = org.json.JSONObject(mapOf(
            "conversationId" to listOf(sender.userId!!, receiver.userId!!).sorted().joinToString("_"),
            "content" to text,
            "senderUsername" to (sender.username ?: ""),
        )).toString().toByteArray(),
    )
    return entryId
}

suspend fun sendAndVerify(sender: ObscuraClient, receiver: ObscuraClient, text: String, timeoutMs: Long = 15_000) {
    val convId = listOf(sender.userId!!, receiver.userId!!).sorted().joinToString("_")
    val entryId = "dm_${System.currentTimeMillis()}_${(0..99999).random()}"
    sender.send(
        recipientUserIds = listOf(receiver.userId!!),
        modelKey = "directMessage",
        entryId = entryId,
        payload = org.json.JSONObject(mapOf(
            "conversationId" to convId,
            "content" to text,
            "senderUsername" to (sender.username ?: ""),
        )).toString().toByteArray(),
    )

    // Poll the INBOX rather than consuming from `incomingMessages`.
    //
    // That is not a style choice. The channel is a droppable wake-up (SPEC §0.9 rule 4) and the ROW
    // is the delivery path — so asserting on the row is what the architecture says to do. It also
    // fixes a real breakage: consuming a channel item here stole it from callers that do their own
    // `waitForMessage` afterwards, which timed out 10 integration tests.
    val deadline = System.currentTimeMillis() + timeoutMs
    var row: com.obscura.kit.stores.InboxRecord? = null
    while (System.currentTimeMillis() < deadline && row == null) {
        row = receiver.inbox.peek(200).find { it.entryId == entryId }
        if (row == null) delay(250)
    }

    assertTrue(row != null, "receiver's inbox should contain entry $entryId within ${timeoutMs}ms")
    assertEquals(sender.userId, row!!.senderUserId)
    assertEquals(text, org.json.JSONObject(String(row.payload)).getString("content"))
}
