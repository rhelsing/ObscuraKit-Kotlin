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
     * Classified in §4 as kit-internal, and implemented by NEITHER kit.
     *
     * Kept distinct from [KIT_INTERNAL] on purpose. These arms are acked and destroyed today, and
     * the temptation is to sweep them into the inbox with the unknown arms — but §4.2 is explicit
     * that the fallback keys on **absence from the classification table**, not absence from the
     * handler. If unimplemented kit work landed in the inbox, the inbox would become the place it
     * goes to be forgotten, and §4's classification would stop meaning anything.
     *
     * So they keep today's behaviour — dropped, then acked — but *loudly*, and each has a recorded
     * resolution in §4.2: four are Phase 3 deletions, and `DEVICE_RECOVERY_ANNOUNCE` is a
     * deliberate deferral (it cannot fire — `enableRecoveryPhrase` defaults to false and obscura-pix
     * ships no recovery UI).
     *
     * `FRIEND_SYNC` joined them when its handler was deleted: it was classified KIT_INTERNAL and had
     * a live handler, but `FriendSync` carries no `user_id`, so the handler keyed the friend record
     * on `sourceUserId` — which its own guard had just proven was the RECEIVER's own id. It put the
     * user in their own friends list. Sender and receiver are both gone; the proto arm is not.
     */
    UNIMPLEMENTED,
}

/**
 * The §4 classification table, as code.
 *
 * `else` is the load-bearing branch: an arm this kit has never heard of is **inboxed unparsed**
 * (§4.1), not left unacked. That choice is not a coin-flip between two reasonable options —
 * declining to ack composes with the server's eviction into a remote wipe:
 *
 * > any authenticated user may send to any device → a never-acked message is never deleted and
 * > redelivers forever → the server's queue caps at 1000 per device and evicts **oldest-first,
 * > silently** → a stranger looping unknown arms pushes the recipient's real undelivered mail off
 * > the back of the queue.
 *
 * Declining to ack is right when the failure is *ours and transient* — disk full, a migration not
 * yet applied, something that will work next launch. An unknown arm is neither, so the retry is
 * unbounded by construction.
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

    // Legacy. Deleted by RESET.md; still routed while it exists.
    PayloadCase.TEXT -> PayloadClass.KIT_INTERNAL

    // Attachment references. §4 classifies these INBOXED and §4.3 resolves them as Phase 3
    // deletions — but the deletion has NOT happened yet: both arms are still in client.proto and
    // `ObscuraClient.sendEncryptedAttachment` is still a public sender. Classifying them
    // UNIMPLEMENTED while a live public API can send them means the kit uploads the blob, ships the
    // AES key over Signal, and the RECEIVER acks and destroys the key. So they follow §4's
    // normative table until the arms and their senders are deleted together, because inboxing an
    // arm nobody sends costs nothing and dropping one somebody can send costs the message.
    PayloadCase.CONTENT_REFERENCE,
    PayloadCase.CHUNKED_CONTENT_REFERENCE -> PayloadClass.INBOXED

    // Classified, unimplemented — see the enum. Not inbox fodder. None of these has a live sender
    // in this kit: DEVICE_RECOVERY_ANNOUNCE is gated behind a default-off flag, and the rest have no
    // sender at all (§4.3).
    //
    // SYNC_REQUEST and FRIEND_SYNC only became senderless when their senders were deleted. The
    // earlier wording — "the other three have no sender anywhere" — was already false for
    // SYNC_REQUEST: `ClientSyncManager.requestSync` was public on the facade and shipped one to every
    // own device, costing a Signal encrypt and an HTTP round trip so the receiver could drop it here.
    PayloadCase.DEVICE_RECOVERY_ANNOUNCE,
    PayloadCase.HISTORY_CHUNK,
    PayloadCase.SYNC_REQUEST,
    PayloadCase.FRIEND_SYNC,
    PayloadCase.SETTINGS_SYNC,
    PayloadCase.READ_SYNC -> PayloadClass.UNIMPLEMENTED

    // Unknown or future arm, and PAYLOAD_NOT_SET. Inbox it unparsed rather than destroy it.
    else -> PayloadClass.INBOXED
}
