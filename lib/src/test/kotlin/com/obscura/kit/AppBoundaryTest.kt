package com.obscura.kit

import com.obscura.kit.orm.ModelConfig
import com.obscura.kit.orm.SyncStrategy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Verifies the app-boundary contract:
 * - [ProcessedCounts] uses generic model names, not application-specific field names.
 * - [ObscuraConfig.conversationModel] is the single place that connects model
 *   names to routing behaviour; the kit never sniffs model names in production code.
 * - [ObscuraConfig.conversationModel] = null keeps the kit fully model-agnostic.
 */
class AppBoundaryTest {

    // ─── ProcessedCounts shape ────────────────────────────────────────────────

    @Test
    fun `ProcessedCounts has no application-specific field names`() {
        // Compile-time guard: the data class must expose a generic Map, not fields
        // named after specific app models (pix, directMessage, etc.).
        val counts = ProcessedCounts(
            modelCounts = mapOf("pix" to 2, "directMessage" to 1),
            otherCount = 0
        )
        assertEquals(2, counts.modelCounts["pix"])
        assertEquals(1, counts.modelCounts["directMessage"])
        assertEquals(0, counts.otherCount)

        // Verify that iterating modelCounts works without hardcoding key names
        val total = counts.modelCounts.values.sum()
        assertEquals(3, total)
    }

    @Test
    fun `ProcessedCounts empty defaults`() {
        val counts = ProcessedCounts()
        assertTrue(counts.modelCounts.isEmpty())
        assertEquals(0, counts.otherCount)
    }

    @Test
    fun `ProcessedCounts arbitrary model keys accumulate correctly`() {
        val counts = ProcessedCounts(
            modelCounts = mapOf("story" to 5, "profile" to 1, "settings" to 2),
            otherCount = 3
        )
        assertEquals(5, counts.modelCounts["story"])
        assertEquals(1, counts.modelCounts["profile"])
        assertEquals(2, counts.modelCounts["settings"])
        assertEquals(3, counts.otherCount)
        assertNull(counts.modelCounts["pix"], "model names not present in batch must be absent, not 0")
    }

    // ─── ObscuraConfig conversationModel ─────────────────────────────────────

    @Test
    fun `ObscuraConfig conversationModel defaults to null`() {
        val config = ObscuraConfig("https://example.com")
        assertNull(config.conversationModel,
            "Default config must not embed any application model name")
    }

    @Test
    fun `ObscuraConfig conversationModel is set by the application, not the kit`() {
        val config = ObscuraConfig(
            apiUrl = "https://example.com",
            conversationModel = "directMessage"
        )
        assertEquals("directMessage", config.conversationModel)
    }

    @Test
    fun `ObscuraConfig conversationModel can be any application-chosen name`() {
        // The kit must accept any string — it must not validate against a known list
        val config = ObscuraConfig(
            apiUrl = "https://example.com",
            conversationModel = "myAppMessage"
        )
        assertEquals("myAppMessage", config.conversationModel)
    }

    // ─── Audience API is model-name agnostic ─────────────────────────────────

    @Test
    fun `ModelConfig Audience is expressed via field names, never hardcoded model names`() {
        // The Conversation audience routes via a field value (a "userId_userId" string)
        // that it reads from the entry. The model name is declared by the application,
        // not embedded in the kit.
        val config = ModelConfig(
            fields = mapOf(
                "conversationId" to "string",
                "content" to "string",
                "senderUsername" to "string"
            ),
            sync = SyncStrategy.GSET,
            audience = com.obscura.kit.orm.Audience.Conversation(conversationField = "conversationId")
        )
        // The audience does not know or care what the model is called
        val audience = config.audience as com.obscura.kit.orm.Audience.Conversation
        assertEquals("conversationId", audience.conversationField)
    }

    // ─── Canonical conversation id (SPEC §1.3) ─────────────────────────────────

    /**
     * The id must be identical no matter which participant composes the write — that is the
     * entire reason it is sorted. Without this, Alice and Bob would address the *same* 1:1
     * conversation by two different ids, and a reply or read receipt would fail to resolve
     * back to it.
     */
    @Test
    fun `canonical conversation id is order-independent`() {
        val fromAlice = com.obscura.kit.orm.Audience.canonicalConversationId("uAlice", "uBob")
        val fromBob = com.obscura.kit.orm.Audience.canonicalConversationId("uBob", "uAlice")

        assertEquals(fromAlice, fromBob, "both participants must derive the same conversation id")
        assertEquals("uAlice_uBob", fromAlice, "sorted lexicographically, joined with one underscore")
    }

    /**
     * SyncManager resolves a Conversation audience by splitting on `_` and requiring exactly
     * two non-blank parts — so what send() builds has to survive that parse. These are inverses;
     * a change to either without the other silently produces DIRECT_ROUTING_UNRESOLVED.
     */
    @Test
    fun `canonical conversation id parses back to exactly two participants`() {
        val convId = com.obscura.kit.orm.Audience.canonicalConversationId("uMe", "uB")

        val participants = convId.split("_").filter { it.isNotBlank() }
        assertEquals(2, participants.size, "must resolve to exactly two participants")
        assertTrue(participants.containsAll(listOf("uMe", "uB")))
    }
}
