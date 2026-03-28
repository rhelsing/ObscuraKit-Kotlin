package com.obscura.kit

data class ObscuraConfig(
    val apiUrl: String,
    val gatewayUrl: String? = null,
    val deviceName: String = "Kotlin Client",
    val databasePath: String? = null // null = in-memory (tests), path = file-backed (production)
) {
    init {
        require(apiUrl.startsWith("https://")) { "API URL must use HTTPS: $apiUrl" }
    }
}
