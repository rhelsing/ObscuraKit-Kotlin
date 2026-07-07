package scenarios

import com.obscura.kit.ObscuraError
import com.obscura.kit.orm.Audience
import com.obscura.kit.orm.FieldTypes
import com.obscura.kit.orm.ModelConfig
import com.obscura.kit.orm.SyncStrategy
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * Vector-driven L3 model-config parsing conformance, consuming the shared
 * `proto/conformance/schema.json` (see obscura-proto SPEC §4). Every kit runs the
 * same file.
 *
 * Pins how a raw model config (the value in the shared schema.ts map) is parsed
 * into a [ModelConfig] — field base-type + optionality, sync strategy, ttl,
 * audience — or the fail-loud INVALID_SCHEMA error for an invalid definition.
 * This is the direct guard against the divergent-parsing bug class (a kit
 * silently ignoring `audience` so a private model broadcast to all friends).
 *
 * Goes through the SAME entry point as production: [ModelConfig.fromWire] (which
 * `defineModelsFromJson` also calls), so parsing cannot drift between prod and test.
 */
class SchemaConformanceTest {

    @TestFactory
    fun `schema conformance`(): List<DynamicTest> {
        val cases = loadVectors("schema.json").getJSONArray("cases")
        return (0 until cases.length()).map { i ->
            val case = cases.getJSONObject(i)
            DynamicTest.dynamicTest(case.getString("name")) { runCase(case) }
        }
    }

    private fun runCase(case: JSONObject) {
        val config = case.getJSONObject("config")
        val expect = case.getJSONObject("expect")

        if (expect.has("error")) {
            val ex = assertThrows<ObscuraError> { ModelConfig.fromWire(config) }
            assertEquals(expect.getString("error"), ex.code, "wrong fail-loud error code")
            return
        }

        val parsed = ModelConfig.fromWire(config)

        assertEquals(
            SyncStrategy.fromWire(expect.getString("sync")), parsed.sync, "sync strategy",
        )
        assertEquals(
            if (expect.isNull("ttl")) null else expect.getString("ttl"), parsed.ttl, "ttl",
        )

        val expAud = expect.getJSONObject("audience")
        assertEquals(expAud.getString("kind"), audienceKind(parsed.audience), "audience kind")
        assertEquals(
            if (expAud.isNull("field")) null else expAud.getString("field"),
            audienceField(parsed.audience),
            "audience field",
        )

        val expFields = expect.getJSONObject("fields")
        assertEquals(expFields.keySet(), parsed.fields.keys, "field name set")
        for (name in expFields.keySet()) {
            val (base, optional) = FieldTypes.parse(name, parsed.fields.getValue(name))
            val expField = expFields.getJSONObject(name)
            assertEquals(expField.getString("type"), base, "[$name] base type")
            assertEquals(expField.getBoolean("optional"), optional, "[$name] optional")
        }
    }

    private fun audienceKind(a: Audience): String = when (a) {
        is Audience.Friends -> "friends"
        is Audience.Self -> "self"
        is Audience.Recipient -> "recipient"
        is Audience.Conversation -> "conversation"
    }

    private fun audienceField(a: Audience): String? = when (a) {
        is Audience.Recipient -> a.usernameField
        is Audience.Conversation -> a.conversationField
        else -> null
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
