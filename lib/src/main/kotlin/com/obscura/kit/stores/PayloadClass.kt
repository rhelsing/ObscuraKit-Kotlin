package com.obscura.kit.stores

import obscura.client.v1.Client.ClientMessage.PayloadCase

/**
 * What a payload arm is allowed to do on receipt (`obscura-proto/KIT_API.md` §4).
 *
 * Every arm MUST be classified, because **the classification is what makes SPEC §0.9 checkable
 * rather than aspirational**. "Never ack before persisting" is not a rule the code can follow until
 * something says, per arm, *what persisting means for this one*.
 */
internal enum class PayloadClass {
    /** Application content. Goes in the inbox; the app drains it. Ack only after the row commits. */
    INBOXED,

    /** Mutates kit-owned state (friend graph, devices, sessions). Ack only after the kit's write. */
    KIT_INTERNAL,

    /** Ephemeral by design, no durable delivery guarantee. MAY be acked without persistence. */
    DROPPABLE,

    /**
     * Declared protocol arms with no receive contract. They are diagnosed, dropped, and acked so
     * an unsupported arm cannot wedge the queue. They remain distinct from unknown future arms,
     * which are inboxed for forward compatibility.
     */
    UNIMPLEMENTED,
}

/**
 * The §4 classification table, as code.
 *
 * An arm this kit has never heard of is **inboxed unparsed** (§4.1). Leaving it
 * unacked would turn an unsupported sender into an unbounded retry:
 *
 * > any authenticated user may send to any device → a never-acked message is never deleted and
 * > redelivers forever → the server's queue caps at 1000 per device and evicts **oldest-first,
 * > silently** → a stranger looping unknown arms pushes the recipient's real undelivered mail off
 * > the back of the queue.
 *
 * Refusing to ack is reserved for transient local failures that can succeed on a later attempt.
 */
internal fun classify(arm: PayloadCase): PayloadClass = when (arm) {
    // The app's entire data path.
    PayloadCase.MODEL_SYNC -> PayloadClass.INBOXED

    // Kit-owned state, all with live handlers in ObscuraClient.routeMessage.
    PayloadCase.FRIEND_REQUEST,
    PayloadCase.FRIEND_RESPONSE,
    PayloadCase.DEVICE_ANNOUNCE,
    PayloadCase.DEVICE_LINK_APPROVAL,
    PayloadCase.SESSION_RESET,
    PayloadCase.SYNC_BLOB,
    PayloadCase.SENT_SYNC -> PayloadClass.KIT_INTERNAL

    // Typing indicators. client.proto says "in-memory only", and §4 permits acking these without
    // persistence — the ONLY class for which that is allowed.
    PayloadCase.MODEL_SIGNAL -> PayloadClass.DROPPABLE

    // Compatibility receive path.
    PayloadCase.TEXT -> PayloadClass.KIT_INTERNAL

    // Public senders still emit these arms, so dropping them would destroy the only copy of the
    // attachment key. Remove the senders and receive classification together.
    PayloadCase.CONTENT_REFERENCE,
    PayloadCase.CHUNKED_CONTENT_REFERENCE -> PayloadClass.INBOXED

    // Declared but unsupported. DEVICE_RECOVERY_ANNOUNCE is gated behind the default-off recovery
    // feature; the remaining arms have no live sender in this kit.
    PayloadCase.DEVICE_RECOVERY_ANNOUNCE,
    PayloadCase.HISTORY_CHUNK,
    PayloadCase.SYNC_REQUEST,
    PayloadCase.FRIEND_SYNC,
    PayloadCase.SETTINGS_SYNC,
    PayloadCase.READ_SYNC -> PayloadClass.UNIMPLEMENTED

    // Unknown or future arm, and PAYLOAD_NOT_SET. Inbox it unparsed rather than destroy it.
    else -> PayloadClass.INBOXED
}
