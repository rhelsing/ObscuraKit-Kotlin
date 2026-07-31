package com.obscura.kit.wire

import obscura.client.v1.Client

/**
 * Internal operation kind for a [ModelSyncData], decoupled from the wire enum
 * so the rest of the kit never touches the proto `OP_*` constants (or the old
 * magic-int convention) directly.
 */
enum class ModelOp {
    CREATE,
    UPDATE,
    DELETE;

    companion object {
        /** Parse an app-facing op string ("CREATE"/"UPDATE"/"DELETE"); anything else → CREATE. */
        fun fromApp(value: String): ModelOp = when (value.uppercase()) {
            "UPDATE" -> UPDATE
            "DELETE" -> DELETE
            else -> CREATE
        }
    }
}

/**
 * Single source of truth for translating between the v2 `client.proto` wire
 * enums and the kit's app-facing forms.
 *
 * Extracted so these mappings live in exactly ONE place. They were previously
 * duplicated across five call sites (op encode/decode, the string-op parse in
 * MessagingManager, the two `TYPE_`-strip sites, and signal-kind encode/decode),
 * which is a drift hazard: the v2 renumbering (`TYPE_`/`OP_` prefixes, moving
 * TEXT/CREATE off wire-0) means a single mis-copied `when` silently breaks
 * cross-platform interop. Pinned by `obscura-proto/conformance/wire.json`
 * (SPEC §3).
 */
object WireCodec {

    // ─── ModelSync.Op ↔ ModelOp ──────────────────────────────────────────────

    fun encodeOp(op: ModelOp): Client.ModelSync.Op = when (op) {
        ModelOp.CREATE -> Client.ModelSync.Op.OP_CREATE
        ModelOp.UPDATE -> Client.ModelSync.Op.OP_UPDATE
        ModelOp.DELETE -> Client.ModelSync.Op.OP_DELETE
    }

    /** `OP_UNSPECIFIED` and any unrecognized value decode to the safe default, CREATE. */
    fun decodeOp(op: Client.ModelSync.Op): ModelOp = when (op) {
        Client.ModelSync.Op.OP_UPDATE -> ModelOp.UPDATE
        Client.ModelSync.Op.OP_DELETE -> ModelOp.DELETE
        else -> ModelOp.CREATE
    }

    // ─── ClientMessage.payload oneof → app string ────────────────────────────

    /**
     * The app-facing message-type string: the set `payload` oneof arm's field
     * name, upper-snake (which the generated [Client.ClientMessage.PayloadCase]
     * already is). An unset payload maps to "".
     */
    fun decodeType(case: Client.ClientMessage.PayloadCase): String =
        if (case == Client.ClientMessage.PayloadCase.PAYLOAD_NOT_SET) "" else case.name

    // ─── SignalKind ↔ app signal name ─────────────────────────────────────────

    fun encodeSignalKind(name: String): Client.SignalKind = when (name) {
        "typing" -> Client.SignalKind.SIGNAL_KIND_TYPING
        "stoppedTyping" -> Client.SignalKind.SIGNAL_KIND_STOPPED_TYPING
        "read" -> Client.SignalKind.SIGNAL_KIND_READ
        else -> Client.SignalKind.SIGNAL_KIND_UNSPECIFIED
    }

    /** @return the internal signal name, or null for UNSPECIFIED/unrecognized (caller ignores). */
    fun decodeSignalKind(kind: Client.SignalKind): String? = when (kind) {
        Client.SignalKind.SIGNAL_KIND_TYPING -> "typing"
        Client.SignalKind.SIGNAL_KIND_STOPPED_TYPING -> "stoppedTyping"
        Client.SignalKind.SIGNAL_KIND_READ -> "read"
        else -> null
    }
}
