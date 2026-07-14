package scenarios

import com.google.protobuf.ByteString
import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraLogger
import com.obscura.kit.crypto.UuidCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import obscura.client.v1.Client.ClientMessage
import obscura.client.v1.Client.EncryptedMessage
import xyz.obscura.server.contracts.ObscuraProtocol.SendMessageRequest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * PLAN.md Phase 0, task 0.2 — the ack-semantics test. Proves finding F2.
 *
 * On the server an ACK is a DELETE: gateway AckBatcher -> message_service.delete_batch ->
 * `DELETE FROM messages WHERE id = ANY($1) AND device_id = $2` (message_repo.rs:155). No
 * tombstone. And the MessagePump keeps a per-connection monotonic cursor
 * (message_pump.rs:62), so an unacked message is NOT re-sent on the same connection — it comes
 * back only on a NEW connection, where a fresh pump starts its cursor at None and re-fetches
 * every row still in the table. Therefore:
 *
 *   - a message that was ACKED is deleted and never reappears;
 *   - a message that was NOT acked reappears on the next reconnect.
 *
 * Reconnect-then-observe-redelivery is thus an exact probe for "is it still on the server?".
 *
 * F2 says Kotlin acks even when decrypt throws: `ObscuraClient.startEnvelopeLoop` runs
 * `gateway.ack(...)` OUTSIDE the try/catch that wraps `messenger.decrypt` (ObscuraClient.kt:868
 * vs the catch at :862). So a message that fails to decrypt is acked anyway, the server deletes
 * it, and it is gone forever.
 *
 * The test delivers Alice a genuinely undecryptable envelope — a real Signal ciphertext with a
 * corrupted MAC, so it parses as a valid EncryptedMessage and fails specifically at the
 * cryptographic decrypt step, not earlier — confirms the decrypt failed, then reconnects Alice
 * and asserts the envelope is redelivered (i.e. still on the server).
 *
 * Expected on Kotlin: the redelivery assertion FAILS, because Kotlin acked the failure and the
 * server deleted it. That failure is the proof of F2.
 */
class AckSemanticsTests {

    /** Counts decrypt failures the kit reports, so the test can observe them without the UI. */
    private class CountingLogger : ObscuraLogger {
        val decryptFailures = AtomicInteger(0)
        override fun log(message: String) {}
        override fun decryptFailed(sourceUserId: String, reason: String) { decryptFailures.incrementAndGet() }
        override fun ackFailed(envelopeId: String, reason: String) {}
        override fun tokenRefreshFailed(attempt: Int, reason: String) {}
        override fun preKeyReplenishFailed(reason: String) {}
        override fun identityChanged(address: String) {}
        override fun sessionEstablishFailed(userId: String, reason: String) {}
        override fun signatureVerificationFailed(sourceUserId: String, messageType: String) {}
        override fun databaseError(store: String, operation: String, reason: String) {}
    }

    companion object {
        private var serverUp = false

        @BeforeAll @JvmStatic fun setup() { serverUp = checkServer() }
    }

    private fun need() = assumeTrue(serverUp)

    /**
     * Build a genuinely undecryptable submission from Bob to Alice: a real Signal ciphertext
     * for Alice's device with its MAC byte flipped. This is an authentic "cannot decrypt" case
     * — the outer EncryptedMessage proto is well-formed, so the failure lands at the Signal
     * decrypt step (bad MAC), exactly the path F2 is about.
     */
    private fun sendCorruptCiphertext(bob: ObscuraClient, aliceUserId: String, aliceDeviceId: String) {
        val regId = bob.messenger.deviceMap(aliceDeviceId)?.second
            ?: error("bob has no deviceMap entry / session for Alice's device — fixture broken")
        val addr = SignalProtocolAddress(aliceUserId, regId)
        require(bob.signalStore.containsSession(addr)) {
            "bob has no Signal session at $addr — send a warmup message first"
        }

        val plaintext = ClientMessage.newBuilder()
            .setText(obscura.client.v1.textMessage { this.text = "this will be corrupted" })
            .setTimestamp(System.currentTimeMillis())
            .build()
            .toByteArray()

        // Encrypt a real ciphertext, then flip the last byte (the MAC) so decryption fails.
        val ciphertext = SessionCipher(bob.signalStore, addr).encrypt(plaintext)
        val bytes = ciphertext.serialize().copyOf()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()

        val encMsg = EncryptedMessage.newBuilder()
            .setType(
                if (ciphertext.type == 3) EncryptedMessage.Type.TYPE_PREKEY_MESSAGE
                else EncryptedMessage.Type.TYPE_ENCRYPTED_MESSAGE
            )
            .setContent(ByteString.copyFrom(bytes))
            .build()

        val submission = SendMessageRequest.Submission.newBuilder()
            .setSubmissionId(UuidCodec.uuidToBytes(UUID.randomUUID()))
            .setDeviceId(UuidCodec.uuidToBytes(UUID.fromString(aliceDeviceId)))
            .setMessage(ByteString.copyFrom(encMsg.toByteArray()))
            .build()

        val request = SendMessageRequest.newBuilder().addMessages(submission).build()
        runBlocking { bob.api.sendMessage(request.toByteArray()) }
    }

