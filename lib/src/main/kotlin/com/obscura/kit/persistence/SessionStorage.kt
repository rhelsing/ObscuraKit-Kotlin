package com.obscura.kit.persistence

/**
 * A typed, versioned snapshot of all session-level state persisted by [ObscuraClient].
 *
 * Using a typed snapshot instead of a raw `Map<String, Any?>` means:
 * - Fields are explicit and IDE-navigable; no silent key typos.
 * - [version] enables forward-compatible schema evolution: readers should
 *   check the version and ignore unknown fields or apply migration logic.
 *
 * @property version Schema version for forward compatibility. Currently 1.
 *   Increment when adding required fields; keep optional fields backwards-
 *   compatible so old snapshots can be loaded by newer code.
 * @property authToken Current JWT access token, or null if not logged in.
 * @property refreshToken Current JWT refresh token, or null if not logged in.
 * @property userId Authenticated user ID, or null.
 * @property username Authenticated username, or null.
 * @property deviceId Registered device ID, or null.
 * @property identityKeyPair Base64-encoded serialised Signal identity key pair, or null.
 * @property registrationId Signal protocol registration ID, or null.
 */
data class SessionSnapshot(
    val version: Int = CURRENT_VERSION,
    val authToken: String? = null,
    val refreshToken: String? = null,
    val userId: String? = null,
    val username: String? = null,
    val deviceId: String? = null,
    val identityKeyPair: String? = null,
    val registrationId: Int? = null,
) {
    companion object {
        const val CURRENT_VERSION: Int = 1

        /**
         * Convert a legacy untyped map (from an older [SessionStorage] impl) to a
         * [SessionSnapshot]. Missing keys are mapped to their null defaults so old
         * snapshots load cleanly.
         */
        fun fromMap(map: Map<String, Any?>): SessionSnapshot = SessionSnapshot(
            version = (map["version"] as? Int) ?: CURRENT_VERSION,
            authToken = map["authToken"] as? String,
            refreshToken = map["refreshToken"] as? String,
            userId = map["userId"] as? String,
            username = map["username"] as? String,
            deviceId = map["deviceId"] as? String,
            identityKeyPair = map["identityKeyPair"] as? String,
            registrationId = (map["registrationId"] as? Number)?.toInt(),
        )
    }

    /** Convert to a raw map for [SessionStorage] implementations that still use the map API. */
    fun toMap(): Map<String, Any?> = buildMap {
        put("version", version)
        put("authToken", authToken)
        put("refreshToken", refreshToken)
        put("userId", userId)
        put("username", username)
        put("deviceId", deviceId)
        put("identityKeyPair", identityKeyPair)
        put("registrationId", registrationId)
    }
}

/**
 * Kit-owned session persistence. Bridge provides platform-specific implementation.
 * Default: no-op (for JVM tests). Android bridge provides SharedPreferences impl.
 *
 * Contract: [save] REPLACES the entire stored blob with `data` (matches the Swift
 * kit's UserDefaults impl, which stores one dict under one key). Callers that only
 * touch part of the session (e.g. [ObscuraClient.persistSession]) load-merge-save
 * so nothing is dropped, so implementations must NOT try to patch individual keys —
 * just persist exactly `data` and drop anything not in it. [load] returns the whole
 * blob or null if nothing is stored.
 *
 * Prefer the typed [saveSnapshot]/[loadSnapshot] helpers over the raw [save]/[load]
 * map API — they guarantee schema versioning and eliminate string-key typos.
 */
interface SessionStorage {
    fun save(data: Map<String, Any?>)
    fun load(): Map<String, Any?>?
    fun clear()

    /** Save a typed, versioned session snapshot. Default impl delegates to [save]. */
    fun saveSnapshot(snapshot: SessionSnapshot) = save(snapshot.toMap())

    /**
     * Load a typed, versioned session snapshot.
     * Returns null if nothing is stored; wraps legacy map results in [SessionSnapshot.fromMap].
     */
    fun loadSnapshot(): SessionSnapshot? = load()?.let { SessionSnapshot.fromMap(it) }
}

/** No-op implementation for JVM tests */
object NoOpSessionStorage : SessionStorage {
    override fun save(data: Map<String, Any?>) {}
    override fun load(): Map<String, Any?>? = null
    override fun clear() {}
}
