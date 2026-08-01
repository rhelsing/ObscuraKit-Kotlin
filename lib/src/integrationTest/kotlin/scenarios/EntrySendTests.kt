package scenarios

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `send` — the kit's explicit-audience send (`obscura-proto/KIT_API.md` §5).
 *
 * 1. the sending device must be excluded from its own fan-out;
 * 2. the sender gets no inbox row, so the app must write its own outgoing entry.
 */
class EntrySendTests {

    @Test
    fun `a sent entry arrives in the recipient's inbox with the payload untouched`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("send_a")
        val bob = registerAndConnect("send_b")
        becomeFriends(alice, bob)

        val payload = """{"content":"opaque to the kit","expiresAt":123}""".toByteArray()
        alice.send(
            recipientUserIds = listOf(bob.userId!!),
            modelKey = "directMessage",
            entryId = "dm_1",
            op = "CREATE",
            payload = payload,
        )
        delay(3000)

        val rows = bob.inbox.peek()
        assertEquals(1, rows.size)
        assertEquals("directMessage", rows[0].modelKey)
        assertEquals("dm_1", rows[0].entryId)
        assertEquals("CREATE", rows[0].op)
        assertArrayEquals(payload, rows[0].payload,
            "the kit never opens the payload, so it must arrive byte-identical")
        assertEquals(alice.userId, rows[0].senderUserId)

        alice.disconnect(); bob.disconnect()
    }

    /**
     * **§5 property 1.** `getSelfSyncTargets()` returns every own device *including this one*, so a
     * send that does not filter would encrypt a copy to itself. The app would then see its own write
     * arrive as an incoming row and have to dedupe it — a whole class of bug for no benefit.
     *
     * A single-device sender is the sharpest version of the test: every own-device target IS this
     * device, so an unfiltered fan-out has nowhere else to go and the echo is unmissable.
     */
    @Test
    fun `the sending device does not receive its own entry`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("selfecho_a")
        val bob = registerAndConnect("selfecho_b")
        becomeFriends(alice, bob)

        assertEquals(0L, alice.inbox.depth())

        alice.send(
            recipientUserIds = listOf(bob.userId!!),
            modelKey = "story",
            entryId = "story_1",
            payload = """{"content":"mine"}""".toByteArray(),
        )
        delay(3000)

        assertEquals(0L, alice.inbox.depth(),
            "a device must not receive the entry it just sent — see KIT_API.md §5")
        assertEquals(1L, bob.inbox.depth(), "...while the recipient still gets it")

        alice.disconnect(); bob.disconnect()
    }

    /**
     * **§5 property 2, stated as a consequence rather than a mechanism.** Nothing loops back
     * locally, so `send` alone leaves the sender with no record of what it sent. obscura-pix must
     * write its own outgoing entry to `entries` — one write path in the kit, two in the app.
     *
     * This is the test that makes that explicit, because the alternative design (the kit writes the
     * sender's copy too) is the tempting one and would quietly re-import audience knowledge into the
     * kit: to store it, the kit would have to decide which model and which id, from the payload.
     */
    @Test
    fun `send does not store anything locally — the app owns its own copy`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("owncopy_a")
        val bob = registerAndConnect("owncopy_b")
        becomeFriends(alice, bob)

        alice.send(
            recipientUserIds = listOf(bob.userId!!),
            modelKey = "story",
            entryId = "story_1",
            payload = """{"content":"mine"}""".toByteArray(),
        )
        delay(2000)

        assertEquals(emptyList<Any>(), alice.entries.all("story"),
            "the kit stores nothing on send; the app writes its own outgoing entry")

        // ...and doing so is a one-liner, which is the point: the app already has the data.
        alice.entries.put("story", com.obscura.kit.stores.StoredEntry(
            id = "story_1", data = """{"content":"mine"}""",
            sentAt = System.currentTimeMillis(), authorDeviceId = alice.deviceId!!,
        ))
        assertEquals(1, alice.entries.all("story").size)

        alice.disconnect(); bob.disconnect()
    }

    /**
     * **One unreachable recipient must not cost the others, or the sender's own devices.**
     *
     * `sendToAllDevices` throws for a userId with no registered devices, so an all-or-nothing loop
     * would abandon recipients 2..N on the first failure — and skip the own-device self-sync that
     * runs after them, meaning the user's other devices silently never receive something they wrote.
     *
     * A userId that was never registered is the sharpest available stand-in for "unreachable": the
     * server has no devices for it, which is exactly the NoDevices path.
     */
    @Test
    fun `an unreachable recipient does not stop delivery to the others`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("partial_a")
        val bob = registerAndConnect("partial_b")
        becomeFriends(alice, bob)

        val ghost = java.util.UUID.randomUUID().toString()

        alice.send(
            recipientUserIds = listOf(ghost, bob.userId!!),
            modelKey = "story",
            entryId = "story_partial",
            payload = """{"content":"reaches bob anyway"}""".toByteArray(),
        )
        delay(3000)

        assertEquals(1L, bob.inbox.depth(),
            "a failure for the first recipient must not abandon the second")

        alice.disconnect(); bob.disconnect()
    }

    /**
     * A TOTAL failure is different in kind from a partial one and must reach the caller: the app
     * would otherwise believe it sent something that reached nobody.
     */
    @Test
    fun `a send that reaches nobody throws`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("nobody_a")
        val ghost = java.util.UUID.randomUUID().toString()

        assertThrows(com.obscura.kit.ObscuraError.SendFailed::class.java) {
            runBlocking {
                alice.send(
                    recipientUserIds = listOf(ghost),
                    modelKey = "story",
                    entryId = "story_nobody",
                    payload = """{"content":"reaches no one"}""".toByteArray(),
                )
            }
        }

        alice.disconnect()
        Unit
    }

    /**
     * An empty recipient list is "my own devices only", not an error. A self-scoped model wants
     * exactly this, and the kit is not guessing an audience — the caller named one, and it was
     * empty. Failing loud is reserved for an audience the kit was asked to invent (SPEC §1.2).
     */
    @Test
    fun `an empty recipient list is a self-sync, not a failure`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("selfonly_a")

        alice.send(
            recipientUserIds = emptyList(),
            modelKey = "profile",
            entryId = "profile_1",
            payload = """{"displayName":"alice"}""".toByteArray(),
        )

        assertEquals(0L, alice.inbox.depth(), "single device, so there is nowhere to self-sync to")

        alice.disconnect()
    }
}
