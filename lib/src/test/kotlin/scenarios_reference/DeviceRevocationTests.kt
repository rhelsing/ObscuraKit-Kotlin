package scenarios_reference

import com.obscura.kit.ObscuraTestClient
import kotlinx.coroutines.runBlocking
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
 * Scenario 7: Device revocation E2E.
 * Bob has 2 devices. Bob1 revokes Bob2. Verify server reflects deletion.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DeviceRevocationTests {

    companion object {
        private val API_URL = System.getenv("OBSCURA_API_URL") ?: "https://obscura.barrelmaker.dev"
        private var serverAvailable = false
        private var bob1: ObscuraTestClient? = null
        private var bob2DeviceId: String? = null
        private var alice: ObscuraTestClient? = null

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
    fun `7-1 - Register Bob with 2 devices, verify server sees both`() = runBlocking {
        requireServer()

        // Register Bob device1
        bob1 = ObscuraTestClient(API_URL)
        val bobUsername = uniqueUsername()
        bob1!!.register(bobUsername, "testpass123!xyz")

        // Login for device2 (user-scoped)
        val bob2Client = ObscuraTestClient(API_URL)
        bob2Client.login(bobUsername, password = "testpass123!xyz", deviceId = null)

        // Provision device2
        val (ikp, regId) = bob2Client.signalStore.generateIdentity()
        val spkPair = org.signal.libsignal.protocol.ecc.Curve.generateKeyPair()
        val sig = org.signal.libsignal.protocol.ecc.Curve.calculateSignature(ikp.privateKey, spkPair.publicKey.serialize())
        val spk = org.signal.libsignal.protocol.state.SignedPreKeyRecord(1, System.currentTimeMillis(), spkPair, sig)
        bob2Client.signalStore.storeSignedPreKey(1, spk)

        val otps = (1..20).map { id ->
            val kp = org.signal.libsignal.protocol.ecc.Curve.generateKeyPair()
            val rec = org.signal.libsignal.protocol.state.PreKeyRecord(id, kp)
            bob2Client.signalStore.storePreKey(id, rec)
            rec
        }

        val identityKeyB64 = java.util.Base64.getEncoder().encodeToString(ikp.publicKey.serialize())
        val spkJson = JSONObject().apply {
            put("keyId", spk.id)
            put("publicKey", java.util.Base64.getEncoder().encodeToString(spk.keyPair.publicKey.serialize()))
            put("signature", java.util.Base64.getEncoder().encodeToString(spk.signature))
        }
        val otpArr = org.json.JSONArray(otps.map { pk ->
            JSONObject().apply {
                put("keyId", pk.id)
                put("publicKey", java.util.Base64.getEncoder().encodeToString(pk.keyPair.publicKey.serialize()))
            }
        })

        val provResult = bob2Client.api.provisionDevice("Bob Device 2", identityKeyB64, regId, spkJson, otpArr)
        bob2Client.api.token = provResult.getString("token")
        bob2DeviceId = provResult.optString("deviceId", null) ?: bob2Client.api.getDeviceId(provResult.getString("token"))

        // Server should see 2 devices
        val devices = bob1!!.api.listDevices()
        assertEquals(2, devices.length(), "Server should have 2 Bob devices")
    }

    @Test
    @Order(2)
    fun `7-2 - Bob1 deletes Bob2 device from server`() = runBlocking {
        requireServer()
        assertNotNull(bob1)
        assertNotNull(bob2DeviceId)

        bob1!!.api.deleteDevice(bob2DeviceId!!)

        // Verify server now shows 1 device
        val devices = bob1!!.api.listDevices()
        assertEquals(1, devices.length(), "Server should have 1 Bob device after revocation")
    }

    @Test
    @Order(3)
    fun `7-3 - Send DEVICE_ANNOUNCE with isRevocation to Alice`() = runBlocking {
        requireServer()
        assertNotNull(bob1)

        alice = ObscuraTestClient(API_URL)
        alice!!.register(uniqueUsername(), "testpass123!xyz")

        // Establish Signal session
        alice!!.fetchPreKeyBundles(bob1!!.userId!!)
        bob1!!.fetchPreKeyBundles(alice!!.userId!!)

        alice!!.connectWebSocket()

        // Bob1 sends DEVICE_ANNOUNCE with isRevocation=true
        bob1!!.sendMessage(alice!!.deviceId!!, ClientMessage.Type.DEVICE_ANNOUNCE, alice!!.userId!!) {
            deviceAnnounce = obscura.v2.deviceAnnounce {
                devices.add(obscura.v2.deviceInfo {
                    deviceUuid = bob1!!.deviceId!!
                    deviceId = bob1!!.deviceId!!
                    deviceName = "Bob Phone"
                })
                timestamp = System.currentTimeMillis()
                isRevocation = true
                signature = com.google.protobuf.ByteString.copyFrom(ByteArray(64))
            }
        }

        // Alice receives
        val msg = alice!!.waitForMessage(15_000)
        assertEquals("DEVICE_ANNOUNCE", msg.type)
        assertTrue(msg.raw!!.deviceAnnounce.isRevocation, "Should be a revocation announce")
        assertEquals(1, msg.raw!!.deviceAnnounce.devicesCount, "Should show 1 remaining device")

        alice!!.disconnectWebSocket()
    }
}
