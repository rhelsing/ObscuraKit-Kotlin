package scenarios

import com.obscura.kit.newInMemoryStore
import com.obscura.kit.orm.OrmEntry
import com.obscura.kit.orm.crdt.LWWMap
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * View-once (pix) regression guard. Mirrors iOS PixViewOnceTests.
 *
 * "View once" is enforced in the *app* UI (it hides any pix whose `viewedAt`
 * is set). The kit's job is only the data underneath it:
 *   1. viewing stamps `viewedAt` via an LWW upsert WITHOUT dropping the other
 *      pix fields, and
 *   2. that viewed-receipt merges back to the sender's copy (LWW).
 *
 * So an "unlimited views" bug is NOT in the kit — these tests pin the kit
 * behavior the app depends on, isolating the defect to the app/bridge layer
 * (the `viewedAt` upsert not firing, or the list not re-filtering).
 */
class PixViewOnceTests {

    private fun entry(id: String, data: Map<String, Any?>, timestamp: Long) =
        OrmEntry(id = id, data = data, timestamp = timestamp, authorDeviceId = "dev")

    /** Viewing a pix stamps `viewedAt` via LWW upsert, preserving every other field. */
    @Test
    fun `viewing stamps viewedAt preserving other fields`() = runTest {
        val pix = LWWMap(newInMemoryStore(), "pix")

        val base = mapOf(
            "conversationId" to "uA_uB", "recipientUsername" to "bob",
            "senderUsername" to "alice", "mediaRef" to "att1",
            "contentKey" to "k", "nonce" to "n", "displayDuration" to 5,
        )
        pix.set(entry("pix_1", base, timestamp = 1000))

        // Fresh pix → no viewedAt → app treats it as "unopened" / viewable.
        assertNull(pix.get("pix_1")?.data?.get("viewedAt"), "a fresh pix must have no viewedAt")

        // View it: upsert the same id with viewedAt at a newer timestamp (LWW wins).
        pix.set(entry("pix_1", base + ("viewedAt" to 1_700_000_000_000L), timestamp = 2000))

        val after = pix.get("pix_1")
        assertNotNull(after?.data?.get("viewedAt"), "viewedAt must be set after viewing — the view-once flag")
        assertEquals("alice", after?.data?.get("senderUsername"), "other fields must survive the upsert")
        assertEquals("att1", after?.data?.get("mediaRef"))
    }

    /** The viewed-receipt (recipient stamps viewedAt) merges back onto the sender's copy. */
    @Test
    fun `viewed receipt merges back to sender via LWW`() = runTest {
        val senderCopy = LWWMap(newInMemoryStore(), "pix")

        // Sender's own copy: created, not yet viewed.
        senderCopy.set(entry("pix_2",
            mapOf("conversationId" to "uA_uB", "senderUsername" to "alice", "mediaRef" to "att2"),
            timestamp = 1000))

        // Recipient's viewed version arrives via sync (newer timestamp).
        senderCopy.merge(listOf(entry("pix_2",
            mapOf("conversationId" to "uA_uB", "senderUsername" to "alice", "mediaRef" to "att2",
                  "viewedAt" to 1_700_000_000_000L),
            timestamp = 2000)))

        assertNotNull(senderCopy.get("pix_2")?.data?.get("viewedAt"),
            "sender must see viewedAt once the receipt merges (LWW)")
    }

    /**
     * LWW must NOT let a stale (older-timestamp) write clobber a newer viewedAt —
     * otherwise a late-arriving un-viewed copy could "un-view" a pix.
     */
    @Test
    fun `a stale un-viewed write cannot clear viewedAt`() = runTest {
        val pix = LWWMap(newInMemoryStore(), "pix")

        pix.set(entry("pix_3",
            mapOf("conversationId" to "uA_uB", "senderUsername" to "alice", "viewedAt" to 1_700_000_000_000L),
            timestamp = 5000))

        // An older, un-viewed version arrives late — must lose to LWW.
        pix.merge(listOf(entry("pix_3",
            mapOf("conversationId" to "uA_uB", "senderUsername" to "alice"),
            timestamp = 1000)))

        assertNotNull(pix.get("pix_3")?.data?.get("viewedAt"),
            "a stale un-viewed write must not clear viewedAt")
    }
}
