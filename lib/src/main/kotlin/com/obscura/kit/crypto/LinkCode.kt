package com.obscura.kit.crypto

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Device linking via QR code or pasted code.
 *
 * This is a Layer 2 constant — every new device MUST go through this flow.
 * The new device generates a link code (displayed as QR or text),
 * the existing device scans/pastes it, validates, and calls approveLink().
 *
 * Link code contains: deviceId, deviceUUID, Signal identity key, random challenge, timestamp.
 * Base64-encoded JSON — small enough for a QR code.
 */
object LinkCode {

    private const val DEFAULT_MAX_AGE_MS = 5 * 60 * 1000L // 5 minutes

    data class LinkData(
        val deviceId: String,
        val deviceUUID: String,
        val signalIdentityKey: ByteArray,
        val challenge: ByteArray,
        val timestamp: Long
    )

    data class ValidationResult(
        val valid: Boolean,
        val error: String? = null,
        val data: LinkData? = null
    )

    data class GeneratedCode(val code: String, val challenge: ByteArray)

    /**
     * Generate a link code for a new device.
     * Display code as QR or copyable text. Keep challenge for verification.
     */
    fun generate(deviceId: String, deviceUUID: String, signalIdentityKey: ByteArray): GeneratedCode {
        val challenge = ByteArray(16)
        SecureRandom().nextBytes(challenge)

        val json = JSONObject().apply {
            put("d", deviceId)          // short keys keep QR small
            put("u", deviceUUID)
            put("k", Base64.getEncoder().encodeToString(signalIdentityKey))
            put("c", Base64.getEncoder().encodeToString(challenge))
            put("t", System.currentTimeMillis())
        }

        val code = Base64.getEncoder().encodeToString(json.toString().toByteArray())
        return GeneratedCode(code, challenge)
    }

    /**
     * Parse a link code without validating expiry.
     */
    fun parse(linkCode: String): LinkData {
        val json = JSONObject(String(Base64.getDecoder().decode(linkCode)))
        return LinkData(
            deviceId = json.getString("d"),
            deviceUUID = json.getString("u"),
            signalIdentityKey = Base64.getDecoder().decode(json.getString("k")),
            challenge = Base64.getDecoder().decode(json.getString("c")),
            timestamp = json.getLong("t")
        )
    }

    /**
     * Validate a link code: check expiry, required fields.
     */
    fun validate(linkCode: String, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): ValidationResult {
        return try {
            val data = parse(linkCode)

            val age = System.currentTimeMillis() - data.timestamp
            if (age > maxAgeMs) {
                return ValidationResult(valid = false, error = "Link code expired")
            }
            if (age < -60_000) {
                return ValidationResult(valid = false, error = "Link code timestamp is in the future")
            }

            if (data.deviceId.isBlank()) {
                return ValidationResult(valid = false, error = "Missing deviceId")
            }
            if (data.signalIdentityKey.isEmpty()) {
                return ValidationResult(valid = false, error = "Missing Signal identity key")
            }
            if (data.challenge.size != 16) {
                return ValidationResult(valid = false, error = "Invalid challenge size")
            }

            ValidationResult(valid = true, data = data)
        } catch (e: Exception) {
            ValidationResult(valid = false, error = "Could not parse link code: ${e.message}")
        }
    }

    /**
     * Constant-time challenge comparison. Prevents timing attacks.
     */
    fun verifyChallenge(expected: ByteArray, received: ByteArray): Boolean {
        return MessageDigest.isEqual(expected, received)
    }
}
