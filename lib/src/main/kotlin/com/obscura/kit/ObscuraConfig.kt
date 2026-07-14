package com.obscura.kit

data class ObscuraConfig(
    val apiUrl: String,
    /**
     * Not used — the gateway WebSocket URL is derived automatically from [apiUrl]
     * by replacing the scheme (https→wss, http→ws). This field is kept for binary
     * compatibility with existing callers but has no effect.
     */
    @Deprecated("gatewayUrl is not used; the gateway URL is derived from apiUrl automatically")
    val gatewayUrl: String? = null,
    val deviceName: String = "Kotlin Client",
    /**
     * Path for the SQLite database file. `null` (default) uses an in-memory database,
     * which is always safe for tests. A file path creates an on-disk database.
     *
     * **Security note:** The JVM `JdbcSqliteDriver` backed by a file path is
     * **unencrypted**. For production Android use, pass an encrypted
     * `AndroidSqliteDriver` (e.g. SQLCipher) instead and keep `databasePath = null`.
     * Set [allowUnencryptedDatabase] to `true` to suppress the startup warning when
     * an intentionally unencrypted file database is acceptable (e.g. a debug build).
     */
    val databasePath: String? = null,
    // Client-side pacing for prod auth rate limits; env-overridable so CI can
    // zero it against a rate-limit-disabled local container (parity with iOS)
    val authRateLimitDelayMs: Long = System.getenv("AUTH_REQUEST_DELAY_MS")?.toLongOrNull() ?: 500L,
    val enableRecoveryPhrase: Boolean = false, // opt-in: enables BIP39 recovery, remote device revocation, encrypted backups
    /**
     * Optional: when set, [com.obscura.kit.ObscuraClient.send] uses this ORM model name
     * for outbound messages instead of the legacy TEXT wire format, and incoming
     * MODEL_SYNC entries for this model are additionally mirrored into the
     * [com.obscura.kit.ObscuraClient.conversations] StateFlow for chat-UI compatibility.
     *
     * The kit never sniffs model names in production code; the application declares
     * the model name here so both routing directions use the same value without
     * embedding it in the kit source.
     */
    val conversationModel: String? = null,
    /**
     * Set to `true` to suppress the startup warning emitted when [databasePath] is
     * set (file-backed, unencrypted JdbcSqliteDriver). Has no effect when
     * [databasePath] is null (in-memory).
     */
    val allowUnencryptedDatabase: Boolean = false,
) {
    init {
        // Plain HTTP is allowed only for loopback (local containerized server
        // in tests/CI). Parse the host exactly — a prefix check on the raw
        // string would let "http://localhost@evil.com" or
        // "http://localhost.evil.com" tunnel cleartext (incl. the auth token)
        // to a remote host, since APIClient enables CLEARTEXT for any http:// URL.
        val host = runCatching { java.net.URI(apiUrl).host }.getOrNull()
        val isLoopback = apiUrl.startsWith("http://") && (host == "localhost" || host == "127.0.0.1" || host == "[::1]")
        require(apiUrl.startsWith("https://") || isLoopback) { "API URL must use HTTPS: $apiUrl" }
    }
}
