package com.obscura.kit.managers

/**
 * Shared mutable state for the client session.
 * Owned by ObscuraClient, passed to each manager.
 * Managers read/write these fields directly.
 */
class ClientSession {
    var userId: String? = null
    var deviceId: String? = null
    var username: String? = null
    var refreshToken: String? = null
    var registrationId: Int = 0
    var recoveryPhrase: String? = null
    var recoveryPublicKey: ByteArray? = null
}
