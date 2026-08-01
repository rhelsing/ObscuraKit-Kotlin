package com.obscura.kit.stores

import com.obscura.kit.newInMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * FriendDomain is the source of truth for the friend list and for a friend's devices. Tests
 * exercise the JSON-encoded `devices` blob through the public API to catch silent parse failures
 * (`parseDevices` swallows errors and returns emptyList — the kind of failure that breaks message
 * delivery without throwing).
 *
 * The read-back here is `get(userId)` rather than the `getAll()` this file used to lean on:
 * `getAll`, `getPending`, `getFanOutTargets` and `getAllFriendDeviceTargets` were only ever called
 * by these tests, so they were four public methods whose entire purpose was to be tested.
 */
class FriendDomainTest {

    private fun newDomain() = FriendDomain(newInMemoryDatabase())

    @Test
    fun `add then get returns the friend`() = runTest {
        val d = newDomain()
        d.add("u1", "alice", FriendStatus.ACCEPTED)
        val f = d.get("u1")!!
        assertEquals("alice", f.username)
        assertEquals(FriendStatus.ACCEPTED, f.status)
    }

    @Test
    fun `get returns null for a user who was never added`() = runTest {
        assertNull(newDomain().get("nope"))
    }

    @Test
    fun `getAccepted filters out pending friends`() = runTest {
        val d = newDomain()
        d.add("u1", "alice", FriendStatus.ACCEPTED)
        d.add("u2", "bob", FriendStatus.PENDING_SENT)
        d.add("u3", "carol", FriendStatus.PENDING_RECEIVED)

        val accepted = d.getAccepted()
        assertEquals(setOf("alice"), accepted.map { it.username }.toSet())
    }

    @Test
    fun `add with devices round-trips the device list`() = runTest {
        val d = newDomain()
        val devices = listOf(
            FriendDeviceInfo(deviceUuid = "uuid-1", deviceId = "dev-1",
                deviceName = "Pixel", registrationId = 100),
            FriendDeviceInfo(deviceUuid = "uuid-2", deviceId = "dev-2",
                deviceName = "iPhone", registrationId = 200)
        )
        d.add("u1", "alice", FriendStatus.ACCEPTED, devices)

        val loaded = d.get("u1")!!
        assertEquals(2, loaded.devices.size)
        val byId = loaded.devices.associateBy { it.deviceId }
        assertEquals("Pixel", byId["dev-1"]?.deviceName)
        assertEquals(100, byId["dev-1"]?.registrationId)
        assertEquals("iPhone", byId["dev-2"]?.deviceName)
        assertEquals(200, byId["dev-2"]?.registrationId)
    }

    @Test
    fun `updateDevices replaces the device list while preserving username and status`() = runTest {
        val d = newDomain()
        d.add("u1", "alice", FriendStatus.ACCEPTED, listOf(
            FriendDeviceInfo("uuid-x", "dev-x", "Old", 1)
        ))
        d.updateDevices("u1", listOf(
            FriendDeviceInfo("uuid-y", "dev-y", "New", 2)
        ))

        val loaded = d.get("u1")!!
        assertEquals("alice", loaded.username, "Username must survive device update")
        assertEquals(FriendStatus.ACCEPTED, loaded.status)
        assertEquals(setOf("dev-y"), loaded.devices.map { it.deviceId }.toSet())
    }

    @Test
    fun `updateDevices on unknown user is a no-op`() = runTest {
        val d = newDomain()
        d.updateDevices("never-added", listOf(
            FriendDeviceInfo("uuid", "dev", "X")
        ))
        assertNull(d.get("never-added"), "Update on unknown user must NOT create a phantom friend row")
    }

    @Test
    fun `updateStatus promotes in place without touching the name or devices`() = runTest {
        val d = newDomain()
        d.add("u1", "alice", FriendStatus.PENDING_SENT, listOf(FriendDeviceInfo("u", "dev", "P")))

        d.updateStatus("u1", FriendStatus.ACCEPTED)

        val loaded = d.get("u1")!!
        assertEquals(FriendStatus.ACCEPTED, loaded.status)
        assertEquals("alice", loaded.username)
        assertEquals(listOf("dev"), loaded.devices.map { it.deviceId })
    }

    // ── the TOFU-pinned recovery key ──────────────────────────────────────────

    @Test
    fun `recovery public key is null until something pins one`() = runTest {
        val d = newDomain()
        d.add("u1", "alice", FriendStatus.ACCEPTED)
        assertNull(d.get("u1")!!.recoveryPublicKey,
            "null is what the trust-on-first-use branch keys on; a default would look already-pinned")
    }

    /**
     * The reason `updateStatus` and `updateDevices` are UPDATEs rather than the INSERT OR REPLACE
     * they used to route through. REPLACE deletes the row and re-inserts it, resetting every column
     * the caller did not name — so a peer able to trigger either one could clear its own pin and
     * then re-pin a key of its choosing, which is the whole guarantee gone.
     */
    @Test
    fun `a pinned recovery key survives a device and status update`() = runTest {
        val d = newDomain()
        d.add("u1", "alice", FriendStatus.PENDING_SENT)
        d.pinRecoveryPublicKey("u1", byteArrayOf(1, 2, 3))

        d.updateDevices("u1", listOf(FriendDeviceInfo("u", "dev", "P")))
        d.updateStatus("u1", FriendStatus.ACCEPTED)

        assertArrayEquals(byteArrayOf(1, 2, 3), d.get("u1")!!.recoveryPublicKey)
    }

    @Test
    fun `pinning a recovery key for an unknown user does not create a row`() = runTest {
        val d = newDomain()
        d.pinRecoveryPublicKey("stranger", byteArrayOf(9))
        assertNull(d.get("stranger"))
    }

    @Test
    fun `remove deletes the friend`() = runTest {
        val d = newDomain()
        d.add("u1", "alice", FriendStatus.ACCEPTED)
        d.add("u2", "bob", FriendStatus.ACCEPTED)
        d.remove("u1")

        assertNull(d.get("u1"))
        assertEquals("bob", d.get("u2")!!.username)
    }

    @Test
    fun `exportAll and importAll round-trip`() = runTest {
        val d1 = newDomain()
        d1.add("u1", "alice", FriendStatus.ACCEPTED, listOf(
            FriendDeviceInfo("uuid", "dev", "Phone", 50)
        ))
        d1.add("u2", "bob", FriendStatus.PENDING_SENT)
        val exported = d1.exportAll()

        val d2 = newDomain()
        d2.importAll(exported)

        assertEquals(FriendStatus.ACCEPTED, d2.get("u1")?.status)
        assertEquals(FriendStatus.PENDING_SENT, d2.get("u2")?.status)
    }

    @Test
    fun `a friend stored with no devices loads as an empty list, not a throw`() = runTest {
        // parseDevices swallows malformed JSON and returns emptyList; the observable contract is
        // that a friend always loads, with devices = [] in the worst case.
        val d = newDomain()
        d.add("u1", "alice", FriendStatus.ACCEPTED, emptyList())
        assertEquals(0, d.get("u1")!!.devices.size)
    }
}
