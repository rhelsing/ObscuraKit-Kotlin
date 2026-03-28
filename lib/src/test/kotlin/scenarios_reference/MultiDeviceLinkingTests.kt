package scenarios_reference

import com.obscura.kit.ObscuraTestClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import obscura.v2.Client.ClientMessage
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Scenario 5: Multi-device E2E.
 * Bob has 2 devices. Alice sends message. Both Bob devices receive.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MultiDeviceLinkingTests {

    companion object {
        private val API_URL = System.getenv("OBSCURA_API_URL") ?: "https://obscura.barrelmaker.dev"
        private var serverAvailable = false

        private var alice: ObscuraTestClient? = null
        private var bob1: ObscuraTestClient? = null
        private var bob2: ObscuraTestClient? = null
        private var bobUsername: String? = null
        private var bobPassword = "testpass123!xyz"

        @BeforeAll
        @JvmStatic
        fun setup() {
            serverAvailable = try {
                java.net.URL("$API_URL/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close()
                true
            } catch (e: Exception) { false }
        }
    }

    private fun requireServer() = assumeTrue(serverAvailable, "Server not available")
    private fun uniqueUsername() = "kt_${System.currentTimeMillis()}_${(1000..9999).random()}"

    @Test
    @Order(1)
    fun `5-1 - Register Bob device1 and Alice`() = runBlocking {
        requireServer()

        // Register Bob (device 1)
        bob1 = ObscuraTestClient(API_URL)
        bobUsername = uniqueUsername()
        bob1!!.register(bobUsername!!, bobPassword)

        // Register Alice
        alice = ObscuraTestClient(API_URL)
        alice!!.register(uniqueUsername(), "testpass123!xyz")

        assertNotNull(bob1!!.deviceId)
        assertNotNull(alice!!.deviceId)
    }

    @Test
    @Order(2)
    fun `5-2 - Provision Bob device2 (same user, new device)`() = runBlocking {
        requireServer()
        assertNotNull(bob1, "5-1 must run first")

        bob2 = ObscuraTestClient(API_URL)

        // Login as Bob (user-scoped token, no deviceId)
        bob2!!.login(bobUsername, password = bobPassword, deviceId = null)

        // bob2 now has user-scoped token. Register new device with fresh Signal keys.
        // We need to do a manual device provision since register() would create a new user.
        val (identityKeyPair, regId) = bob2!!.signalStore.generateIdentity()
        val signedPreKeyPair = org.signal.libsignal.protocol.ecc.Curve.generateKeyPair()
        val signature = org.signal.libsignal.protocol.ecc.Curve.calculateSignature(
            identityKeyPair.privateKey, signedPreKeyPair.publicKey.serialize()
        )
        val signedPreKey = org.signal.libsignal.protocol.state.SignedPreKeyRecord(1, System.currentTimeMillis(), signedPreKeyPair, signature)
        bob2!!.signalStore.storeSignedPreKey(1, signedPreKey)

        val oneTimePreKeys = (1..100).map { id ->
            val kp = org.signal.libsignal.protocol.ecc.Curve.generateKeyPair()
            val record = org.signal.libsignal.protocol.state.PreKeyRecord(id, kp)
            bob2!!.signalStore.storePreKey(id, record)
            record
        }

        val identityKeyB64 = java.util.Base64.getEncoder().encodeToString(identityKeyPair.publicKey.serialize())
        val spkJson = JSONObject().apply {
            put("keyId", signedPreKey.id)
            put("publicKey", java.util.Base64.getEncoder().encodeToString(signedPreKey.keyPair.publicKey.serialize()))
            put("signature", java.util.Base64.getEncoder().encodeToString(signedPreKey.signature))
        }
        val otpJsonArr = org.json.JSONArray(oneTimePreKeys.map { pk ->
            JSONObject().apply {
                put("keyId", pk.id)
                put("publicKey", java.util.Base64.getEncoder().encodeToString(pk.keyPair.publicKey.serialize()))
            }
        })

        val provResult = bob2!!.api.provisionDevice(
            name = "Bob Device 2",
            identityKey = identityKeyB64,
            registrationId = regId,
            signedPreKey = spkJson,
            oneTimePreKeys = otpJsonArr
        )

        val deviceToken = provResult.getString("token")
        bob2!!.api.token = deviceToken

        // Parse deviceId from token or response
        val payload = deviceToken.split(".")[1]
        val decoded = JSONObject(String(java.util.Base64.getUrlDecoder().decode(payload)))
        val bob2DeviceId = decoded.optString("device_id", null) ?: provResult.optString("deviceId", null)

        assertNotNull(bob2DeviceId, "Bob2 should have a deviceId")
        assertNotEquals(bob1!!.deviceId, bob2DeviceId, "Bob2 should have different deviceId")

        // Manually set bob2's identity
        bob2!!.mapDevice(bob2DeviceId!!, bob1!!.userId!!, regId)

        // Verify server sees 2 devices
        val devices = bob2!!.api.listDevices()
        assertEquals(2, devices.length(), "Bob should have 2 devices on server")
    }

    @Test
    @Order(3)
    fun `5-3 - Alice sends to both Bob devices, both receive`() = runBlocking {
        requireServer()
        assertNotNull(bob1, "5-1 must run first")
        assertNotNull(bob2, "5-2 must run first")

        // Connect all WebSockets
        bob1!!.connectWebSocket()
        bob2!!.connectWebSocket()

        // Alice fetches Bob's prekey bundles — should get 2 devices
        val bobBundles = alice!!.fetchPreKeyBundles(bob1!!.userId!!)
        assertTrue(bobBundles.size >= 2, "Should get bundles for both Bob devices")

        // Alice encrypts and sends to BOTH Bob devices (fan-out)
        val msgBytes = alice!!.encodeClientMessage(ClientMessage.Type.TEXT) {
            text = "Hello both Bobs!"
        }
        val bobDeviceIds = alice!!.getDeviceIdsForUser(bob1!!.userId!!)
        assertTrue(bobDeviceIds.size >= 2, "Alice should know 2 Bob devices after fetchPreKeyBundles")

        for (devId in bobDeviceIds) {
            alice!!.queueMessage(devId, msgBytes, bob1!!.userId!!)
        }
        val sent = alice!!.flushMessages()
        assertTrue(sent >= 2, "Should have sent to at least 2 devices")

        // Both Bob devices should receive
        val msg1 = bob1!!.waitForMessage(15_000)
        assertEquals("TEXT", msg1.type)
        assertEquals("Hello both Bobs!", msg1.text)

        val msg2 = bob2!!.waitForMessage(15_000)
        assertEquals("TEXT", msg2.type)
        assertEquals("Hello both Bobs!", msg2.text)

        bob1!!.disconnectWebSocket()
        bob2!!.disconnectWebSocket()
    }

}
