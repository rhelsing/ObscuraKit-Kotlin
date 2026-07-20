package com.obscura.kit.stores

import com.google.protobuf.ByteString
import com.obscura.kit.crypto.SignalStore
import com.obscura.kit.crypto.UuidCodec
import com.obscura.kit.crypto.fromBase64
import com.obscura.kit.network.APIClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import obscura.client.v1.Client.*
import org.json.JSONArray
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import xyz.obscura.server.contracts.ObscuraProtocol.*
import java.util.*

data class DecryptedMessage(
    val clientMessage: ClientMessage,
    // The sending device's UUID. Phase 2: this is the address of the session that
    // decrypted (== envelope.sender_device_id, PROVEN by the successful MAC), never a
    // guess and never a user id. Always non-null on a successful decrypt.
    //
    // NOTE: there is NO user id here. The envelope no longer carries `sender_id` (SPEC §0.5):
    // the recipient resolves device -> user from ITS OWN friend graph (deviceMap), never from a
    // server assertion. The caller does that resolution.
    val senderDeviceId: String
)

/**
 * MessengerDomain - Confined coroutines. Encrypt/decrypt/queue/flush.
 * Single-threaded dispatcher protects Signal ratchet state.
 *
 * Phase 2 addressing: every Signal session is keyed on the peer's DEVICE UUID, not on a
 * registrationId. A [SignalProtocolAddress] is a purely LOCAL store key that is never
 * transmitted, and its name slot is a String, so we put the device UUID there and pin the
 * deviceId slot to the constant [ADDR_DEVICE_ID]. Send and receive MUST build the identical
 * address for the same peer device — see [addressFor] — or the bidirectional session splits.
 */
