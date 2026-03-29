package com.obscura.kit.managers

import com.obscura.kit.crypto.SignalStore
import com.obscura.kit.crypto.toBase64
import com.obscura.kit.managers.SignalKeyUtils.toApiJson
import com.obscura.kit.network.APIClient
import com.obscura.kit.network.UploadDeviceKeysRequest
import com.obscura.kit.stores.DeviceDomain
import com.obscura.kit.stores.FriendDomain
import com.obscura.kit.stores.MessageDomain
import com.obscura.kit.stores.MessengerDomain
import obscura.v2.Client.ClientMessage

/**
 * Device announce, revocation, approve link, takeover.
 */
internal class DeviceManager(
    private val session: ClientSession,
    private val api: APIClient,
    private val signalStore: SignalStore,
    private val messenger: MessengerDomain,
    private val friends: FriendDomain,
    private val devices: DeviceDomain,
    private val messages: MessageDomain,
    private val messageSender: MessageSender,
    private val clientSyncManager: () -> ClientSyncManager,
    private val announceDevicesCallback: suspend () -> Unit
) {
    suspend fun announceDevices() {
        val ownDevices = devices.getOwnDevices()
        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.DEVICE_ANNOUNCE)
            .setTimestamp(System.currentTimeMillis())
            .setDeviceAnnounce(obscura.v2.deviceAnnounce {
                for (d in ownDevices) {
                    this.devices.add(obscura.v2.deviceInfo {
                        deviceUuid = d.deviceId; deviceId = d.deviceId; deviceName = d.deviceName
                    })
                }
                timestamp = System.currentTimeMillis()
                isRevocation = false
            }).build()

        for (friend in friends.getAccepted()) {
            messageSender.sendToAllDevices(friend.userId, msg)
        }
    }

    suspend fun announceDeviceRevocation(friendUsername: String, remainingDeviceIds: List<String>) {
        val friendData = friends.getAccepted().find { it.username == friendUsername }
            ?: throw IllegalStateException("Not friends with $friendUsername")

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.DEVICE_ANNOUNCE)
            .setTimestamp(System.currentTimeMillis())
            .setDeviceAnnounce(obscura.v2.deviceAnnounce {
                for (devId in remainingDeviceIds) {
                    devices.add(obscura.v2.deviceInfo {
                        deviceUuid = devId; deviceId = devId; deviceName = "Device"
                    })
                }
                timestamp = System.currentTimeMillis()
                isRevocation = true
                signature = com.google.protobuf.ByteString.copyFrom(ByteArray(64))
            }).build()

        messageSender.sendToAllDevices(friendData.userId, msg)
    }

    suspend fun revokeDevice(recoveryPhrase: String, targetDeviceId: String) {
        api.deleteDevice(targetDeviceId)
        messages.deleteByAuthorDevice(targetDeviceId)
        signalStore.deleteAllSessions(targetDeviceId)

        val remainingDeviceIds = devices.getOwnDevices()
            .map { it.deviceId }
            .filter { it != targetDeviceId }

        val announceData = com.obscura.kit.crypto.RecoveryKeys.serializeAnnounceForSigning(
            remainingDeviceIds, System.currentTimeMillis(), true
        )
        val signature = com.obscura.kit.crypto.RecoveryKeys.signWithPhrase(recoveryPhrase, announceData)
        val recoveryPubKey = com.obscura.kit.crypto.RecoveryKeys.getPublicKey(recoveryPhrase)

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.DEVICE_ANNOUNCE)
            .setTimestamp(System.currentTimeMillis())
            .setDeviceAnnounce(obscura.v2.deviceAnnounce {
                for (devId in remainingDeviceIds) {
                    this.devices.add(obscura.v2.deviceInfo {
                        deviceUuid = devId; deviceId = devId; deviceName = "Device"
                    })
                }
                timestamp = System.currentTimeMillis()
                isRevocation = true
                this.signature = com.google.protobuf.ByteString.copyFrom(signature)
                this.recoveryPublicKey = com.google.protobuf.ByteString.copyFrom(recoveryPubKey.serialize())
            }).build()

        for (friend in friends.getAccepted()) {
            messageSender.sendToAllDevices(friend.userId, msg)
        }
    }

    suspend fun approveLink(newDeviceId: String, challengeResponse: ByteArray) {
        val identity = devices.getIdentity()
        val ownDeviceList = devices.getOwnDevices()
        val friendsExportStr = friends.exportAll()

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.DEVICE_LINK_APPROVAL)
            .setTimestamp(System.currentTimeMillis())
            .setDeviceLinkApproval(obscura.v2.deviceLinkApproval {
                if (identity?.p2pPublicKey != null) {
                    p2PPublicKey = com.google.protobuf.ByteString.copyFrom(identity.p2pPublicKey)
                }
                if (identity?.p2pPrivateKey != null) {
                    p2PPrivateKey = com.google.protobuf.ByteString.copyFrom(identity.p2pPrivateKey)
                }
                if (identity?.recoveryPublicKey != null) {
                    recoveryPublicKey = com.google.protobuf.ByteString.copyFrom(identity.recoveryPublicKey)
                }
                this.challengeResponse = com.google.protobuf.ByteString.copyFrom(challengeResponse)

                for (d in ownDeviceList) {
                    this.ownDevices.add(obscura.v2.deviceInfo {
                        deviceUuid = d.deviceId; deviceId = d.deviceId; deviceName = d.deviceName
                    })
                }

                friendsExport = com.google.protobuf.ByteString.copyFrom(friendsExportStr.toByteArray())
            }).build()

        messenger.queueMessage(newDeviceId, msg, session.userId)
        messenger.flushMessages()

        clientSyncManager().pushHistoryToDevice(newDeviceId)
        announceDevicesCallback()
    }

    suspend fun takeoverDevice() {
        val (identityKeyPair, regId) = signalStore.generateIdentity()
        session.registrationId = regId

        val signedPreKey = SignalKeyUtils.generateSignedPreKey(signalStore, identityKeyPair, 1)
        val oneTimePreKeys = SignalKeyUtils.generateOneTimePreKeys(signalStore, 1, 100)

        api.uploadDeviceKeys(UploadDeviceKeysRequest(
            identityKey = identityKeyPair.publicKey.serialize().toBase64(),
            registrationId = regId,
            signedPreKey = signedPreKey.toApiJson(),
            oneTimePreKeys = oneTimePreKeys.toApiJson()
        ))
        messenger.mapDevice(
            requireNotNull(session.deviceId) { "deviceId not set - call register/login first" },
            requireNotNull(session.userId) { "userId not set - call register/login first" },
            regId
        )
    }
}
