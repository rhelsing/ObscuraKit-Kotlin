package com.obscura.kit.managers

import com.obscura.kit.stores.DeviceDomain
import com.obscura.kit.stores.FriendDomain
import com.obscura.kit.stores.FriendStatus
import com.obscura.kit.stores.FriendSyncAction
import com.obscura.kit.stores.MessengerDomain
import obscura.v2.Client.ClientMessage

/**
 * Befriend, acceptFriend, and syncFriendToOwnDevices.
 */
internal class FriendshipManager(
    private val session: ClientSession,
    private val messenger: MessengerDomain,
    private val friends: FriendDomain,
    private val devices: DeviceDomain,
    private val messageSender: MessageSender
) {
    suspend fun befriend(targetUserId: String, targetUsername: String) {
        messenger.fetchPreKeyBundles(targetUserId)

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.FRIEND_REQUEST)
            .setUsername(session.username ?: "").setTimestamp(System.currentTimeMillis()).build()

        messageSender.sendToAllDevices(targetUserId, msg)
        friends.add(targetUserId, targetUsername, FriendStatus.PENDING_SENT)

        syncFriendToOwnDevices(targetUsername, FriendSyncAction.ADD.value, FriendStatus.PENDING_SENT.value)
    }

    suspend fun acceptFriend(targetUserId: String, targetUsername: String) {
        messenger.fetchPreKeyBundles(targetUserId)

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.FRIEND_RESPONSE)
            .setUsername(session.username ?: "").setAccepted(true)
            .setTimestamp(System.currentTimeMillis()).build()

        messageSender.sendToAllDevices(targetUserId, msg)
        friends.add(targetUserId, targetUsername, FriendStatus.ACCEPTED)

        syncFriendToOwnDevices(targetUsername, FriendSyncAction.ADD.value, FriendStatus.ACCEPTED.value)
    }

    suspend fun syncFriendToOwnDevices(friendUsername: String, action: String, status: String) {
        val selfTargets = devices.getSelfSyncTargets().filter { it != session.deviceId }
        if (selfTargets.isEmpty()) return

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.FRIEND_SYNC)
            .setTimestamp(System.currentTimeMillis())
            .setFriendSync(obscura.v2.friendSync {
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
