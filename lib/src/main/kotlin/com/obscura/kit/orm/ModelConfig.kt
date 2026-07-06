package com.obscura.kit.orm

/** How a model's field values are merged across devices. */
enum class SyncStrategy {
    /** Grow-only set: entries are only added (append-only feeds, messages). */
    GSET,
    /** Last-writer-wins map: entries can be updated and deleted (profiles, settings). */
    LWW;

    companion object {
        fun fromWire(value: String): SyncStrategy = when (value.lowercase()) {
            "gset" -> GSET
            "lww" -> LWW
            else -> throw IllegalArgumentException(
                "Unknown sync strategy '$value' (expected 'gset' or 'lww')")
        }
    }
}

/**
 * Declares who a model's writes are delivered to.
 *
 * Chosen per model in the schema. For the 1:1 variants the app names *its own*
 * field that carries the recipient, so the kit never hardcodes application
 * field names when routing.
 */
sealed class Audience {
    /** Broadcast to every accepted friend's devices (plus own devices). The default. */
    object Friends : Audience()

    /** Deliver only to the local user's own devices (e.g. settings, drafts). */
    object Self : Audience()

    /**
     * 1:1 delivery to the single user named in [usernameField] of the entry.
     * Fails loud (never broadcasts) if that field is missing or blank.
     */
    data class Recipient(val usernameField: String) : Audience()

    /**
     * 1:1 delivery to both parties of a canonical "userIdA_userIdB" value held
     * in [conversationField] of the entry. Fails loud if that field is missing
     * or does not resolve to exactly two participants.
     */
    data class Conversation(val conversationField: String) : Audience()

    companion object {
        /** Parse the JSON `audience` object from the schema. Null → [Friends]. */
        fun fromWire(kind: String?, field: String?): Audience = when (kind) {
            null, "friends" -> Friends
            "self" -> Self
            "recipient" -> Recipient(requireField(field, "recipient"))
            "conversation" -> Conversation(requireField(field, "conversation"))
            else -> throw IllegalArgumentException("Unknown audience kind '$kind'")
        }

        private fun requireField(field: String?, kind: String): String =
            field?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("audience '$kind' requires a 'field' naming the recipient field")
    }
}

/**
 * Configuration for a model definition. Mirrors the JS schema config object and
 * is built from JSON by [com.obscura.kit.ObscuraClient.defineModelsFromJson].
 */
data class ModelConfig(
    /** field name -> declared type ("string", "number?", "boolean", "timestamp"). */
    val fields: Map<String, String> = emptyMap(),
    val sync: SyncStrategy = SyncStrategy.GSET,
    val ttl: String? = null,
    val belongsTo: List<String> = emptyList(),
    val hasMany: List<String> = emptyList(),
    val audience: Audience = Audience.Friends,
) {
    init {
        // Fail loud at define time on an unknown declared field type, rather than
        // silently accepting it and skipping validation on every write.
        for ((field, type) in fields) FieldTypes.parse(field, type)
    }
}

/** Parsing + validation for the string field-type declarations. */
object FieldTypes {
    private val known = setOf("string", "number", "boolean", "timestamp")

    /** @return (baseType, nullable). Throws [IllegalArgumentException] on an unknown type. */
    fun parse(field: String, declared: String): Pair<String, Boolean> {
        val nullable = declared.endsWith("?")
        val base = declared.removeSuffix("?")
        if (base !in known) throw IllegalArgumentException(
            "Field '$field' has unknown type '$declared' (expected one of $known, optionally suffixed with '?')")
        return base to nullable
    }
}
