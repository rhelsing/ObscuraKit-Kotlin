package com.obscura.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * DEBUG-ONLY. Simulates a silent FCM push so the full wake → drain → classify → notify
 * pipeline can be exercised on any emulator (no Google Play services / no real FCM needed).
 *
 * Triggers the identical code path as [ObscuraFcmService.onMessageReceived] —
 * processPendingMessages() + PushNotifier — so what you see here is what real FCM does.
 *
 *   adb shell am broadcast -n com.obscuraapp.android/com.obscura.app.TestPushReceiver \
 *     -a com.obscura.app.TEST_PUSH
 *
 * Lives in src/debug/, so it is absent from release builds.
 */
class TestPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "TEST_PUSH received — simulating silent push wake")
        val app = context.applicationContext as? ObscuraApp
        val client = app?.client
        if (client == null) {
            Log.w(TAG, "TEST_PUSH: no client/session — log in first")
            return
        }
        // Keep the receiver alive while the async drain runs.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val counts = client.processPendingMessages(timeoutMs = 8_000)
                Log.d(TAG, "TEST_PUSH drain: pix=${counts.pixCount} msg=${counts.messageCount} other=${counts.otherCount}")
                PushNotifier.notify(context.applicationContext, counts)
            } catch (e: Exception) {
                Log.e(TAG, "TEST_PUSH drain failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ObscuraApp"
    }
}
