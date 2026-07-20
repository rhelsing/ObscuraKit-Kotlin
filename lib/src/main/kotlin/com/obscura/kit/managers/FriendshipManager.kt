package com.obscura.kit.managers

import com.obscura.kit.stores.FriendStatus
import com.obscura.kit.stores.FriendSyncAction
import obscura.client.v1.Client.ClientMessage

/**
 * Befriend, acceptFriend, and syncFriendToOwnDevices.
 */
internal class FriendshipManager(
    private val ctx: ClientContext
) {
    private val session get() = ctx.session
    private val messenger get() = ctx.messenger
    private val friends get() = ctx.friends
    private val devices get() = ctx.devices
    private val messageSender get() = ctx.messageSender
    suspend fun befriend(targetUserId: String, targetUsername: String) {
        require(targetUserId != session.userId) { "Cannot befriend yourself" }
        messenger.fetchPreKeyBundles(targetUserId)

        // Self-identify inside the ENCRYPTED payload. The envelope carries only our device
        // (Phase 2), and on first contact the recipient has no device->user mapping for us yet, so
        // it needs our user_id to resolve identity. username is a display label; user_id is the
        // identity the recipient verifies against our device's identity key. (SPEC §0.5)
        val msg = ClientMessage.newBuilder()
            .setFriendRequest(obscura.client.v1.friendRequest {
                username = session.username ?: ""
                userId = session.userId ?: ""
            })
            .setTimestamp(System.currentTimeMillis()).build()

        messageSender.sendToAllDevices(targetUserId, msg)
        friends.add(targetUserId, targetUsername, FriendStatus.PENDING_SENT)

        syncFriendToOwnDevices(targetUsername, FriendSyncAction.ADD.value, FriendStatus.PENDING_SENT.value)
    }

    suspend fun acceptFriend(targetUserId: String, targetUsername: String) {
        messenger.fetchPreKeyBundles(targetUserId)

        val msg = ClientMessage.newBuilder()
            .setFriendResponse(obscura.client.v1.friendResponse {
                username = session.username ?: ""
                accepted = true
            })
            .setTimestamp(System.currentTimeMillis()).build()

        messageSender.sendToAllDevices(targetUserId, msg)
        friends.add(targetUserId, targetUsername, FriendStatus.ACCEPTED)

        syncFriendToOwnDevices(targetUsername, FriendSyncAction.ADD.value, FriendStatus.ACCEPTED.value)
    }

    suspend fun syncFriendToOwnDevices(friendUsername: String, action: String, status: String) {
        val selfTargets = devices.getSelfSyncTargets().filter { it != session.deviceId }
        if (selfTargets.isEmpty()) return

        val msg = ClientMessage.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .setFriendSync(obscura.client.v1.friendSync {
                username = friendUsername
                this.action = action
                this.status = status
                timestamp = System.currentTimeMillis()
            }).build()

        for (devId in selfTargets) {
            messenger.queueMessage(devId, msg, session.userId)
        }
        messenger.flushMessages()
    }
}
