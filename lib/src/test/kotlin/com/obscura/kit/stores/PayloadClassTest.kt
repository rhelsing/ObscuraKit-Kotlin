package com.obscura.kit.stores

import obscura.client.v1.Client.ClientMessage.PayloadCase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The §4 classification table.
 *
 * This is the piece that makes SPEC §0.9 checkable rather than aspirational — "never ack before
 * persisting" means nothing until something says, per arm, what persisting means for that arm. So
 * the table itself is worth pinning, and the exhaustiveness test below is the one that matters most:
 * a new arm added to `client.proto` must not be able to slip through unclassified.
 */
class PayloadClassTest {

    /**
     * The expected class of every arm, written out. Also the cross-kit contract: these are the exact
     * classifications `ObscuraKit-swift`'s `PayloadClassTests.testEveryArmHasTheSameClassAsTheKotlinKit`
     * asserts. The kits may differ in how they store a row; they may not differ in whether an arm is
     * stored at all.
     */
    private val expected = mapOf(
        PayloadCase.MODEL_SYNC to PayloadClass.INBOXED,
        PayloadCase.CONTENT_REFERENCE to PayloadClass.INBOXED,
        PayloadCase.CHUNKED_CONTENT_REFERENCE to PayloadClass.INBOXED,
        PayloadCase.FRIEND_REQUEST to PayloadClass.KIT_INTERNAL,
        PayloadCase.FRIEND_RESPONSE to PayloadClass.KIT_INTERNAL,
        PayloadCase.FRIEND_SYNC to PayloadClass.KIT_INTERNAL,
        PayloadCase.DEVICE_ANNOUNCE to PayloadClass.KIT_INTERNAL,
        PayloadCase.DEVICE_LINK_APPROVAL to PayloadClass.KIT_INTERNAL,
        PayloadCase.SESSION_RESET to PayloadClass.KIT_INTERNAL,
        PayloadCase.SYNC_BLOB to PayloadClass.KIT_INTERNAL,
        PayloadCase.SENT_SYNC to PayloadClass.KIT_INTERNAL,
        PayloadCase.TEXT to PayloadClass.KIT_INTERNAL,
        PayloadCase.MODEL_SIGNAL to PayloadClass.DROPPABLE,
        PayloadCase.DEVICE_RECOVERY_ANNOUNCE to PayloadClass.UNIMPLEMENTED,
        PayloadCase.HISTORY_CHUNK to PayloadClass.UNIMPLEMENTED,
        PayloadCase.SYNC_REQUEST to PayloadClass.UNIMPLEMENTED,
        PayloadCase.SETTINGS_SYNC to PayloadClass.UNIMPLEMENTED,
        PayloadCase.READ_SYNC to PayloadClass.UNIMPLEMENTED,
    )

    /**
     * **The load-bearing test.** A new arm in `client.proto` must fail here rather than be discovered
     * later as a message that quietly vanished.
     *
     * > **This replaces a version that could not fail** (2026-07-28). It asserted
     * > `runCatching { classify(it) }.isFailure` was empty for every arm — but `classify` is TOTAL:
     * > it ends in `else -> INBOXED`, so nothing ever throws and the assertion was vacuous. A new
     * > proto arm would have fallen into that `else` with all four tests still green, which is
     * > precisely the outcome the doc comment claimed it prevented. Verified by mutating `classify`
     * > to a constant: this test kept passing.
     * >
     * > Kotlin has no compiler backstop here — `classify` needs its `else`, and `WireCodec.decodeType`
     * > uses `payloadCase.name` — so an explicit table is the only thing that can catch it. (Swift is
     * > protected by an exhaustive switch, by accident rather than by its test.)
     */
    @Test
    fun `every payload arm in the proto has an expected class, and every expectation is a real arm`() {
        val armsInProto = PayloadCase.entries.filter { it != PayloadCase.PAYLOAD_NOT_SET }.toSet()

        val missing = armsInProto - expected.keys
        assertTrue(missing.isEmpty(),
            "new arm(s) in client.proto with no decision about what they may do: $missing")

        val stale = expected.keys - armsInProto
        assertTrue(stale.isEmpty(), "expectation(s) naming an arm that no longer exists: $stale")

        armsInProto.forEach { arm ->
            assertEquals(expected[arm], classify(arm), "$arm is classified differently than expected")
        }
    }

