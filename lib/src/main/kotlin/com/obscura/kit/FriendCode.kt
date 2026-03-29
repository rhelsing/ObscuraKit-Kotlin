package com.obscura.kit

import com.obscura.kit.crypto.toBase64
import com.obscura.kit.crypto.fromBase64
import org.json.JSONObject

/**
 * Friend code: base64-encoded JSON with userId + username.
 * This string gets QR-encoded or shared as text.
 *
 * Format: Base64({"u": userId, "n": username})
 * Short keys keep the QR code small (fewer modules = easier to scan).
 *
 * The code is just a pointer — the actual key exchange happens via Signal
 * when befriend() is called. The code itself doesn't need to be secret.
 */
object FriendCode {

    data class Decoded(val userId: String, val username: String)

    fun encode(userId: String, username: String): String {
        val json = JSONObject().apply {
            put("u", userId)
            put("n", username)
        }
        return json.toString().toByteArray().toBase64()
    }

    fun decode(code: String): Decoded {
        val json = JSONObject(String(code.fromBase64()))
        val userId = json.optString("u", "")
        val username = json.optString("n", "")
        require(userId.isNotEmpty() && username.isNotEmpty()) { "Invalid friend code" }
        return Decoded(userId, username)
    }
}
