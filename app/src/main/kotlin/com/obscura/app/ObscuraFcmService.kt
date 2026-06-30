package com.obscura.app

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

/**
 * Receives FCM wake-ups and drives the kit's push pipeline.
 *
 * Two callbacks matter:
 *  - [onNewToken]: FCM rotated the device token. Persist it and (if logged in) register
 *    it with the server via [com.obscura.kit.ObscuraClient.registerPushToken] →
 *    PUT /v1/push-tokens. [ObscuraApp] also re-registers on every auth, covering the
 *    case where the token arrives before login.
 *  - [onMessageReceived]: a silent/data push arrived. Wake the kit, drain queued
 *    envelopes via processPendingMessages(), and post one generic local notification.
 *
 * Runs whenever a push arrives — including a cold start, where [ObscuraApp.onCreate]
 * has already restored the session and created the client before this fires.
 */
class ObscuraFcmService : FirebaseMessagingService() {

    private val app: ObscuraApp? get() = application as? ObscuraApp

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM onNewToken: ${token.take(12)}…")
        val a = app ?: return
        a.securePrefs.edit().putString("fcmToken", token).apply()
        val client = a.client
        if (client == null) {
            Log.d(TAG, "onNewToken: no client yet — will register after login")
            return
        }
        runBlocking {
            try {
                client.registerPushToken(token)
                Log.d(TAG, "onNewToken: registered with server")
            } catch (e: Exception) {
                Log.e(TAG, "onNewToken: registerPushToken failed: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d(TAG, "FCM push received (from=${message.from}) — waking to drain")
        val client = app?.client
        if (client == null) {
            Log.w(TAG, "push received but no client/session — ignoring")
            return
        }
        // The service stays alive while this callback runs; block it until the drain
        // completes so the OS doesn't freeze us mid-flight.
        runBlocking {
            val counts = client.processPendingMessages(timeoutMs = 10_000)
            Log.d(TAG, "drain complete: pix=${counts.pixCount} msg=${counts.messageCount} other=${counts.otherCount}")
            PushNotifier.notify(applicationContext, counts)
        }
    }

    companion object {
        private const val TAG = "ObscuraApp"
    }
}
