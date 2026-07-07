package scenarios

import com.obscura.kit.ObscuraError
import com.obscura.kit.orm.Audience
import com.obscura.kit.orm.Model
import com.obscura.kit.orm.ModelConfig
import com.obscura.kit.orm.OrmEntry
import com.obscura.kit.orm.SyncManager
import com.obscura.kit.orm.SyncStrategy
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * Vector-driven L3 delivery-targeting conformance.
 *
 * The vectors live in the obscura-proto submodule (`proto/conformance/routing.json`)
 * so the Swift + web kits run the SAME file — this is the prototype consumer. See
 * `obscura-proto/SPEC.md` §1 and `conformance/README.md` for the contract.
 *
 * This replaces the former hand-written SyncTargetingTests: the confidentiality
 * boundary (which devices a 1:1 payload reaches, and the fail-loud refusal to
 * broadcast an unresolved 1:1) is now defined by data, not code.
 *
 * Harness simplification: one device per user (deviceId == userId). Routing
 * vectors pin audience -> recipient set; device fan-out is mechanical and out of
 * scope, so the recorded target set equals the recipient-userId set directly.
 */
class RoutingConformanceTest {

    @TestFactory
    fun `routing conformance`(): List<DynamicTest> {
        val vectors = loadVectors("routing.json")
        val topo = vectors.getJSONObject("topology")
        val selfId = topo.getString("selfUserId")

        // userId -> [deviceId] and username -> [deviceId], one device per user.
        val devicesByUserId = hashMapOf(selfId to listOf(selfId))
        val devicesByUsername = hashMapOf<String, List<String>>()
        val friendIds = mutableListOf<String>()
        val friends = topo.getJSONArray("friends")
        for (i in 0 until friends.length()) {
            val f = friends.getJSONObject(i)
            val uid = f.getString("userId")
            devicesByUserId[uid] = listOf(uid)
            devicesByUsername[f.getString("username")] = listOf(uid)
            if (f.getString("status") == "accepted") friendIds.add(uid)
        }

        val cases = vectors.getJSONArray("cases")
        return (0 until cases.length()).map { i ->
            val case = cases.getJSONObject(i)
            DynamicTest.dynamicTest(case.getString("name")) {
                runBlocking {
                    runCase(case, selfId, friendIds, devicesByUserId, devicesByUsername)
                }
            }
        }
    }

    private suspend fun runCase(
        case: JSONObject,
        selfId: String,
        friendIds: List<String>,
        devicesByUserId: Map<String, List<String>>,
        devicesByUsername: Map<String, List<String>>,
    ) {
        val recorded = mutableListOf<String>()
        val sm = SyncManager().apply {
            getSelfSyncTargets = { listOf(selfId) }
            getFriendTargets = { friendIds }
            getDevicesForUsername = { devicesByUsername[it] ?: emptyList() }
            getDevicesForUserId = { devicesByUserId[it] ?: emptyList() }
            queueModelSync = { targetDeviceId, _ -> recorded.add(targetDeviceId) }
            flushQueue = { }
        }

        val model = Model(case.getJSONObject("schema").let { schema ->
            schema.optString("model", "m")
        }, buildConfig(case.getJSONObject("schema")))

        val entryObj = case.getJSONObject("entry")
        val entry = OrmEntry(
            id = entryObj.getString("id"),
            data = entryObj.getJSONObject("data").toMap(),
            timestamp = 1L,
            authorDeviceId = selfId,
        )

        val expect = case.getJSONObject("expect")
        if (expect.has("error")) {
            val ex = assertThrows<ObscuraError> { runBlocking { sm.broadcast(model, entry) } }
            assertEquals(expect.getString("error"), ex.code, "wrong fail-loud error code")
            assertEquals(
                emptyList<String>(), recorded,
                "a kit MUST send nothing when a 1:1 recipient cannot be resolved",
            )
        } else {
            sm.broadcast(model, entry)
            val expected = expect.getJSONArray("recipients")
                .let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                .toSet()
            assertEquals(expected, recorded.toSet(), "delivered to the wrong recipient set")
        }
    }

    /** Build a ModelConfig through the SAME parse entry points as defineModelsFromJson, so a bad schema in a vector fails the way production would. */
    private fun buildConfig(schema: JSONObject): ModelConfig {
        val audienceObj = schema.optJSONObject("audience")
        return ModelConfig(
            sync = SyncStrategy.fromWire(schema.optString("sync", "gset")),
            ttl = if (schema.has("ttl") && !schema.isNull("ttl")) schema.getString("ttl") else null,
            audience = Audience.fromWire(
                kind = audienceObj?.optString("kind"),
                field = audienceObj?.optString("field"),
            ),
        )
    }

    private fun loadVectors(name: String): JSONObject {
        val candidates = listOf("../proto/conformance/$name", "proto/conformance/$name")
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: error(
                "conformance vector '$name' not found (looked in: ${candidates.joinToString()}). " +
                    "Is the obscura-proto submodule checked out? Run: git submodule update --init",
            )
        return JSONObject(file.readText())
    }
}
