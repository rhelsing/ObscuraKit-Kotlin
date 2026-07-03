package com.obscura.kit

data class ObscuraConfig(
    val apiUrl: String,
    val gatewayUrl: String? = null,
    val deviceName: String = "Kotlin Client",
    val databasePath: String? = null, // null = in-memory (tests), path = file-backed (production)
    // Client-side pacing for prod auth rate limits; env-overridable so CI can
    // zero it against a rate-limit-disabled local container (parity with iOS)
    val authRateLimitDelayMs: Long = System.getenv("AUTH_REQUEST_DELAY_MS")?.toLongOrNull() ?: 500L,
    val enableRecoveryPhrase: Boolean = false // opt-in: enables BIP39 recovery, remote device revocation, encrypted backups
) {
    init {
        // Plain HTTP is allowed only for loopback (local containerized server in tests/CI)
        val isLoopback = apiUrl.startsWith("http://localhost") || apiUrl.startsWith("http://127.0.0.1")
        require(apiUrl.startsWith("https://") || isLoopback) { "API URL must use HTTPS: $apiUrl" }
    }
}
