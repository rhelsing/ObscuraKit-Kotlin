package scenarios

import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ORMTests {

    companion object {
        private val API = "https://obscura.barrelmaker.dev"
        private var serverUp = false
        private var alice: ObscuraClient? = null
        private var bob: ObscuraClient? = null

        @BeforeAll @JvmStatic fun setup() {
            serverUp = try {
                java.net.URL("$API/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close(); true
            } catch (e: Exception) { false }

            if (serverUp) runBlocking {
                alice = ObscuraClient(ObscuraConfig(API))
                alice!!.register("kt_o8_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
                bob = ObscuraClient(ObscuraConfig(API))
                bob!!.register("kt_o8b_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
                alice!!.connect(); bob!!.connect()
                alice!!.befriend(bob!!.userId!!, bob!!.username!!)
                bob!!.waitForMessage()
                bob!!.acceptFriend(alice!!.userId!!, alice!!.username!!)
                alice!!.waitForMessage()
            }
        }
    }

    private fun need() = assumeTrue(serverUp && alice != null)

    @Test @Order(1)
    fun `8-1 - MODEL_SYNC CREATE delivered to friend`() = runBlocking {
        need()
        alice!!.sendModelSync(bob!!.username!!, "story", "story_${System.currentTimeMillis()}",
            data = mapOf("content" to "Hello ORM"))

        val msg = bob!!.waitForMessage()
        assertEquals("MODEL_SYNC", msg.type)
        assertEquals("story", msg.raw!!.modelSync.model)
        val data = org.json.JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
        assertEquals("Hello ORM", data.getString("content"))
    }

    @Test @Order(2)
    fun `8-2 - Bidirectional MODEL_SYNC exchange`() = runBlocking {
        need()
        alice!!.sendModelSync(bob!!.username!!, "story", "from_alice",
            data = mapOf("content" to "Alice story"))
        val atBob = bob!!.waitForMessage()
        assertEquals("from_alice", atBob.raw!!.modelSync.id)

        bob!!.sendModelSync(alice!!.username!!, "story", "from_bob",
            data = mapOf("content" to "Bob story"))
        val atAlice = alice!!.waitForMessage()
        assertEquals("from_bob", atAlice.raw!!.modelSync.id)
    }

    @Test @Order(3)
    fun `8-3 - MODEL_SYNC UPDATE (LWW)`() = runBlocking {
        need()
        val id = "streak_${System.currentTimeMillis()}"

        alice!!.sendModelSync(bob!!.username!!, "streak", id,
            data = mapOf("count" to 5))
        bob!!.waitForMessage()

        alice!!.sendModelSync(bob!!.username!!, "streak", id, op = "UPDATE",
            data = mapOf("count" to 10))
        val m2 = bob!!.waitForMessage()
        val d = org.json.JSONObject(String(m2.raw!!.modelSync.data.toByteArray()))
        assertEquals(10, d.getInt("count"))
    }
}
