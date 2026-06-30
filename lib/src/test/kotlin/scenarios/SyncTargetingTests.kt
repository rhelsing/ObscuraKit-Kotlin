package scenarios

import com.obscura.kit.orm.Model
import com.obscura.kit.orm.ModelConfig
import com.obscura.kit.orm.OrmEntry
import com.obscura.kit.orm.SyncManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [SyncManager] delivery targeting — no network, no Signal.
 *
 * These nail down the confidentiality boundary: which devices a model entry is actually
 * sent to. Regression guard for the leak where 1:1 directMessage / pix were broadcast to
 * ALL friends (so a mutual friend C saw A↔B's conversation).
 *
 * Topology: self (userId "uMe", device "selfDev") is friends with three people:
 *   alice  → userId "uA", device "aDev"
 *   bob    → userId "uB", device "bDev"
 *   carol  → userId "uC", device "cDev"
 */
class SyncTargetingTests {

    private val selfUserId = "uMe"

    private val devicesByUserId = mapOf(
        "uMe" to listOf("selfDev"),
        "uA" to listOf("aDev"),
        "uB" to listOf("bDev"),
        "uC" to listOf("cDev"),
    )
    private val devicesByUsername = mapOf(
        "alice" to listOf("aDev"),
        "bob" to listOf("bDev"),
        "carol" to listOf("cDev"),
    )

    /** Build a SyncManager wired to the test topology; records every queued target device. */
    private fun newSyncManager(recorded: MutableList<String>): SyncManager =
        SyncManager().apply {
            getSelfSyncTargets = { listOf("selfDev") }
            getFriendTargets = { listOf("aDev", "bDev", "cDev") }
            getDevicesForUsername = { username -> devicesByUsername[username] ?: emptyList() }
            getDevicesForUserId = { userId -> devicesByUserId[userId] ?: emptyList() }
            queueModelSync = { targetDeviceId, _ -> recorded.add(targetDeviceId) }
            flushQueue = { }
        }

    /** Canonical 1:1 id — mirrors JS conversationId(myUserId, friendUserId). */
    private fun conversationId(a: String, b: String) = listOf(a, b).sorted().joinToString("_")

    private fun entry(data: Map<String, Any?>) =
        OrmEntry(id = "e1", data = data, timestamp = 1L, authorDeviceId = "selfDev")

    @Test
    fun `directMessage goes only to the two participants, never to other friends`() = runBlocking {
        val recorded = mutableListOf<String>()
        val sm = newSyncManager(recorded)
        val dm = Model("directMessage", ModelConfig(sync = "gset"))

        sm.broadcast(dm, entry(mapOf(
            "conversationId" to conversationId(selfUserId, "uB"), // me ↔ bob
            "content" to "secret",
            "senderUsername" to "me",
        )))

        assertEquals(setOf("selfDev", "bDev"), recorded.toSet(),
            "DM must reach only self + bob's devices")
        assert("cDev" !in recorded) { "LEAK: carol received a DM she is not part of" }
        assert("aDev" !in recorded) { "LEAK: alice received a DM she is not part of" }
    }

    @Test
    fun `pix goes only to its recipient, never to other friends`() = runBlocking {
        val recorded = mutableListOf<String>()
        val sm = newSyncManager(recorded)
        val pix = Model("pix", ModelConfig(sync = "lww"))

        sm.broadcast(pix, entry(mapOf(
            "recipientUsername" to "alice",
            "senderUsername" to "me",
            "mediaRef" to "ref-1",
        )))

        assertEquals(setOf("selfDev", "aDev"), recorded.toSet(),
            "pix must reach only self + alice's devices")
        assert("bDev" !in recorded) { "LEAK: bob received a pix meant for alice" }
        assert("cDev" !in recorded) { "LEAK: carol received a pix meant for alice" }
    }

    @Test
    fun `story still broadcasts to all friends`() = runBlocking {
        val recorded = mutableListOf<String>()
        val sm = newSyncManager(recorded)
        val story = Model("story", ModelConfig(sync = "gset", ttl = "24h"))

        sm.broadcast(story, entry(mapOf("content" to "hi all", "authorUsername" to "me")))

        assertEquals(setOf("selfDev", "aDev", "bDev", "cDev"), recorded.toSet(),
            "story is intentionally public to all friends")
    }

    @Test
    fun `profile still broadcasts to all friends`() = runBlocking {
        val recorded = mutableListOf<String>()
        val sm = newSyncManager(recorded)
        val profile = Model("profile", ModelConfig(sync = "lww"))

        sm.broadcast(profile, entry(mapOf("displayName" to "Me", "bio" to "hi")))

        assertEquals(setOf("selfDev", "aDev", "bDev", "cDev"), recorded.toSet(),
            "profile is intentionally visible to all friends")
    }

    @Test
    fun `private settings goes only to own devices`() = runBlocking {
        val recorded = mutableListOf<String>()
        val sm = newSyncManager(recorded)
        val settings = Model("settings", ModelConfig(sync = "lww", private = true))

        sm.broadcast(settings, entry(mapOf("theme" to "dark", "notificationsEnabled" to true)))

        assertEquals(setOf("selfDev"), recorded.toSet(),
            "private model must never leave own devices")
    }

    @Test
    fun `non 1to1 conversationId falls through to broadcast (no accidental scoping)`() = runBlocking {
        val recorded = mutableListOf<String>()
        val sm = newSyncManager(recorded)
        val dm = Model("directMessage", ModelConfig(sync = "gset"))

        // A malformed / multi-party id (not exactly two parts) must not silently drop recipients.
        sm.broadcast(dm, entry(mapOf(
            "conversationId" to "uMe_uB_uC",
            "content" to "x",
            "senderUsername" to "me",
        )))

        assertEquals(setOf("selfDev", "aDev", "bDev", "cDev"), recorded.toSet(),
            "unrecognized conversationId shape falls back to all-friends, not silent loss")
    }
}