    /**
     * The one decision in §4.1 that was not a coin flip. Declining to ack an arm we do not
     * understand looks conservative and is the opposite: any authenticated user may send to any
     * device, a never-acked message redelivers forever, and the server's queue caps at 1000 per
     * device and evicts **oldest-first, silently**. So refusing to ack unknown arms hands a stranger
     * a remote wipe of the recipient's real undelivered mail.
     */
    @Test
    fun `an unknown arm is inboxed rather than left unacked`() {
        assertEquals(PayloadClass.INBOXED, classify(PayloadCase.PAYLOAD_NOT_SET))
    }

    @Test
    fun `model sync is the app's data path`() {
        assertEquals(PayloadClass.INBOXED, classify(PayloadCase.MODEL_SYNC))
    }

    /**
     * Attachment references follow §4's normative table rather than §4.3's deletion plan, because
     * the deletion has not happened: `ObscuraClient.sendEncryptedAttachment` is still public and
     * still ships an AES key over Signal. Dropping the arm would have the receiver ack and destroy
     * that key. Inboxing an arm nobody sends costs nothing; dropping one somebody CAN send costs the
     * message.
     */
    @Test
    fun `attachment references are inboxed while a public sender still exists`() {
        assertEquals(PayloadClass.INBOXED, classify(PayloadCase.CONTENT_REFERENCE))
        assertEquals(PayloadClass.INBOXED, classify(PayloadCase.CHUNKED_CONTENT_REFERENCE))
    }

    /**
     * The only class permitted to ack without persisting, and it is a closed list. If this ever
     * grows, the growth is a decision about durability, not a routing tweak.
     */
    @Test
    fun `typing indicators are the only droppable arm`() {
        val droppable = PayloadCase.entries.filter { classify(it) == PayloadClass.DROPPABLE }

        assertEquals(listOf(PayloadCase.MODEL_SIGNAL), droppable)
    }

    /**
     * §4.2's distinction, and the reason [PayloadClass.UNIMPLEMENTED] exists at all: the inbox
     * fallback keys on absence from the **classification table**, not absence from the handler. If
     * these landed in the inbox it would become the place unimplemented kit work goes to be
     * forgotten, and the classification would stop meaning anything.
     */
    @Test
    fun `classified-but-unimplemented arms are not swept into the inbox`() {
        val unimplemented = listOf(
            PayloadCase.DEVICE_RECOVERY_ANNOUNCE,
            PayloadCase.HISTORY_CHUNK,
            PayloadCase.SYNC_REQUEST,
            PayloadCase.SETTINGS_SYNC,
            PayloadCase.READ_SYNC,
        )

        unimplemented.forEach {
            assertEquals(PayloadClass.UNIMPLEMENTED, classify(it),
                "$it is classified in §4 but implemented by neither kit — it must not become inbox fodder")
        }
    }

    /** Kit-owned state stays kit-owned; none of it may reach an app-readable inbox. */
    @Test
    fun `friend and device arms are kit-internal`() {
        listOf(
            PayloadCase.FRIEND_REQUEST, PayloadCase.FRIEND_RESPONSE, PayloadCase.FRIEND_SYNC,
            PayloadCase.DEVICE_ANNOUNCE, PayloadCase.DEVICE_LINK_APPROVAL,
            PayloadCase.SESSION_RESET, PayloadCase.SYNC_BLOB, PayloadCase.SENT_SYNC,
        ).forEach {
            assertEquals(PayloadClass.KIT_INTERNAL, classify(it), "$it mutates kit-owned state")
        }
    }
}
