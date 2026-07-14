package com.obscura.kit

data class ObscuraConfig(
    val apiUrl: String,
    val gatewayUrl: String? = null,
    val deviceName: String = "Kotlin Client",
    val databasePath: String? = null, // null = in-memory (tests), path = file-backed (production)
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
