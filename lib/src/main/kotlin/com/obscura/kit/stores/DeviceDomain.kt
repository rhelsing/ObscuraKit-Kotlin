package com.obscura.kit.stores

import com.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceIdentityData(
    val deviceId: String,
    val userId: String,
    val username: String,
    val token: String,
    val p2pPublicKey: ByteArray? = null,
    val p2pPrivateKey: ByteArray? = null,
    val recoveryPublicKey: ByteArray? = null,
    val recoveryPrivateKey: ByteArray? = null
)

data class OwnDeviceData(
    val deviceId: String,
    val deviceName: String,
    val signalIdentityKey: ByteArray? = null
)

/**
 * DeviceDomain - Confined coroutines. Device identity + own device list.
 */
class DeviceDomain internal constructor(private val db: ObscuraDatabase) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun storeIdentity(identity: DeviceIdentityData) = withContext(dispatcher) {
        db.deviceQueries.insertIdentity(
            identity.deviceId,
            identity.userId,
            identity.username,
            identity.token,
            identity.p2pPublicKey,
            identity.p2pPrivateKey,
            identity.recoveryPublicKey,
            identity.recoveryPrivateKey
        )
    }

    suspend fun getIdentity(): DeviceIdentityData? = withContext(dispatcher) {
        val row = db.deviceQueries.selectIdentity().executeAsOneOrNull() ?: return@withContext null
        DeviceIdentityData(
            deviceId = row.device_id,
            userId = row.user_id,
            username = row.username,
            token = row.token,
            p2pPublicKey = row.p2p_public_key,
            p2pPrivateKey = row.p2p_private_key,
            recoveryPublicKey = row.recovery_public_key,
            recoveryPrivateKey = row.recovery_private_key
        )
    }

    suspend fun addOwnDevice(device: OwnDeviceData) = withContext(dispatcher) {
        db.deviceQueries.insertDevice(
            device.deviceId,
            device.deviceName,
            null,
            device.signalIdentityKey,
            1, // is_own = true
            System.currentTimeMillis()
        )
    }

    suspend fun getOwnDevices(): List<OwnDeviceData> = withContext(dispatcher) {
        db.deviceQueries.selectOwnDevices().executeAsList().map { row ->
            OwnDeviceData(
                deviceId = row.device_id,
                deviceName = row.device_name,
                signalIdentityKey = row.signal_identity_key
            )
        }
    }

    suspend fun setOwnDevices(devices: List<FriendDeviceInfo>) = withContext(dispatcher) {
        db.deviceQueries.deleteAllDevices()
        for (d in devices) {
            db.deviceQueries.insertDevice(
                d.deviceId.ifBlank { d.deviceUuid }, // deviceId is the primary key
                d.deviceName,
                null, // user_id — own devices, not friend devices
                d.signalIdentityKey,
                1, // is_own
                System.currentTimeMillis()
            )
        }
    }

    suspend fun getSelfSyncTargets(): List<String> = withContext(dispatcher) {
        db.deviceQueries.selectOwnDevices().executeAsList().map { it.device_id }
    }
}
