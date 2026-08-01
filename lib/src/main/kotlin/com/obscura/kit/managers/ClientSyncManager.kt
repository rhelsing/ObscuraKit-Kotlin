package com.obscura.kit.managers

import com.obscura.kit.stores.MessageData
import obscura.client.v1.Client.ClientMessage
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/**
 * Push history, reset sessions, process sync blobs.
 *
 * `requestSync` was here. It shipped a SYNC_REQUEST to every one of the user's other devices — a
 * Signal encrypt and an HTTP round trip each — and BOTH kits classify that arm UNIMPLEMENTED and
 * drop it on arrival. It was a no-op with a cost.
 */
internal class ClientSyncManager(
    private val ctx: ClientContext
) {
    private val session get() = ctx.session
    private val signalStore get() = ctx.signalStore
    private val messenger get() = ctx.messenger
    private val friends get() = ctx.friends
    private val devices get() = ctx.devices
    private val messages get() = ctx.messages
    private val messageSender get() = ctx.messageSender
    suspend fun pushHistoryToDevice(targetDeviceId: String) {
        val friendList = friends.getAccepted()
        val compressed = com.obscura.kit.crypto.SyncBlob.export(friendList)

        val msg = ClientMessage.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .setSyncBlob(obscura.client.v1.syncBlob {
                compressedData = com.google.protobuf.ByteString.copyFrom(compressed)
            }).build()

        messenger.queueMessage(targetDeviceId, msg, session.userId)
        messenger.flushMessages()
    }

    suspend fun processSyncBlob(msg: ClientMessage) {
        val compressed = msg.syncBlob.compressedData.toByteArray()
        val parsed = com.obscura.kit.crypto.SyncBlob.parse(compressed) ?: return

        if (parsed.friends.isNotEmpty()) {
            val friendsJson = JSONArray(parsed.friends.map { JSONObject(it) }).toString()
            friends.importAll(friendsJson)
        }

        for (m in parsed.messages) {
            val convId = m["conversationId"] as? String ?: continue
            messages.add(convId, MessageData(
                id = m["messageId"] as? String ?: UUID.randomUUID().toString(),
                conversationId = convId,
                authorDeviceId = m["authorDeviceId"] as? String ?: "synced",
                content = m["content"] as? String ?: "",
                timestamp = (m["timestamp"] as? Long) ?: System.currentTimeMillis(),
                type = m["type"] as? String ?: "text"
            ))
        }
    }

    suspend fun resetSessionWith(targetUserId: String, reason: String = "manual") {
        // Sessions are keyed on the DEVICE UUID (Phase 2), so clear every session with each of
        // the user's devices.
        clearSessionsWithUser(targetUserId)

        val msg = ClientMessage.newBuilder()
            .setSessionReset(obscura.client.v1.sessionReset { this.reason = reason })
            .setTimestamp(System.currentTimeMillis()).build()

        messageSender.sendToAllDevices(targetUserId, msg)

        // Delete the session that was just built to send the reset message.
        // This forces the next send to use a fresh PreKey exchange,
        // which the receiver can handle after they also cleared their session.
        clearSessionsWithUser(targetUserId)
    }

    private fun clearSessionsWithUser(targetUserId: String) {
        messenger.getDeviceIdsForUser(targetUserId).forEach { signalStore.deleteAllSessions(it) }
    }

    suspend fun resetAllSessions(reason: String = "manual") {
        val allFriends = friends.getAccepted()
        for (friend in allFriends) {
            try { resetSessionWith(friend.userId, reason) } catch (e: Exception) {
                // Best-effort: one friend's session reset must not abort resetting the rest.
            }
        }
    }
}
