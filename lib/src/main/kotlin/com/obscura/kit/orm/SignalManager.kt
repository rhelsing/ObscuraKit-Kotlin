package com.obscura.kit.orm

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * ECS Signal Manager — ephemeral signals attached to models.
 *
 * Signals are NOT persisted, NOT CRDT-merged. They live in memory,
 * auto-expire after 3 seconds, and emit to reactive observers.
 *
 * Used for: typing indicators, read receipts, online status.
 *
 * Wire format: MODEL_SIGNAL (type 31) in ClientMessage.
 *
 * Implements [AutoCloseable] — call [close] to cancel the internal coroutine
 * scope and release all signal timers.
 */
class SignalManager : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Key: "${model}:${signal}:${contextKey}" → Set of active authors
    private val activeSignals = MutableStateFlow<Map<String, Set<ActiveSignal>>>(emptyMap())

    data class ActiveSignal(
        val authorDeviceId: String,
        val expiresAt: Long
    )

    /**
     * Callback wired by ObscuraClient to fan-out signals over the wire.
     * Receives the model name, signal name, and an opaque [contextKey] string
     * (e.g. a conversation ID or channel name chosen by the application).
     * No application-specific field names are embedded here.
     */
    var sendSignal: suspend (model: String, signal: String, contextKey: String) -> Unit = { _, _, _ -> }

    /**
     * Emit a signal. Auto-throttled: won't re-send if the same signal
     * was sent within the last 2 seconds.
     */
    private val lastSent = mutableMapOf<String, Long>()
    private val THROTTLE_MS = 2000L

    suspend fun emit(model: String, signal: String, contextKey: String, authorDeviceId: String) {
        val throttleKey = "$model:$signal:$contextKey:$authorDeviceId"
        val now = System.currentTimeMillis()
        val last = lastSent[throttleKey] ?: 0L
        if (now - last < THROTTLE_MS) return

        lastSent[throttleKey] = now
        sendSignal(model, signal, contextKey)
    }

    /**
     * Receive an incoming signal from the wire.
     * Holds it in memory for 3 seconds, then auto-expires.
     */
    fun receive(model: String, signal: String, contextKey: String, authorDeviceId: String) {
        val key = "$model:$signal:$contextKey"
        val expiresAt = System.currentTimeMillis() + EXPIRE_MS

        val active = ActiveSignal(authorDeviceId, expiresAt)

        val current = activeSignals.value.toMutableMap()
        val existing = current[key]?.toMutableSet() ?: mutableSetOf()
        existing.removeAll { it.authorDeviceId == authorDeviceId }
        existing.add(active)
        current[key] = existing
        activeSignals.value = current

        scope.launch {
            delay(EXPIRE_MS + 100)
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
    fun clear(model: String, signal: String, contextKey: String, authorDeviceId: String) {
        val key = "$model:$signal:$contextKey"
        expire(key, authorDeviceId)
    }

    /**
     * Observe who is actively signaling for a given model + signal + context.
     * Returns [authorDeviceId] strings of active signalers; callers resolve
     * display names from their own friend graph rather than trusting a
     * sender-provided username field.
     */
    fun observe(model: String, signal: String, contextKey: String): Flow<List<String>> {
        val key = "$model:$signal:$contextKey"
        return activeSignals.map { signals ->
            val now = System.currentTimeMillis()
            (signals[key] ?: emptySet())
                .filter { it.expiresAt > now }
                .map { it.authorDeviceId }
        }
    }

    /** Cancel all pending signal timers. Idempotent. */
    override fun close() {
        scope.cancel()
    }

    private fun expire(key: String, authorDeviceId: String) {
        val current = activeSignals.value.toMutableMap()
        val existing = current[key]?.toMutableSet() ?: return
        existing.removeAll { it.authorDeviceId == authorDeviceId }
        if (existing.isEmpty()) current.remove(key) else current[key] = existing
        activeSignals.value = current
    }

    companion object {
        const val EXPIRE_MS = 3000L
    }
}
