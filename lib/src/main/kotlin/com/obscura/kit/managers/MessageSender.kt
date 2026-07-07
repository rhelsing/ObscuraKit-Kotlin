package com.obscura.kit.managers

import com.obscura.kit.stores.MessengerDomain
import obscura.client.v1.Client.ClientMessage

/**
 * Shared utility for sending a ClientMessage to all of a user's devices.
 * Used by every manager that needs to send encrypted messages.
 */
internal class MessageSender(
    private val messenger: MessengerDomain,
    private val authManager: AuthManager
) {
    suspend fun sendToAllDevices(targetUserId: String, msg: ClientMessage) {
        authManager.ensureFreshToken()
        var deviceIds = messenger.getDeviceIdsForUser(targetUserId)
        if (deviceIds.isEmpty()) {
            messenger.fetchPreKeyBundles(targetUserId)
            deviceIds = messenger.getDeviceIdsForUser(targetUserId)
        }
        if (deviceIds.isEmpty()) {
            throw com.obscura.kit.ObscuraError.NoDevices(targetUserId)
        }
        for (devId in deviceIds) {
            messenger.queueMessage(devId, msg, targetUserId)
        }
        val (sent, failed, _) = messenger.flushMessages()
        if (sent == 0 && failed > 0) {
            throw com.obscura.kit.ObscuraError.SendFailed("All $failed message submissions failed for $targetUserId")
        }
    }
}
