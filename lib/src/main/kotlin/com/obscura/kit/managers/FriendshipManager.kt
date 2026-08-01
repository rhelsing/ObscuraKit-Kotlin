package com.obscura.kit.managers

import com.obscura.kit.stores.FriendStatus
import obscura.client.v1.Client.ClientMessage

/**
 * Befriend and acceptFriend.
 */
internal class FriendshipManager(
    private val ctx: ClientContext
) {
    private val session get() = ctx.session
    private val messenger get() = ctx.messenger
    private val friends get() = ctx.friends
    private val messageSender get() = ctx.messageSender
    suspend fun befriend(targetUserId: String, targetUsername: String) {
        require(targetUserId != session.userId) { "Cannot befriend yourself" }
        messenger.fetchPreKeyBundles(targetUserId)

        // The FriendRequest carries only our display username — a first-contact bootstrap label
        // (SPEC §0.5). Our IDENTITY is not in the payload: the server stamps envelope.sender_id with
        // our user id, and the recipient's Signal session pins our identity key on first contact
        // (TOFU), exactly as Signal authenticates. No user_id field is needed or sent.
        val msg = ClientMessage.newBuilder()
            .setFriendRequest(obscura.client.v1.friendRequest {
                username = session.username ?: ""
            })
            .setTimestamp(System.currentTimeMillis()).build()

        messageSender.sendToAllDevices(targetUserId, msg)
        // Persist the friend's devices (learned by the prekey fetch above) so the device->user
        // mapping survives a restart: rebuildDeviceMap(getAccepted()) restores it from here.
        friends.add(targetUserId, targetUsername, FriendStatus.PENDING_SENT, messenger.knownDevicesFor(targetUserId))
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
        friends.add(targetUserId, targetUsername, FriendStatus.ACCEPTED, messenger.knownDevicesFor(targetUserId))
    }

    // `syncFriendToOwnDevices` was here and is deleted along with the whole FRIEND_SYNC arm — see
    // the note at the deleted `handleFriendSync` in ObscuraClient.kt. In short: `FriendSync` has no
    // `user_id` field, so the receiver could only key the record on `sourceUserId`, which its own
    // guard proves is the RECEIVER's own id — every call here wrote a Friend row on the other
    // device naming the user as their own friend.
    //
    // Consequence: a second device no longer learns about friends added after it was linked.
    // DEVICE_LINK_APPROVAL still ships the whole friends export at link time.
}
