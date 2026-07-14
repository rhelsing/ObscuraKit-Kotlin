package scenarios

import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.AuthState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.signal.libsignal.protocol.SignalProtocolAddress
import obscura.client.v1.Client.ClientMessage

/**
 * PLAN.md Phase 0, task 0.1 — the two-device test. Targets finding F1.
 *
 * Fixture: Alice registers with TWO devices; Bob registers (one device); friendship both ways;
 * Bob sends; assert BOTH of Alice's devices receive AND decrypt.
 *
 * F1's predicted mechanism:
 *   - `DeviceInfo` in client.proto has no registration_id, so every friend device off the wire is
 *     stored with the default registrationId = 1 (FriendDomain.kt:34, ObscuraClient.kt:948);
 *   - `connect()` -> `rebuildDeviceMap(friends.getAccepted())` (ObscuraClient.kt:671) then writes the
 *     sender's deviceMap to (aliceUserId, 1) for every Alice device, clobbering the real ids that
 *     `parsePreKeyBundles` learned;
 *   - `MessageSender.sendToAllDevices` sees a non-empty deviceMap and SKIPS the corrective prekey
 *     fetch (MessageSender.kt:17), so the send encrypts ONE ciphertext at (aliceUserId, 1) and fans
 *     it out to both devices — only one can read it.
 *
 * RESULT (two tests, read them together):
 *
 *   `two-device happy path ...` (Order 2) — the task's literal fixture. PASSES. This is the false
 *     comfort PLAN.md warns about. It passes because a SECOND, independent bug prevents F1's
 *     precondition: Alice's own-device registry is never populated (register/loginAndProvision never
 *     record own devices; approveLink ships the approver's empty getOwnDevices() as the approval's
 *     ownDevices, so the approvee's setOwnDevices([]) writes empty too), so her DeviceAnnounce
 *     carries no devices and Bob's friend store stays EMPTY. rebuildDeviceMap has nothing to clobber,
 *     the deviceMap keeps real per-device registration IDs, and both devices decrypt.
 *
 *   `F1 mechanism probe ...` (Order 3) — forces F1's exact precondition by delivering a populated
 *     DeviceAnnounce (Alice's real device UUIDs, through her real encryption path — no invented wire
 *     fields; this is the message a fixed propagation path would send). Bob's friend store then lists
 *     both devices at registrationId=1; after a reconnect both are encrypted at the SAME address
 *     (aliceUserId, 1); device 2 FAILS to decrypt. F1 CONFIRMED.
 *
 * Conclusion: F1 is a REAL, reproducible latent bug, currently masked by the dead device-list
 * propagation. Fixing propagation without fixing the registrationId addressing would immediately
 * break multi-device delivery. Each test prints, per Alice device, the registrationId used and the
 * ProtocolAddress the ciphertext was encrypted under, so the mechanism is verifiable, not inferred.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TwoDeviceSendTests {

    companion object {
        private var serverUp = false
        private var alice1: ObscuraClient? = null   // Alice, device 1 (phone)
        private var alice2: ObscuraClient? = null   // Alice, device 2 (laptop)
        private var bob: ObscuraClient? = null      // Bob, single device
        private var aliceUsername: String? = null

        @BeforeAll @JvmStatic fun setup() {
            serverUp = checkServer()
            if (!serverUp) return

            runBlocking {
                val name = uniqueName("f1_alice")
                aliceUsername = name

                // 1. Alice, device 1.
                alice1 = ObscuraClient(ObscuraConfig(API, deviceName = "Alice Phone"))
                alice1!!.register(name, TEST_PASSWORD)
                assertEquals(AuthState.AUTHENTICATED, alice1!!.authState.value)
                alice1!!.connect()

                // 2. Alice, device 2 — linked and approved BEFORE any friendship exists.
                alice2 = provisionAndApprove(alice1!!, name, "Alice Laptop")
                assertNotEquals(alice1!!.deviceId, alice2!!.deviceId, "Alice's two devices must differ")
                alice1!!.connect()
                alice2!!.connect()

                // 3. Bob, single device. Friendship both ways. Bob's befriend() fetches Alice's
                //    prekey bundles, which enumerates BOTH of her devices into Bob's deviceMap.
                bob = registerAndConnect("f1_bob")
                becomeFriends(bob!!, alice1!!)

                // Drain the friend fan-out from every queue.
                delay(800)
                drain(alice1!!); drain(alice2!!); drain(bob!!)
            }
        }

        private suspend fun drain(c: ObscuraClient) {
            try { while (true) { c.waitForMessage(2_000) } } catch (_: Exception) {}
        }
    }

    private fun need() = assumeTrue(serverUp && alice2 != null)

    /** What the sender believes about Alice's devices, and which Signal sessions it holds. */
    private fun senderState(tag: String) {
        val b = bob!!
        val aliceId = alice1!!.userId!!
        println("── $tag ──")
        // F1's regId=1 clobber is driven by rebuildDeviceMap(friends.getAccepted()), which reads
        // the friend store's per-device list. Print it: if it is empty, the clobber cannot fire.
        val storeDevices = b.friendList.value.find { it.userId == aliceId }?.devices ?: emptyList()
        println("  bob's accepted-friends store lists ${storeDevices.size} device(s) for Alice " +
            "(this is what rebuildDeviceMap reads):")
        storeDevices.forEach { println("    ${it.deviceId} registrationId=${it.registrationId}") }
        val devIds = b.messenger.getDeviceIdsForUser(aliceId)
        println("  bob fans out to ${devIds.size} device(s) for Alice:")
        for (d in devIds) {
            val label = when (d) {
                alice1!!.deviceId -> "device1"
                alice2!!.deviceId -> "device2"
                else -> "UNKNOWN "
            }
            println("    $label $d -> encrypts at address ($aliceId, ${b.messenger.deviceMap(d)?.second})")
        }
        val regIds = b.signalStore.getAllSessionRegistrationIds(aliceId)
        println("  bob holds Signal sessions for Alice at registrationIds: $regIds")
    }

    /** Bob sends one message; BOTH of Alice's devices must receive and decrypt it. */
    private suspend fun sendAndBothMustDecrypt(text: String) {
        bob!!.send(aliceUsername!!, text)

        val onD1 = runCatching { alice1!!.waitForMessage(15_000) }
        val onD2 = runCatching { alice2!!.waitForMessage(15_000) }
        val d1ok = onD1.getOrNull()?.text == text
        val d2ok = onD2.getOrNull()?.text == text

        println("  RESULT '$text': device1 decrypted=$d1ok  device2 decrypted=$d2ok")
        if (!d1ok || !d2ok) {
            // Was an envelope delivered at all, and did it fail specifically at DECRYPT?
            println("  --- alice device1 debugLog (newest last) ---")
            alice1!!.debugLog.take(10).reversed().forEach { println("    $it") }
            println("  --- alice device2 debugLog (newest last) ---")
            alice2!!.debugLog.take(10).reversed().forEach { println("    $it") }
        }

        assertTrue(d1ok, "Alice device 1 must receive and decrypt '$text' — got ${onD1.getOrNull()?.text}")
        assertTrue(d2ok, "Alice device 2 must receive and decrypt '$text' — got ${onD2.getOrNull()?.text}")
    }

    @Test @Order(1)
    fun `server lists two devices for Alice`() = runBlocking {
        need()
        assertEquals(2, alice1!!.api.listDevices().length(), "Alice should have 2 devices on the server")
    }

    /**
     * CONTROL — the happy-path two-device test. It PASSES, and that pass is the false comfort
     * PLAN.md warns about: it passes only because Bob's friend store is empty (Alice's DeviceAnnounce
     * carries no devices — see the `F1 mechanism probe` test for why), so rebuildDeviceMap has nothing
     * to clobber and the deviceMap keeps the real per-device registration IDs from parsePreKeyBundles.
     * A passing happy-path test is NOT evidence F1 is fixed; the `F1 mechanism probe` test below is.
     */
    @Test @Order(2)
    fun `two-device happy path passes because the friend store is empty`() = runBlocking {
        need()

        // (a) Plain send, deviceMap freshly populated by befriend()'s prekey fetch.
        senderState("(a) fresh — deviceMap from befriend()'s prekey fetch")
        sendAndBothMustDecrypt("f1-a-fresh")

        // (b) Bob reconnects — connect() calls rebuildDeviceMap(friends.getAccepted()).
        //     F1 says this rewrites both Alice devices to registrationId = 1.
        bob!!.disconnect(); delay(500); bob!!.connect(); delay(500)
        senderState("(b) after sender reconnect — F1 predicts regId clobbered to 1")
        sendAndBothMustDecrypt("f1-b-reconnect")

        alice1!!.disconnect(); alice2!!.disconnect(); bob!!.disconnect()
    }

    /**
     * Adversarial probe for F1's exact mechanism. F1's regId=1 clobber can only fire if the
     * sender's friend store actually lists the friend's devices — and in the current code it never
     * does (Order 2 shows it stays at 0). This test FORCES that precondition by having Alice's
     * linked device broadcast a real DeviceAnnounce, so Bob's friend store gets populated the way
     * `handleDeviceAnnounce` builds it: each device with registrationId defaulted to 1
     * (client.proto's DeviceInfo carries no registration_id). Then Bob reconnects (rebuildDeviceMap)
     * and sends.
     *
     * This distinguishes two very different conclusions:
     *   - if BOTH devices still decrypt, F1's mechanism is genuinely wrong;
     *   - if device 2 now FAILS, F1 is real and merely masked in normal flows by a *second* bug —
     *     friend device lists are never populated — which is the thing to report to Nolan.
     */
    @Test @Order(3)
    fun `F1 mechanism probe - populate friend store via DeviceAnnounce, then reconnect and send`() = runBlocking {
        need()
        val aliceId = alice1!!.userId!!

        // Reconnect the participants (Order 2 left them disconnected).
        alice1!!.connect(); alice2!!.connect(); bob!!.connect()
        delay(500)
        try { while (true) { bob!!.waitForMessage(1_500) } } catch (_: Exception) {}

        // Alice's linked laptop (which holds both devices in its own-device store, from link
        // approval) broadcasts a DeviceAnnounce to its accepted friends.
        // NOTE: Alice's own-device registry is empty in every real flow (register/loginAndProvision
        // never record own devices, and approveLink ships the approver's empty getOwnDevices() as
        // the approval's ownDevices, so the approvee's setOwnDevices([]) writes empty too). So the
        // real `announceDevices()` API cannot populate Bob's friend store. To exercise F1's exact
        // precondition we hand-build the DeviceAnnounce that a *fixed* propagation path would send —
        // Alice's two real device UUIDs — and deliver it through Alice's genuine encryption path.
        // handleDeviceAnnounce (ObscuraClient.kt:947) then stores each with registrationId defaulted
        // to 1, exactly as it does for any DeviceInfo off the wire.
        println("  alice1.getOwnDevices()=${alice1!!.devices.getOwnDevices().size} " +
            "alice2.getOwnDevices()=${alice2!!.devices.getOwnDevices().size} (both empty = propagation dead)")

        val now = System.currentTimeMillis()
        val announce = ClientMessage.newBuilder()
            .setTimestamp(now)
            .setDeviceAnnounce(obscura.client.v1.deviceAnnounce {
                devices.add(obscura.client.v1.deviceInfo {
                    deviceUuid = alice1!!.deviceId!!; deviceId = alice1!!.deviceId!!; deviceName = "Alice Phone"
                })
                devices.add(obscura.client.v1.deviceInfo {
                    deviceUuid = alice2!!.deviceId!!; deviceId = alice2!!.deviceId!!; deviceName = "Alice Laptop"
                })
                timestamp = now
                isRevocation = false
            }).build()
        alice1!!.messenger.queueMessage(bob!!.deviceId!!, announce, bob!!.userId!!)
        alice1!!.messenger.flushMessages()

        // Wait for Bob to ingest the DeviceAnnounce.
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline &&
            (bob!!.friendList.value.find { it.userId == aliceId }?.devices?.isEmpty() != false)) {
            try { bob!!.waitForMessage(1_500) } catch (_: Exception) {}
        }
        val storeDevices = bob!!.friendList.value.find { it.userId == aliceId }?.devices ?: emptyList()
        println("  after DeviceAnnounce, bob's friend store lists ${storeDevices.size} device(s) for Alice:")
        storeDevices.forEach { println("    ${it.deviceId} registrationId=${it.registrationId}") }
        assumeTrue(storeDevices.isNotEmpty(),
            "DeviceAnnounce did not populate Bob's friend store — the F1 precondition could not be set up")

        // Now the F1 trigger: Bob reconnects -> rebuildDeviceMap(friends.getAccepted()) reads the
        // now-populated (registrationId=1) list and writes it into the deviceMap.
        bob!!.disconnect(); delay(500); bob!!.connect(); delay(500)
        senderState("(c) friend store populated + sender reconnect — F1's exact precondition")
        sendAndBothMustDecrypt("f1-c-announce")

        alice1!!.disconnect(); alice2!!.disconnect(); bob!!.disconnect()
    }
}
