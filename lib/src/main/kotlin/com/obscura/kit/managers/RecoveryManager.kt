package com.obscura.kit.managers

import com.obscura.kit.ObscuraConfig
import com.obscura.kit.crypto.ParsedSyncBlob
import com.obscura.kit.network.APIClient
import com.obscura.kit.stores.FriendDomain
import com.obscura.kit.stores.MessengerDomain
import org.signal.libsignal.protocol.ecc.Curve

/**
 * Recovery phrase, verify code, backup upload/download, and recovery announcement.
 */
internal class RecoveryManager(
    private val config: ObscuraConfig,
    private val session: ClientSession,
    private val api: APIClient,
    private val friends: FriendDomain,
    private val messageSender: MessageSender
) {
    private var backupEtag: String? = null

    fun generateRecoveryPhrase(): String {
        val phrase = com.obscura.kit.crypto.RecoveryKeys.generatePhrase()
        session.recoveryPhrase = phrase
        session.recoveryPublicKey = com.obscura.kit.crypto.RecoveryKeys.getPublicKey(phrase).serialize()
        return phrase
    }

    fun getRecoveryPhrase(): String? {
        val phrase = session.recoveryPhrase
        session.recoveryPhrase = null // one-time read
        return phrase
    }

    fun getVerifyCode(): String? {
        val key = session.recoveryPublicKey ?: return null
        return com.obscura.kit.crypto.VerificationCode.fromRecoveryKey(key)
    }

    suspend fun announceRecovery(recoveryPhrase: String, isFullRecovery: Boolean = true) {
        val recoveryPubKey = com.obscura.kit.crypto.RecoveryKeys.getPublicKey(recoveryPhrase)

        val announceData = com.obscura.kit.crypto.RecoveryKeys.serializeAnnounceForSigning(
            listOf(session.deviceId ?: ""), System.currentTimeMillis(), false
        )
        val signature = com.obscura.kit.crypto.RecoveryKeys.signWithPhrase(recoveryPhrase, announceData)

        val msg = obscura.v2.Client.ClientMessage.newBuilder()
            .setType(obscura.v2.Client.ClientMessage.Type.DEVICE_RECOVERY_ANNOUNCE)
            .setTimestamp(System.currentTimeMillis())
            .setDeviceRecoveryAnnounce(obscura.v2.deviceRecoveryAnnounce {
                newDevices.add(obscura.v2.deviceInfo {
                    deviceUuid = session.deviceId ?: ""
                    deviceId = session.deviceId ?: ""
                    deviceName = config.deviceName
                })
                timestamp = System.currentTimeMillis()
                this.isFullRecovery = isFullRecovery
                this.signature = com.google.protobuf.ByteString.copyFrom(signature)
                this.recoveryPublicKey = com.google.protobuf.ByteString.copyFrom(recoveryPubKey.serialize())
            }).build()

        for (friend in friends.getAccepted()) {
            messageSender.sendToAllDevices(friend.userId, msg)
        }
    }

    suspend fun uploadBackup(): String? {
        val friendList = friends.getAccepted()
        val compressed = com.obscura.kit.crypto.SyncBlob.export(friendList)

        val pubKey = session.recoveryPublicKey
        val payload = if (pubKey != null) {
            val ecPubKey = Curve.decodePoint(pubKey, 0)
            com.obscura.kit.crypto.BackupCrypto.encrypt(compressed, ecPubKey)
        } else {
            compressed
        }

        val newEtag = api.uploadBackup(payload, backupEtag)
        backupEtag = newEtag
        return newEtag
    }

    suspend fun downloadBackup(recoveryPhrase: String? = null): ParsedSyncBlob? {
        val result = api.downloadBackup(backupEtag) ?: return null
        val (data, etag) = result
        backupEtag = etag

        val decrypted = if (recoveryPhrase != null) {
            com.obscura.kit.crypto.BackupCrypto.decrypt(data, recoveryPhrase)
        } else {
            data
        }

        return com.obscura.kit.crypto.SyncBlob.parse(decrypted)
    }

    suspend fun checkBackup(): Triple<Boolean, String?, Long?> {
        return api.checkBackup()
    }
}
