package com.obscura.kit.wire

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ECS Signal Manager — ephemeral signals attached to models.
 *
 * Signals are NOT persisted, NOT CRDT-merged. They live in memory,
 * auto-expire after 3 seconds, and emit to reactive observers.
 *
 * Used for: typing indicators, read receipts, online status.
 *
 * Wire format: MODEL_SIGNAL (type 31) in ClientMessage.
 */
class SignalManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Key: "${model}:${signal}:${contextKey}" → Set of active authors
    // contextKey is extracted from data (e.g., conversationId)
    private val activeSignals = MutableStateFlow<Map<String, Set<ActiveSignal>>>(emptyMap())

    data class ActiveSignal(
        val authorDeviceId: String,
        val senderUsername: String,
        val expiresAt: Long
    )

    // Callbacks — wired by ObscuraClient
    var sendSignal: suspend (model: String, signal: String, data: Map<String, Any?>) -> Unit = { _, _, _ -> }

    /**
     * Emit a signal. Auto-throttled: won't re-send if the same signal
     * was sent within the last 2 seconds.
     */
    private val lastSent = mutableMapOf<String, Long>()

    /**
     * Guards [lastSent]. The throttle is a read-modify-write ("last send was long enough ago, so
     * record now and send"), and `emit` is called from whatever coroutine the UI happens to be on —
     * so a plain `mutableMapOf` was both a data race on a HashMap and a check that two callers could
     * pass simultaneously, defeating the throttle it exists to enforce.
     */
    private val throttleMutex = Mutex()
    private val THROTTLE_MS = 2000L

    suspend fun emit(model: String, signal: String, data: Map<String, Any?>, authorDeviceId: String) {
        val contextKey = data["conversationId"] as? String ?: "global"
        val throttleKey = "$model:$signal:$contextKey:$authorDeviceId"
        val now = System.currentTimeMillis()
        val allowed = throttleMutex.withLock {
            val last = lastSent[throttleKey] ?: 0L
            if (now - last < THROTTLE_MS) false else { lastSent[throttleKey] = now; true }
        }
        if (!allowed) return

        sendSignal(model, signal, data)
    }

    /**
     * Receive an incoming signal from the wire.
     * Holds it in memory for 3 seconds, then auto-expires.
     */
    fun receive(model: String, signal: String, data: Map<String, Any?>, authorDeviceId: String) {
        val contextKey = data["conversationId"] as? String ?: "global"
        val key = "$model:$signal:$contextKey"
        val senderUsername = data["senderUsername"] as? String ?: authorDeviceId
        val expiresAt = System.currentTimeMillis() + EXPIRE_MS

        val active = ActiveSignal(authorDeviceId, senderUsername, expiresAt)

        // `update {}`, not read-then-assign: two envelopes for the same conversation are routed
        // concurrently, and `value = value.toMutableMap().also { ... }` loses one of them outright.
        activeSignals.update { current ->
            val existing = current[key].orEmpty().filterNot { it.authorDeviceId == authorDeviceId }
            current + (key to (existing + active).toSet())
        }

        // Schedule expiry — only remove if the signal hasn't been renewed
        scope.launch {
            delay(EXPIRE_MS + 100) // small buffer
            val now = System.currentTimeMillis()
            val signals = activeSignals.value[key] ?: return@launch
            val entry = signals.find { it.authorDeviceId == authorDeviceId } ?: return@launch
            if (entry.expiresAt <= now) {
                expire(key, authorDeviceId)
            }
        }
    }

    /**
     * Immediately clear a signal (e.g., stoppedTyping).
     */
    fun clear(model: String, signal: String, data: Map<String, Any?>, authorDeviceId: String) {
        val contextKey = data["conversationId"] as? String ?: "global"
        val key = "$model:$signal:$contextKey"
        expire(key, authorDeviceId)
    }

    /**
     * Observe who is actively signaling for a given model + signal + context.
     * Returns usernames of active signalers.
     */
    fun observe(model: String, signal: String, contextKey: String): Flow<List<String>> {
        val key = "$model:$signal:$contextKey"
        return activeSignals.map { signals ->
            val now = System.currentTimeMillis()
            (signals[key] ?: emptySet())
                .filter { it.expiresAt > now }
                .map { it.senderUsername }
        }
    }

    private fun expire(key: String, authorDeviceId: String) {
        activeSignals.update { current ->
            val existing = current[key]?.filterNot { it.authorDeviceId == authorDeviceId } ?: return@update current
            if (existing.isEmpty()) current - key else current + (key to existing.toSet())
        }
    }

    /**
     * Stop the expiry coroutines and forget everything. Called from `ObscuraClient.fullLogout`.
     *
     * Without it this scope is never cancelled: every [receive] launches a 3.1s timer, and after a
     * logout those keep running against state belonging to a user who is gone. Terminal — a
     * shut-down manager stays empty, which is what a logged-out client should show.
     */
    fun shutdown() {
        scope.cancel()
        activeSignals.value = emptyMap()
    }

    companion object {
        const val EXPIRE_MS = 3000L
    }
}
