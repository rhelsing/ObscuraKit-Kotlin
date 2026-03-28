package com.obscura.kit

/**
 * Structured logger for security-relevant events.
 * Does NOT expose key material, message content, or tokens.
 * Set ObscuraClient.logger to receive events.
 */
interface ObscuraLogger {
    fun decryptFailed(sourceUserId: String, reason: String)
    fun tokenRefreshFailed(attempt: Int, reason: String)
    fun preKeyReplenishFailed(reason: String)
    fun identityChanged(address: String)
    fun wsFrameParseError(reason: String)
    fun wsReconnecting(attempt: Int)
}

/** Default no-op logger. Replace for production monitoring. */
object NoOpLogger : ObscuraLogger {
    override fun decryptFailed(sourceUserId: String, reason: String) {}
    override fun tokenRefreshFailed(attempt: Int, reason: String) {}
    override fun preKeyReplenishFailed(reason: String) {}
    override fun identityChanged(address: String) {}
    override fun wsFrameParseError(reason: String) {}
    override fun wsReconnecting(attempt: Int) {}
}