    @Test
    fun `F2 - a decrypt failure must NOT be acked, so the server still holds it on reconnect`() = runBlocking {
        need()

        val aliceLogger = CountingLogger()

        // Alice + Bob, friends. Alice carries the counting logger so we can see decrypt failures.
        val alice = registerAndConnect("f2_alice").also { it.logger = aliceLogger }
        val bob = registerAndConnect("f2_bob")
        becomeFriends(bob, alice)
        delay(500)

        // Warmup: a normal Bob -> Alice message. Establishes the Bob->Alice Signal session and
        // proves the delivery plumbing works before we corrupt anything.
        bob.send(alice.username!!, "warmup ok")
        val warm = alice.waitForMessage(15_000)
        assertEquals("warmup ok", warm.text, "warmup message should decrypt fine")
        assertEquals(0, aliceLogger.decryptFailures.get(), "warmup must not have failed to decrypt")
        delay(500)

        // Deliver the poison envelope.
        val failsBefore = aliceLogger.decryptFailures.get()
        sendCorruptCiphertext(bob, alice.userId!!, alice.deviceId!!)

        // Wait for Alice's envelope loop to receive it and fail to decrypt.
        val deadline = System.currentTimeMillis() + 15_000
        while (aliceLogger.decryptFailures.get() == failsBefore && System.currentTimeMillis() < deadline) {
            delay(100)
        }
        val failsAfterDelivery = aliceLogger.decryptFailures.get()
        println("decrypt failures after delivering poison: $failsAfterDelivery (was $failsBefore)")
        assertTrue(
            failsAfterDelivery > failsBefore,
            "PRECONDITION: the corrupt envelope must actually reach Alice and fail to DECRYPT " +
                "(so this proves ack-on-failure, not a delivery problem)."
        )

        // Give the kit a beat to fire its ack for that envelope.
        delay(1_000)

        // Reconnect Alice. A fresh MessagePump starts its cursor at None and re-fetches every row
        // still in the messages table. If the poison envelope was NOT acked, it is still there and
        // Alice will fail to decrypt it a SECOND time. If Kotlin acked the failure (F2), the row
        // was deleted and nothing comes back.
        val failsBeforeReconnect = aliceLogger.decryptFailures.get()
        alice.disconnect()
        delay(1_000)
        alice.connect()

        val reconnectDeadline = System.currentTimeMillis() + 12_000
        while (aliceLogger.decryptFailures.get() == failsBeforeReconnect &&
            System.currentTimeMillis() < reconnectDeadline) {
            delay(200)
        }
        val redelivered = aliceLogger.decryptFailures.get() > failsBeforeReconnect
        println("after reconnect: redelivered=$redelivered " +
            "(failures ${failsBeforeReconnect} -> ${aliceLogger.decryptFailures.get()})")
        println("alice debugLog (newest last):")
        alice.debugLog.take(14).reversed().forEach { println("  $it") }

        alice.disconnect(); bob.disconnect()

        assertTrue(
            redelivered,
            "F2: the undecryptable message must STILL be on the server after a decrypt failure — " +
                "it should redeliver on reconnect and fail to decrypt again. It did not, which means " +
                "Kotlin acked (=DELETEd) a message it never decrypted. Data loss."
        )
    }
}
