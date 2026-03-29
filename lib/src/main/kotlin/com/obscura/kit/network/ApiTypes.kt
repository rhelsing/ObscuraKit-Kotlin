package com.obscura.kit.network

import org.json.JSONArray
import org.json.JSONObject

// ============================================================
// Signal Key JSON types (used in provision, takeover, replenish)
// ============================================================

data class SignedPreKeyJson(val keyId: Int, val publicKey: String, val signature: String) {
    fun toJson() = JSONObject().apply {
        put("keyId", keyId)
        put("publicKey", publicKey)
        put("signature", signature)
    }
}

data class OneTimePreKeyJson(val keyId: Int, val publicKey: String) {
    fun toJson() = JSONObject().apply {
        put("keyId", keyId)
        put("publicKey", publicKey)
    }
}

fun List<OneTimePreKeyJson>.toJsonArray() = JSONArray(map { it.toJson() })

// ============================================================
// Auth request types
// ============================================================

data class RegisterUserRequest(val username: String, val password: String) {
    fun toJson() = JSONObject().apply {
        put("username", username)
        put("password", password)
    }
}

data class LoginRequest(val username: String, val password: String, val deviceId: String? = null) {
    fun toJson() = JSONObject().apply {
        put("username", username)
        put("password", password)
        if (deviceId != null) put("deviceId", deviceId)
    }
}

data class RefreshTokenRequest(val refreshToken: String) {
    fun toJson() = JSONObject().apply {
        put("refreshToken", refreshToken)
    }
}

data class LogoutRequest(val refreshToken: String) {
    fun toJson() = JSONObject().apply {
        put("refreshToken", refreshToken)
    }
}

// ============================================================
// Device request types
// ============================================================

data class ProvisionDeviceRequest(
    val name: String,
    val identityKey: String,
    val registrationId: Int,
    val signedPreKey: SignedPreKeyJson,
    val oneTimePreKeys: List<OneTimePreKeyJson>
) {
    fun toJson() = JSONObject().apply {
        put("name", name)
        put("identityKey", identityKey)
        put("registrationId", registrationId)
        put("signedPreKey", signedPreKey.toJson())
        put("oneTimePreKeys", oneTimePreKeys.toJsonArray())
    }
}

data class UploadDeviceKeysRequest(
    val identityKey: String,
    val registrationId: Int,
    val signedPreKey: SignedPreKeyJson,
    val oneTimePreKeys: List<OneTimePreKeyJson>
) {
    fun toJson() = JSONObject().apply {
        put("identityKey", identityKey)
        put("registrationId", registrationId)
        put("signedPreKey", signedPreKey.toJson())
        put("oneTimePreKeys", oneTimePreKeys.toJsonArray())
    }
}
