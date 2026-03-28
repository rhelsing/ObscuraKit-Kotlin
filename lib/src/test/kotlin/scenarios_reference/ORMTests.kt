package scenarios_reference

import com.obscura.kit.ObscuraTestClient
import kotlinx.coroutines.runBlocking
import obscura.v2.Client.ClientMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Scenario 8: ORM MODEL_SYNC E2E.
 * Alice creates ORM entry, broadcasts MODEL_SYNC encrypted to Bob. Bob receives, merges.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ORMTests {

    companion object {
        private val API_URL = System.getenv("OBSCURA_API_URL") ?: "https://obscura.barrelmaker.dev"
        private var serverAvailable = false
        private var alice: ObscuraTestClient? = null
        private var bob: ObscuraTestClient? = null

        @BeforeAll
        @JvmStatic
        fun setup() {
            serverAvailable = try {
                java.net.URL("$API_URL/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close()
                true
            } catch (e: Exception) { false }

            if (serverAvailable) {
                kotlinx.coroutines.runBlocking {
                    alice = ObscuraTestClient(API_URL)
                    alice!!.register("kt_orm_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")

                    bob = ObscuraTestClient(API_URL)
                    bob!!.register("kt_orm2_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")

                    // Establish sessions
                    alice!!.fetchPreKeyBundles(bob!!.userId!!)
                    bob!!.fetchPreKeyBundles(alice!!.userId!!)

                    alice!!.storeFriend(bob!!.username!!, bob!!.userId!!, listOf(
                        ObscuraTestClient.DeviceRef(bob!!.deviceId!!, bob!!.registrationId)
                    ))
                }
            }
        }
    }

    private fun requireServer() = assumeTrue(serverAvailable && alice != null, "Server not available")

    @Test
    @Order(1)
    fun `8-1 - Send MODEL_SYNC CREATE encrypted to friend`() = runBlocking {
        requireServer()

        bob!!.connectWebSocket()

        val modelData = """{"content":"Hello from ORM","mediaRef":"att-123"}""".toByteArray()

        // Alice sends MODEL_SYNC to Bob
        alice!!.sendMessage(bob!!.deviceId!!, ClientMessage.Type.MODEL_SYNC, bob!!.userId!!) {
            modelSync = obscura.v2.modelSync {
                model = "story"
                id = "story_${System.currentTimeMillis()}_test"
                op = obscura.v2.Client.ModelSync.Op.CREATE
                timestamp = System.currentTimeMillis()
                data = com.google.protobuf.ByteString.copyFrom(modelData)
                authorDeviceId = alice!!.deviceId!!
            }
        }

        // Bob receives
        val msg = bob!!.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg.type)

        val sync = msg.raw!!.modelSync
        assertEquals("story", sync.model)
        assertEquals(obscura.v2.Client.ModelSync.Op.CREATE, sync.op)

        val data = org.json.JSONObject(String(sync.data.toByteArray()))
        assertEquals("Hello from ORM", data.getString("content"))

        bob!!.disconnectWebSocket()
    }

    @Test
    @Order(2)
    fun `8-2 - Send MODEL_SYNC UPDATE (LWW conflict) encrypted`() = runBlocking {
        requireServer()

        bob!!.connectWebSocket()

        val entryId = "streak_alice_${System.currentTimeMillis()}"

        // Alice sends CREATE
        alice!!.sendMessage(bob!!.deviceId!!, ClientMessage.Type.MODEL_SYNC, bob!!.userId!!) {
            modelSync = obscura.v2.modelSync {
                model = "streak"
                id = entryId
                op = obscura.v2.Client.ModelSync.Op.CREATE
                timestamp = 1000
                data = com.google.protobuf.ByteString.copyFrom("""{"count":5}""".toByteArray())
                authorDeviceId = alice!!.deviceId!!
            }
        }

        val msg1 = bob!!.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg1.type)
        assertEquals(1000L, msg1.raw!!.modelSync.timestamp)

        // Alice sends UPDATE with newer timestamp
        alice!!.sendMessage(bob!!.deviceId!!, ClientMessage.Type.MODEL_SYNC, bob!!.userId!!) {
            modelSync = obscura.v2.modelSync {
                model = "streak"
                id = entryId
                op = obscura.v2.Client.ModelSync.Op.UPDATE
                timestamp = 2000
                data = com.google.protobuf.ByteString.copyFrom("""{"count":10}""".toByteArray())
                authorDeviceId = alice!!.deviceId!!
            }
        }

        val msg2 = bob!!.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg2.type)
        assertEquals(2000L, msg2.raw!!.modelSync.timestamp)

        val data = org.json.JSONObject(String(msg2.raw!!.modelSync.data.toByteArray()))
        assertEquals(10, data.getInt("count"))

        bob!!.disconnectWebSocket()
    }

    @Test
    @Order(3)
    fun `8-3 - Bidirectional MODEL_SYNC exchange`() = runBlocking {
        requireServer()

        alice!!.connectWebSocket()
        bob!!.connectWebSocket()

        // Alice → Bob
        alice!!.sendMessage(bob!!.deviceId!!, ClientMessage.Type.MODEL_SYNC, bob!!.userId!!) {
            modelSync = obscura.v2.modelSync {
                model = "story"
                id = "story_from_alice"
                op = obscura.v2.Client.ModelSync.Op.CREATE
                timestamp = System.currentTimeMillis()
                data = com.google.protobuf.ByteString.copyFrom("""{"content":"Alice story"}""".toByteArray())
                authorDeviceId = alice!!.deviceId!!
            }
        }

        val msgAtBob = bob!!.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msgAtBob.type)
        assertEquals("story_from_alice", msgAtBob.raw!!.modelSync.id)

        // Bob → Alice
        bob!!.sendMessage(alice!!.deviceId!!, ClientMessage.Type.MODEL_SYNC, alice!!.userId!!) {
            modelSync = obscura.v2.modelSync {
                model = "story"
                id = "story_from_bob"
                op = obscura.v2.Client.ModelSync.Op.CREATE
                timestamp = System.currentTimeMillis()
                data = com.google.protobuf.ByteString.copyFrom("""{"content":"Bob story"}""".toByteArray())
                authorDeviceId = bob!!.deviceId!!
            }
        }

        val msgAtAlice = alice!!.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msgAtAlice.type)
        assertEquals("story_from_bob", msgAtAlice.raw!!.modelSync.id)

        alice!!.disconnectWebSocket()
        bob!!.disconnectWebSocket()
    }
}