class MessengerDomain internal constructor(
    private val signalStore: SignalStore,
    private val api: APIClient
) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    // deviceUuid -> { userId, registrationId }. Used ONLY to enumerate a user's devices for
    // fan-out (getDeviceIdsForUser) and to resolve a device's owning user. The registrationId
    // slot is retained for diagnostics/back-compat but is NO LONGER an addressing identifier.
    private val deviceMap = mutableMapOf<String, Pair<String, Int>>()

    // Pending submissions for batch sending
    private val queue = mutableListOf<SendMessageRequest.Submission>()

    fun mapDevice(deviceId: String, userId: String, registrationId: Int) {
        deviceMap[deviceId] = Pair(userId, registrationId)
    }

    fun deviceMap(deviceId: String): Pair<String, Int>? = deviceMap[deviceId]

    fun getDeviceIdsForUser(userId: String): List<String> {
        return deviceMap.entries.filter { it.value.first == userId }.map { it.key }
    }

    /**
     * Snapshot the devices we currently know for [userId] (learned from prekey fetches / the friend
     * graph) as [FriendDeviceInfo], so a caller can PERSIST them into the friend record. That is
     * what makes the device -> user mapping DURABLE across a restart: on the next connect,
     * rebuildDeviceMap(getAccepted()) restores deviceMap from the persisted friend.devices. Without
     * this, deviceMap is in-memory only and a friend's messages become unattributable after restart.
     */
    fun knownDevicesFor(userId: String): List<FriendDeviceInfo> =
        deviceMap.entries.filter { it.value.first == userId }.map {
            FriendDeviceInfo(
                deviceUuid = it.key,
                deviceId = it.key,
                deviceName = "",
                registrationId = it.value.second
            )
        }

    fun rebuildDeviceMap(friends: List<FriendData>) {
        for (friend in friends) {
            for (device in friend.devices) {
                deviceMap[device.deviceId] = Pair(friend.userId, device.registrationId)
            }
        }
    }

    suspend fun queueMessage(targetDeviceId: String, message: ClientMessage, userId: String? = null) =
        withContext(dispatcher) {
            // targetDeviceId IS the peer's device UUID — the address name slot (see addressFor).
            // The owning userId is needed only to fetch that device's prekey bundle on first
            // contact; it is NOT part of the session address.
            val ownerUserId = userId ?: deviceMap[targetDeviceId]?.first
                ?: throw IllegalStateException("No owning userId known for device $targetDeviceId")

            val clientMsgBytes = message.toByteArray()
            ensureSession(ownerUserId, targetDeviceId)
            val encrypted = encrypt(targetDeviceId, clientMsgBytes)

            val encMsg = EncryptedMessage.newBuilder()
                .setType(
                    if (encrypted.first == 3) EncryptedMessage.Type.TYPE_PREKEY_MESSAGE
                    else EncryptedMessage.Type.TYPE_ENCRYPTED_MESSAGE
                )
                .setContent(ByteString.copyFrom(encrypted.second))
                .build()

            val submission = SendMessageRequest.Submission.newBuilder()
                .setSubmissionId(UuidCodec.uuidToBytes(UUID.randomUUID()))
                .setDeviceId(UuidCodec.uuidToBytes(UUID.fromString(targetDeviceId)))
                .setMessage(ByteString.copyFrom(encMsg.toByteArray()))
                .build()

            queue.add(submission)
        }

    suspend fun flushMessages(): Triple<Int, Int, List<SendMessageResponse.FailedSubmission>> =
        withContext(dispatcher) {
            if (queue.isEmpty()) return@withContext Triple(0, 0, emptyList())

            val submissions = queue.toList()
            queue.clear()

            val request = SendMessageRequest.newBuilder()
                .addAllMessages(submissions)
                .build()

            val responseBytes = try {
                api.sendMessage(request.toByteArray())
            } catch (e: Exception) {
                // Network failure — re-queue for retry
                queue.addAll(submissions)
                throw e
            }

            val failedSubmissions = if (responseBytes.isNotEmpty()) {
                try {
                    SendMessageResponse.parseFrom(responseBytes).failedSubmissionsList
                } catch (e: Exception) {
                    // Can't parse response — treat as all failed, re-queue
                    queue.addAll(submissions)
                    return@withContext Triple(0, submissions.size, emptyList<SendMessageResponse.FailedSubmission>())
                }
            } else {
                emptyList()
            }

            Triple(
                submissions.size - failedSubmissions.size,
                failedSubmissions.size,
                failedSubmissions
            )
        }

    // Fetches and processes a user's prekey bundles as a side effect: populates deviceMap so
    // getDeviceIdsForUser can enumerate the user's devices for fan-out. Callers use it for that
    // side effect, not the return value.
    suspend fun fetchPreKeyBundles(userId: String): List<PreKeyBundle> = withContext(dispatcher) {
        val bundlesJson = api.fetchPreKeyBundles(userId)
        parsePreKeyBundles(bundlesJson, userId).map { it.second }
    }

    suspend fun decrypt(envelope: Envelope): DecryptedMessage = withContext(dispatcher) {
        // Phase 2 receive-side addressing. The envelope carries ONLY the SENDER'S DEVICE UUID
        // (sender_device_id, stamped by the server from the device-scoped JWT — unforgeable by
        // the sender). It no longer carries any user id. Signal sessions are pairwise
        // device-to-device and a SignalMessage carries no sender identity, so this is how we
        // select the inbound session. Missing/empty is an ERROR path — never a guess. There is no
        // candidate-registrationId loop anymore. The owning USER is resolved by the caller from
        // its own friend graph, keyed on this device UUID (SPEC §0.5).
        val senderDeviceBytes = envelope.senderDeviceId.toByteArray()
        if (senderDeviceBytes.size != 16) {
            throw IllegalStateException(
                "Envelope has no sender_device_id (${senderDeviceBytes.size} bytes); " +
                    "cannot select an inbound session"
            )
        }
        val senderDeviceUuid = UuidCodec.bytesToUuid(senderDeviceBytes).toString()

        val encMsg = EncryptedMessage.parseFrom(envelope.message.toByteArray())
        val isPreKey = encMsg.type == EncryptedMessage.Type.TYPE_PREKEY_MESSAGE
        val content = encMsg.content.toByteArray()

        val address = addressFor(senderDeviceUuid)
        val cipher = SessionCipher(signalStore, address)
        val decryptedBytes = if (isPreKey) {
            cipher.decrypt(PreKeySignalMessage(content))
        } else {
            cipher.decrypt(SignalMessage(content))
        }
        val clientMsg = ClientMessage.parseFrom(decryptedBytes)

        // authorDeviceId is the address of the session that decrypted. A valid MAC proves
        // possession of that session's chain key, which only the sender's device has — so this
        // attribution is cryptographically sound, never a wire-field to be trusted blindly.
        DecryptedMessage(clientMsg, senderDeviceUuid)
    }

    /**
     * Resolve the USER that owns [deviceUuid] from what we've learned (friend graph + prekey
     * fetches). Returns null if this device isn't mapped yet (e.g. a friend linked a new device
     * whose DeviceAnnounce hasn't landed — a race the caller treats as unattributable).
     */
    suspend fun resolveUser(deviceUuid: String): String? = withContext(dispatcher) {
        deviceMap[deviceUuid]?.first
    }

    /**
     * Verify that [claimedUserId] genuinely OWNS [senderDeviceUuid], by confirming the identity
     * key the server publishes for that device under that user matches the identity key of the
     * session that just decrypted the message (stored at [addressFor]). This is what stops a peer
     * from claiming another user's id in a FriendRequest payload: the claim is only trusted if the
     * device that produced the MAC we already verified is registered under the claimed user with
     * the same identity key. Returns true iff verified. Populates deviceMap as a side effect (the
     * fetch enumerates the claimed user's devices).
     */
    suspend fun verifyDeviceBelongsToUser(senderDeviceUuid: String, claimedUserId: String): Boolean =
        withContext(dispatcher) {
            // The identity key libsignal saved for the session that decrypted this message.
            val sessionIdentity = signalStore.getIdentity(addressFor(senderDeviceUuid))
                ?: return@withContext false
            val bundlesJson = try {
                api.fetchPreKeyBundles(claimedUserId)
            } catch (e: Exception) {
                return@withContext false
            }
            val claimedDeviceBundle = parsePreKeyBundles(bundlesJson, claimedUserId)
                .firstOrNull { it.first == senderDeviceUuid }?.second
                ?: return@withContext false
            // Byte-for-byte identity-key comparison (not object identity).
            claimedDeviceBundle.identityKey.serialize().contentEquals(sessionIdentity.serialize())
        }

    private suspend fun ensureSession(targetUserId: String, targetDeviceUuid: String) {
        val address = addressFor(targetDeviceUuid)
        if (!signalStore.containsSession(address)) {
            val bundlesJson = api.fetchPreKeyBundles(targetUserId)
            val bundles = parsePreKeyBundles(bundlesJson, targetUserId)
            // Select the bundle by DEVICE UUID. No firstOrNull() fallback: sending under an
            // arbitrary device's keys is exactly the F1 bug.
            val bundle = bundles.firstOrNull { it.first == targetDeviceUuid }?.second
                ?: throw IllegalStateException(
                    "No prekey bundle for device $targetDeviceUuid of user $targetUserId"
                )
            SessionBuilder(signalStore, address).process(bundle)
        }
    }

    private fun encrypt(targetDeviceUuid: String, plaintext: ByteArray): Pair<Int, ByteArray> {
        val cipher = SessionCipher(signalStore, addressFor(targetDeviceUuid))
        val ciphertext = cipher.encrypt(plaintext)
        return Pair(ciphertext.type, ciphertext.serialize())
    }

    // Returns (deviceUuid, PreKeyBundle) pairs and populates deviceMap as a side effect.
    private fun parsePreKeyBundles(bundlesJson: JSONArray, userId: String): List<Pair<String, PreKeyBundle>> {
        return (0 until bundlesJson.length()).map { i ->
            val b = bundlesJson.getJSONObject(i)
            val deviceId = b.getString("deviceId")
            val regId = b.getInt("registrationId")
            deviceMap[deviceId] = Pair(userId, regId)

            val spk = b.getJSONObject("signedPreKey")
            val otp = if (b.has("oneTimePreKey") && !b.isNull("oneTimePreKey")) b.getJSONObject("oneTimePreKey") else null

            deviceId to PreKeyBundle(
                regId, 1,
                otp?.getInt("keyId") ?: 0,
                otp?.let { Curve.decodePoint(it.getString("publicKey").fromBase64(), 0) },
                spk.getInt("keyId"),
                Curve.decodePoint(spk.getString("publicKey").fromBase64(), 0),
                spk.getString("signature").fromBase64(),
                IdentityKey(b.getString("identityKey").fromBase64())
            )
        }
    }

    companion object {
        // The deviceId slot of every ProtocolAddress. Constant because the device UUID in the
        // name slot already uniquely identifies the peer device; libsignal just needs SOME
        // stable int here.
        private const val ADDR_DEVICE_ID = 1

        /**
         * THE single ProtocolAddress constructor for a peer device, used by BOTH send
         * (queueMessage/ensureSession/encrypt) and receive (decrypt). name = device UUID,
         * deviceId = constant. If send and receive ever built different addresses for the same
         * device the bidirectional session would split — this function is why they cannot.
         */
        fun addressFor(deviceUuid: String) = SignalProtocolAddress(deviceUuid, ADDR_DEVICE_ID)
    }
}
