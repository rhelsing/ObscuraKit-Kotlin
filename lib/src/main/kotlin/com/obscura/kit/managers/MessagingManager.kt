package com.obscura.kit.managers

import com.obscura.kit.network.APIClient
import com.obscura.kit.stores.DeviceDomain
import com.obscura.kit.stores.FriendDomain
import com.obscura.kit.stores.MessengerDomain
import obscura.v2.Client.ClientMessage
import org.json.JSONObject

/**
 * Send text, attachments, model sync, raw messages. Upload/download attachments.
 */
internal class MessagingManager(
    private val session: ClientSession,
    private val api: APIClient,
    private val messenger: MessengerDomain,
    private val friends: FriendDomain,
    private val devices: DeviceDomain,
    private val messageSender: MessageSender
) {
    suspend fun send(friendUsername: String, text: String) {
        val friendData = friends.getAccepted().find { it.username == friendUsername }
            ?: throw IllegalStateException("Not friends with $friendUsername")

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.TEXT).setText(text)
            .setTimestamp(System.currentTimeMillis()).build()

        messageSender.sendToAllDevices(friendData.userId, msg)

        // Self-sync: SENT_SYNC to own devices
        val selfTargets = devices.getSelfSyncTargets().filter { it != session.deviceId }
        if (selfTargets.isNotEmpty()) {
            val msgId = java.util.UUID.randomUUID().toString()
            val sentSync = ClientMessage.newBuilder()
                .setType(ClientMessage.Type.SENT_SYNC)
                .setTimestamp(System.currentTimeMillis())
                .setSentSync(obscura.v2.sentSync {
                    conversationId = friendUsername
                    messageId = msgId
                    timestamp = System.currentTimeMillis()
                    content = com.google.protobuf.ByteString.copyFrom(text.toByteArray())
                })
                .build()
            for (devId in selfTargets) {
                messenger.queueMessage(devId, sentSync, session.userId)
            }
            messenger.flushMessages()
        }
    }

    suspend fun sendAttachment(friendUsername: String, attachmentId: String, contentKey: ByteArray, nonce: ByteArray, mimeType: String, sizeBytes: Long) {
        val friendData = friends.getAccepted().find { it.username == friendUsername }
            ?: throw IllegalStateException("Not friends with $friendUsername")

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.CONTENT_REFERENCE)
            .setTimestamp(System.currentTimeMillis())
            .setContentReference(obscura.v2.contentReference {
                this.attachmentId = attachmentId
                this.contentKey = com.google.protobuf.ByteString.copyFrom(contentKey)
                this.nonce = com.google.protobuf.ByteString.copyFrom(nonce)
                this.contentType = mimeType
                this.sizeBytes = sizeBytes
            }).build()

        messageSender.sendToAllDevices(friendData.userId, msg)
    }

    suspend fun sendEncryptedAttachment(friendUsername: String, plaintext: ByteArray, mimeType: String = "application/octet-stream") {
        val encrypted = com.obscura.kit.crypto.AttachmentCrypto.encrypt(plaintext)
        val result = api.uploadAttachment(encrypted.ciphertext)
        val attachmentId = result.getString("id")
        sendAttachment(friendUsername, attachmentId, encrypted.contentKey, encrypted.nonce, mimeType, encrypted.sizeBytes)
    }

    suspend fun sendModelSync(friendUsername: String, model: String, entryId: String, op: String = "CREATE", data: Map<String, Any?>) {
        val friendData = friends.getAccepted().find { it.username == friendUsername }
            ?: throw IllegalStateException("Not friends with $friendUsername")

        val opEnum = when (op.uppercase()) {
            "UPDATE" -> obscura.v2.Client.ModelSync.Op.UPDATE
            "DELETE" -> obscura.v2.Client.ModelSync.Op.DELETE
            else -> obscura.v2.Client.ModelSync.Op.CREATE
        }

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.MODEL_SYNC)
            .setTimestamp(System.currentTimeMillis())
            .setModelSync(obscura.v2.modelSync {
                this.model = model; this.id = entryId; this.op = opEnum
                timestamp = System.currentTimeMillis()
                this.data = com.google.protobuf.ByteString.copyFrom(JSONObject(data).toString().toByteArray())
                authorDeviceId = session.deviceId ?: ""
            }).build()

        messageSender.sendToAllDevices(friendData.userId, msg)
    }

    suspend fun sendRaw(targetUserId: String, msg: ClientMessage) = messageSender.sendToAllDevices(targetUserId, msg)

    suspend fun uploadAttachment(data: ByteArray): Pair<String, Long> {
        val result = api.uploadAttachment(data)
        return Pair(result.getString("id"), result.optLong("expiresAt", 0))
    }

    suspend fun downloadAttachment(id: String): ByteArray = api.fetchAttachment(id)

    suspend fun downloadDecryptedAttachment(id: String, contentKey: ByteArray, nonce: ByteArray, expectedHash: ByteArray? = null): ByteArray {
        val ciphertext = api.fetchAttachment(id)
        return com.obscura.kit.crypto.AttachmentCrypto.decrypt(ciphertext, contentKey, nonce, expectedHash)
    }
}
