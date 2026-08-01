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
 * Phase 0 task 0.1 (`git show bb9259c:PLAN.md`) — the two-device test. Targets finding F1.
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
 * HISTORY — why this file is shaped the way it is (2026-07-14 → 2026-07-24):
 *
 *   Originally these tests DOCUMENTED the bug rather than guarding against it. Order 2 passed
 *   vacuously and Order 3 confirmed F1 by reproducing it. That was correct then: F1 was latent,
 *   masked by F9 (Alice's own-device registry was never populated — addOwnDevice had no callers —
 *   so every DeviceAnnounce was empty, Bob's friend store stayed EMPTY, rebuildDeviceMap had
 *   nothing to clobber, and the deviceMap kept the real per-device registration IDs). Order 3
 *   therefore had to HAND-BUILD the DeviceAnnounce a fixed propagation path would send, and guard
 *   that setup with assumeTrue — so it could silently SKIP.
 *
 *   Phase 2 (PR #40) fixed both: sessions are addressed by DEVICE UUID (F1) and the own-device
 *   registry is populated (F9). These are now REGRESSION GUARDS, and every assumeTrue on the setup
 *   is gone — after F9 there is no legitimate reason for this test to skip, and a skipped test is
 *   not a passing one.
 *
 * WHAT THEY GUARD NOW:
 *
 *   Order 2 — the happy path: both devices decrypt on a fresh befriend, and again after the sender
 *     reconnects. Necessary but NOT sufficient — see Order 3.
 *
 *   Order 3 — `git show bb9259c:PLAN.md` §0.1's actual acceptance criterion. Drives the REAL announceDevices() API (no
 *     fabricated protobuf: a test that builds the message it means to verify proves only that the
 *     builder works), asserts Bob genuinely learns BOTH device UUIDs, then forces the
 *     sender-reconnect-after-friendship sequence that is F1's exact precondition, and requires both
 *     devices to decrypt. Pre-Phase-2 this failed; if it ever fails again, either device-UUID
 *     addressing or device propagation has regressed.
 *
 * Each test prints, per Alice device, the registrationId and the ProtocolAddress the ciphertext was
 * encrypted under, so a failure is diagnosable from the log rather than by re-deriving the mechanism.
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

    /**
     * What the sender believes about Alice's devices, and which Signal sessions it actually holds.
     *
     * Post-Phase-2 this prints the DEVICE-UUID address, because that is what the code now uses:
     * `MessengerDomain.addressFor(deviceUuid)` -> `SignalProtocolAddress(deviceUuid, 1)`. An earlier
     * version of this helper printed `(aliceUserId, registrationId)` and looked sessions up by
     * registrationId — which after Phase 2 reports "no sessions" for a client that holds two healthy
     * ones, and describes an addressing scheme the kit no longer uses. A diagnostic that lies is
     * worse than no diagnostic: it sends the next reader after a bug that is not there.
     */
    private fun senderState(tag: String) {
        val b = bob!!
        val aliceId = alice1!!.userId!!
        println("── $tag ──")
        // The rebuildDeviceMap input: the friend store's per-device list. registrationId is printed
        // only to show it is now inert — it addresses nothing (HISTORY.md F1 status, 2026-07-24).
        val storeDevices = b.friendList.value.find { it.userId == aliceId }?.devices ?: emptyList()
        println("  bob's accepted-friends store lists ${storeDevices.size} device(s) for Alice " +
            "(this is what rebuildDeviceMap reads):")
        storeDevices.forEach { println("    ${it.deviceId} registrationId=${it.registrationId} (inert since Phase 2)") }
        val devIds = b.messenger.getDeviceIdsForUser(aliceId)
        println("  bob fans out to ${devIds.size} device(s) for Alice:")
        for (d in devIds) {
            val label = when (d) {
                alice1!!.deviceId -> "device1"
                alice2!!.deviceId -> "device2"
                else -> "UNKNOWN "
            }
            // The real Phase 2 address, and whether a session exists at it. Two devices collapsing
            // onto ONE address is precisely the F1 failure this test exists to catch.
            val hasSession = b.signalStore.containsSession(SignalProtocolAddress(d, 1))
            println("    $label $d -> encrypts at address ($d, 1)  session=${if (hasSession) "present" else "ABSENT"}")
        }
        val distinctAddresses = devIds.toSet().size
        println("  distinct Signal addresses in use for Alice: $distinctAddresses (must equal ${devIds.size} — " +
            "one address for two devices is F1)")
    }

    /** Bob sends one message; BOTH of Alice's devices must receive and decrypt it. */
    private suspend fun sendAndBothMustDecrypt(text: String) {
        // The app names the recipient by userId now (SPEC §0.4); the kit fans out to every device
        // of that user, which is exactly what this test is about.
        bob!!.send(
            recipientUserIds = listOf(alice1!!.userId!!),
            modelKey = "directMessage",
            entryId = "dm_${System.currentTimeMillis()}_${(0..99999).random()}",
            payload = org.json.JSONObject(mapOf(
                "conversationId" to listOf(bob!!.userId!!, alice1!!.userId!!).sorted().joinToString("_"),
                "content" to text,
            )).toString().toByteArray(),
        )

        val onD1 = runCatching { alice1!!.waitForMessage(15_000) }
        val onD2 = runCatching { alice2!!.waitForMessage(15_000) }
        // `.text` is the legacy TEXT arm's field and is EMPTY for a MODEL_SYNC — the content lives
        // in the opaque payload. This test is about both devices decrypting, so it must read the
        // content from wherever it actually is.
        val d1ok = onD1.getOrNull()?.content() == text
        val d2ok = onD2.getOrNull()?.content() == text

        println("  RESULT '$text': device1 decrypted=$d1ok  device2 decrypted=$d2ok")
        if (!d1ok || !d2ok) {
            // Was an envelope delivered at all, and did it fail specifically at DECRYPT?
            println("  --- alice device1 debugLog (newest last) ---")
            alice1!!.debugLog.take(10).reversed().forEach { println("    $it") }
            println("  --- alice device2 debugLog (newest last) ---")
            alice2!!.debugLog.take(10).reversed().forEach { println("    $it") }
        }

        assertTrue(d1ok, "Alice device 1 must receive and decrypt '$text' — got ${onD1.getOrNull()?.content()}")
        assertTrue(d2ok, "Alice device 2 must receive and decrypt '$text' — got ${onD2.getOrNull()?.content()}")
    }

    @Test @Order(1)
    fun `server lists two devices for Alice`() = runBlocking {
        need()
        assertEquals(2, alice1!!.api.listDevices().length(), "Alice should have 2 devices on the server")
    }

    /**
     * The happy path: Bob befriends Alice (which enumerates both her devices via the prekey fetch)
     * and sends, before and after a reconnect. NECESSARY BUT NOT SUFFICIENT — historically this
     * passed while F1 was live, because the friend store was empty and so the deviceMap kept the
     * real per-device registration IDs. Order 3 is the test that actually pins the invariant; this
     * one exists to localise a failure (if BOTH orders fail, the break is in plain fan-out, not in
     * propagation or rebuild).
     */
    @Test @Order(2)
    fun `two-device happy path - fresh befriend and after sender reconnect`() = runBlocking {
        need()

        // (a) Plain send, deviceMap freshly populated by befriend()'s prekey fetch.
        senderState("(a) fresh — deviceMap from befriend()'s prekey fetch")
        sendAndBothMustDecrypt("f1-a-fresh")

        // (b) Bob reconnects — connect() calls rebuildDeviceMap(friends.getAccepted()). Pre-Phase-2
        //     this rewrote both Alice devices to registrationId = 1 and collapsed them onto one
        //     session address; now the address is the device UUID, so the rebuild cannot collapse them.
        bob!!.disconnect(); delay(500); bob!!.connect(); delay(500)
        senderState("(b) after sender reconnect — rebuildDeviceMap has run")
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
    fun `F1 regression guard - real announceDevices, sender reconnect, both devices decrypt`() = runBlocking {
        need()
        val aliceId = alice1!!.userId!!

        // Reconnect the participants (Order 2 left them disconnected).
        alice1!!.connect(); alice2!!.connect(); bob!!.connect()
        delay(500)
        try { while (true) { bob!!.waitForMessage(1_500) } } catch (_: Exception) {}

        // ── Step 1: F9 guard. announceDevices() broadcasts whatever the own-device registry holds,
        // so an empty registry makes the announce inert and silently voids the rest of this test.
        // Before Phase 2 this was 0 (addOwnDevice had no callers) and the probe had to hand-build a
        // DeviceAnnounce to reach F1's precondition at all. Assert it, do not assume it: if
        // propagation regresses, THIS is the line that should fail, and it should fail loudly.
        val ownDevices = alice1!!.devices.getOwnDevices()
        println("  alice1.getOwnDevices()=${ownDevices.size} alice2.getOwnDevices()=${alice2!!.devices.getOwnDevices().size}")
        ownDevices.forEach { println("    own: ${it.deviceId} ${it.deviceName}") }
        assertEquals(2, ownDevices.size,
            "F9 regression: Alice's own-device registry must list BOTH devices, or announceDevices() " +
                "broadcasts an empty list and device propagation is dead again")

        // ── Step 2: the REAL propagation path. No hand-built protobuf: this is the shipping API,
        // which is the whole point — a test that fabricates the message it is meant to verify
        // proves only that the fabricator works.
        alice1!!.announceDevices()

        // ── Step 3: Bob must actually learn both devices. Hard assertion, not assumeTrue:
        // a skipped test is not a passing one, and after F9 there is no legitimate reason to skip.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            (bob!!.friendList.value.find { it.userId == aliceId }?.devices?.size ?: 0) < 2) {
            try { bob!!.waitForMessage(1_500) } catch (_: Exception) {}
        }
        val storeDevices = bob!!.friendList.value.find { it.userId == aliceId }?.devices ?: emptyList()
        println("  after announceDevices(), bob's friend store lists ${storeDevices.size} device(s) for Alice:")
        storeDevices.forEach { println("    ${it.deviceId} registrationId=${it.registrationId}") }
        assertEquals(2, storeDevices.size,
            "Bob's friend store must list both of Alice's devices after a real announceDevices() " +
                "(F6/F9 propagation). Got: ${storeDevices.map { it.deviceId }}")
        assertTrue(
            storeDevices.map { it.deviceId }.toSet() == setOf(alice1!!.deviceId, alice2!!.deviceId),
            "Bob learned the wrong device UUIDs: ${storeDevices.map { it.deviceId }}"
        )

        // ── Step 4: F1's exact precondition. connect() -> rebuildDeviceMap(friends.getAccepted())
        // reads that now-populated list. Pre-Phase-2 this stamped every device registrationId = 1,
        // the non-empty map made sendToAllDevices SKIP the corrective prekey fetch, and both devices
        // got one ciphertext encrypted at a single (userId, 1) address — only one could read it.
        // Post-Phase-2 the address is the device UUID, so the rebuild cannot collapse two devices
        // onto one session. `git show bb9259c:PLAN.md` §0.1 is explicit that a test WITHOUT this reconnect passes
        // vacuously; the reconnect is the load-bearing step.
        bob!!.disconnect(); delay(500); bob!!.connect(); delay(500)
        senderState("(c) friend store populated by the real announce + sender reconnect — F1's precondition")
        sendAndBothMustDecrypt("f1-c-announce")

        alice1!!.disconnect(); alice2!!.disconnect(); bob!!.disconnect()
    }
}
