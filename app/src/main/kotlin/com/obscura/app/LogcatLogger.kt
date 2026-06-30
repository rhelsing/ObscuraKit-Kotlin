package com.obscura.app

import android.util.Log
import com.obscura.kit.ObscuraLogger

/**
 * Routes ObscuraKit events to Android logcat under the "ObscuraKit" tag.
 * Set on every client in [ObscuraApp.createClient] so the kit is observable on device:
 *
 *   adb logcat -s ObscuraKit ObscuraApp
 */
object LogcatLogger : ObscuraLogger {
    private const val TAG = "ObscuraKit"

    override fun log(message: String) = Log.d(TAG, message).let {}
    override fun decryptFailed(sourceUserId: String, reason: String) =
        Log.w(TAG, "decrypt failed from $sourceUserId: $reason").let {}
    override fun ackFailed(envelopeId: String, reason: String) =
        Log.w(TAG, "ack failed for $envelopeId: $reason").let {}
    override fun tokenRefreshFailed(attempt: Int, reason: String) =
        Log.w(TAG, "token refresh failed (attempt $attempt): $reason").let {}
    override fun preKeyReplenishFailed(reason: String) =
        Log.w(TAG, "prekey replenish failed: $reason").let {}
    override fun identityChanged(address: String) =
        Log.w(TAG, "identity changed for $address").let {}
    override fun sessionEstablishFailed(userId: String, reason: String) =
        Log.w(TAG, "session establish failed for $userId: $reason").let {}
    override fun signatureVerificationFailed(sourceUserId: String, messageType: String) =
        Log.w(TAG, "signature verification failed from $sourceUserId type=$messageType").let {}
    override fun databaseError(store: String, operation: String, reason: String) =
        Log.e(TAG, "db error in $store.$operation: $reason").let {}
}
