package com.obscura.kit.stores

import com.obscura.kit.newInMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DeviceDomain handles the local device identity row + the own-devices
 * list. Wrong behavior here means the wrong device gets credit for
 * messages or a user's device list reads stale. Tests use an in-memory
 * sqldelight DB (the same driver that ObscuraClient itself uses in
 * test mode), no mocks.
 */
class DeviceDomainTest {

    private fun newDomain() = DeviceDomain(newInMemoryDatabase())

    private fun sampleIdentity(deviceId: String = "device-1") = DeviceIdentityData(
        deviceId = deviceId,
        userId = "user-1",
        username = "alice",
        token = "tok-abc",
        p2pPublicKey = byteArrayOf(0x01, 0x02),
        p2pPrivateKey = byteArrayOf(0x03, 0x04),
        recoveryPublicKey = byteArrayOf(0x05),
        recoveryPrivateKey = byteArrayOf(0x06)
    )

    @Test
    fun `getIdentity returns null when none stored`() = runTest {
        assertNull(newDomain().getIdentity())
    }

    @Test
    fun `store then get identity round-trips all fields`() = runTest {
        val d = newDomain()
        d.storeIdentity(sampleIdentity())

        val loaded = d.getIdentity()!!
        assertEquals("device-1", loaded.deviceId)
        assertEquals("user-1", loaded.userId)
        assertEquals("alice", loaded.username)
        assertEquals("tok-abc", loaded.token)
        assertArrayEquals(byteArrayOf(0x01, 0x02), loaded.p2pPublicKey)
        assertArrayEquals(byteArrayOf(0x03, 0x04), loaded.p2pPrivateKey)
        assertArrayEquals(byteArrayOf(0x05), loaded.recoveryPublicKey)
        assertArrayEquals(byteArrayOf(0x06), loaded.recoveryPrivateKey)
    }

    @Test
    fun `addOwnDevice then getOwnDevices returns the row`() = runTest {
        val d = newDomain()
        d.addOwnDevice(OwnDeviceData(
            deviceId = "dev-a",
            deviceName = "Pixel 8",
            signalIdentityKey = byteArrayOf(0xAB.toByte(), 0xCD.toByte())
        ))

        val devices = d.getOwnDevices()
        assertEquals(1, devices.size)
        assertEquals("dev-a", devices[0].deviceId)
        assertEquals("Pixel 8", devices[0].deviceName)
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), devices[0].signalIdentityKey)
    }

    @Test
    fun `setOwnDevices replaces existing list`() = runTest {
        val d = newDomain()
        d.addOwnDevice(OwnDeviceData("old-dev", "Old Phone"))
        assertEquals(1, d.getOwnDevices().size)

        d.setOwnDevices(listOf(
            FriendDeviceInfo("uuid-1", "new-dev-1", "Phone A", 100),
            FriendDeviceInfo("uuid-2", "new-dev-2", "Phone B", 200)
        ))

        val after = d.getOwnDevices()
        assertEquals(2, after.size)
        val ids = after.map { it.deviceId }.toSet()
        assertTrue("new-dev-1" in ids)
        assertTrue("new-dev-2" in ids)
        assertTrue("old-dev" !in ids, "Old device must be wiped by setOwnDevices")
    }

    @Test
    fun `setOwnDevices falls back to deviceUuid when deviceId is blank`() = runTest {
        // Real-world: link-code flow sometimes sends deviceUuid but not yet
        // a server-assigned deviceId. Code path uses deviceUuid as PK.
        val d = newDomain()
        d.setOwnDevices(listOf(
            FriendDeviceInfo(deviceUuid = "uuid-only", deviceId = "", deviceName = "fresh")
        ))
        assertEquals(listOf("uuid-only"), d.getOwnDevices().map { it.deviceId })
    }

    @Test
    fun `getSelfSyncTargets returns own device ids`() = runTest {
        val d = newDomain()
        d.addOwnDevice(OwnDeviceData("dev-1", "A"))
        d.addOwnDevice(OwnDeviceData("dev-2", "B"))

        val targets = d.getSelfSyncTargets()
        assertEquals(setOf("dev-1", "dev-2"), targets.toSet())
    }

    @Test
    fun `getSelfSyncTargets returns empty list when no own devices`() = runTest {
        assertEquals(emptyList<String>(), newDomain().getSelfSyncTargets())
    }
}
