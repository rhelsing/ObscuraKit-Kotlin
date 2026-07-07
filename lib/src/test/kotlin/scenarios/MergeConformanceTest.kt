package scenarios

import com.obscura.kit.newInMemoryStore
import com.obscura.kit.orm.OrmEntry
import com.obscura.kit.orm.crdt.GSet
import com.obscura.kit.orm.crdt.LWWMap
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.fail
import java.io.File

/**
 * Vector-driven L3 CRDT merge conformance, consuming the shared
 * `proto/conformance/merge.json` (see obscura-proto SPEC §2). Every kit runs the
 * same file.
 *
 * Cases with multiple `applyOrders` assert CONVERGENCE: the same ops applied in
 * different arrival orders MUST resolve identically. This is what pins the LWW
 * `(timestamp, authorDeviceId)` total order — a non-deterministic tie-break
 * passes single-order tests but diverges here.
 */
class MergeConformanceTest {

    @TestFactory
    fun `merge conformance`(): List<DynamicTest> {
        val cases = loadVectors("merge.json").getJSONArray("cases")
        val tests = mutableListOf<DynamicTest>()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            for (order in applyOrders(case)) {
                tests.add(
                    DynamicTest.dynamicTest("${case.getString("name")} [$order]") {
                        runBlocking { runCase(case, order) }
                    },
                )
            }
        }
        return tests
    }

    private suspend fun runCase(case: JSONObject, order: String) {
        val sync = case.getString("sync")
        var ops = case.getJSONArray("ops").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).toEntry() }
        }
        if (order == "reverse") ops = ops.reversed()

        val expected = case.getJSONObject("expect").getJSONArray("entries")
        val expectedIds = (0 until expected.length()).map { expected.getJSONObject(it).getString("id") }

        // Apply every op via the merge (incoming-sync) path, then resolve winners.
        val store = newInMemoryStore()
        val winners = HashMap<String, OrmEntry?>()
        if (sync == "lww") {
            val map = LWWMap(store, "m")
            for (op in ops) map.merge(listOf(op))
            expectedIds.forEach { winners[it] = map.get(it) }
        } else {
            val set = GSet(store, "m")
            for (op in ops) set.merge(listOf(op))
            expectedIds.forEach { winners[it] = set.get(it) }
        }

        for (i in 0 until expected.length()) {
            val exp = expected.getJSONObject(i)
            val id = exp.getString("id")
            val actual = winners[id] ?: fail("expected entry '$id' is missing after merge")
            if (exp.has("deleted")) {
                assertEquals(exp.getBoolean("deleted"), actual.isDeleted, "[$id] wrong deleted state")
            }
            if (exp.has("authorDeviceId")) {
                assertEquals(exp.getString("authorDeviceId"), actual.authorDeviceId, "[$id] wrong winning author")
            }
            if (exp.has("data")) {
                assertEquals(exp.getJSONObject("data").toMap(), actual.data, "[$id] wrong winning data")
            }
        }
    }

    private fun applyOrders(case: JSONObject): List<String> {
        val arr = case.optJSONArray("applyOrders") ?: return listOf("forward")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun JSONObject.toEntry() = OrmEntry(
        id = getString("id"),
        data = getJSONObject("data").toMap(),
        timestamp = getLong("ts"),
        authorDeviceId = getString("authorDeviceId"),
    )

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
