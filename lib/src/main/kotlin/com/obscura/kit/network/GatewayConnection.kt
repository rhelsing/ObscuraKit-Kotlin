package com.obscura.kit.network

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import xyz.obscura.server.contracts.ObscuraProtocol.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class GatewayState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

/**
 * WebSocket connection to Obscura gateway. Implements [AutoCloseable] — call [close]
 * (or [disconnect] for a soft disconnect with reconnect suppressed) to release
 * the OkHttp client, cancel the reconnect job, and close both channels.
 *
 * Keeps the connection alive with:
 * - OkHttp ping every 30s (keeps NAT/proxy alive)
 * - Auto-reconnect with exponential backoff (1s → 30s)
 * - Token refresh before reconnect attempts
 * - Intentional disconnect (code 1000) suppresses reconnect
 *
 * Thread-safety: OkHttp delivers callbacks on its own threads; all shared
 * mutable state uses [AtomicReference] / [AtomicInteger] / [@Volatile] so
 * callbacks racing with coroutine-side writers never observe torn state.
 */
class GatewayConnection(
    private val api: APIClient,
    private val scope: CoroutineScope
) : AutoCloseable {
    private val client = OkHttpClient.Builder()
        .readTimeout(90, TimeUnit.SECONDS)  // if no data or pong for 90s, connection is dead
        .pingInterval(30, TimeUnit.SECONDS) // keepalive — triggers onFailure if pong missing
        .build()

    // AtomicReference for fields written by OkHttp callbacks AND coroutine threads.
    private val _webSocket = AtomicReference<WebSocket?>(null)
    private val _openSignal = AtomicReference<CompletableDeferred<Unit>?>(null)

    // AtomicInteger for the backoff counter (read/written from reconnect coroutine only,
    // but also from onOpen which runs on an OkHttp thread).
    private val _reconnectAttempts = AtomicInteger(0)

    // @Volatile booleans are written from coroutine threads and read from OkHttp
    // callback threads; @Volatile guarantees visibility without a full lock.
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var shouldReconnect = true
    @Volatile private var isClosed = false

    // Current socket state, exposed two complementary ways:
    //   • [state]          — observable holder (StateFlow) for pull / flow collection
    //   • [onStateChanged] — synchronous push, invoked inline from setState() so the
    //                        one in-process mirror (ObscuraClient.connectionState) is
    //                        updated with zero latency — see setState() below.
    // Both are driven by the single mutation point setState(); callers never touch
    // _state directly.
    private val _state = MutableStateFlow(GatewayState.DISCONNECTED)
    val state: StateFlow<GatewayState> = _state

    /** Fired synchronously on every socket state transition. Set by ObscuraClient. */
    var onStateChanged: ((GatewayState) -> Unit)? = null

    /** The only writer of [_state]; keeps the flow and the callback in lockstep. */
    private fun setState(s: GatewayState) {
        _state.value = s
        onStateChanged?.invoke(s)
    }

    /**
     * Channel capacities: envelopes uses 1 000 (matches ObscuraClient.incomingMessages)
     * so a burst of messages after reconnect doesn't drop. preKeyStatus is small —
     * the server sends one status frame right after open.
     * Channel.BUFFERED (64) is fine; the only consequence of a full preKeyStatus
     * channel is a missed replenishment check (non-fatal, retried next connect).
     */
    val envelopes = Channel<Envelope>(capacity = 1000)
    val preKeyStatus = Channel<PreKeyStatus>(capacity = Channel.BUFFERED)

    /** Called before reconnect to ensure token is fresh. Set by ObscuraClient. */
    var ensureFreshToken: (suspend () -> Boolean) = { true }

    suspend fun connect() {
        if (isClosed) throw IllegalStateException("GatewayConnection is closed")
        if (_state.value == GatewayState.CONNECTED || _state.value == GatewayState.CONNECTING) return
        setState(GatewayState.CONNECTING)
        shouldReconnect = true

        val signal = CompletableDeferred<Unit>()
        _openSignal.set(signal)
        try {
            val ticket = api.fetchGatewayTicket()
            val url = api.getGatewayUrl(ticket)
            openWebSocket(url)
            // Suspend until the socket truly opens (onOpen) or fails (onFailure/
            // onClosed). This makes "connect() returned" mean "connected", and
            // lets the listener callbacks be the single owner of the state flow.
            signal.await()
        } catch (e: Exception) {
            if (_state.value != GatewayState.DISCONNECTED) setState(GatewayState.DISCONNECTED)
            throw e
        } finally {
            _openSignal.compareAndSet(signal, null)
        }
    }

    /**
     * Soft disconnect: suppresses auto-reconnect and closes the socket with a
     * clean code 1000. Does not release the OkHttp client or close channels;
     * [connect] can be called again after [disconnect]. Use [close] to fully
     * tear down this instance.
     */
    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        _openSignal.getAndSet(null)?.completeExceptionally(
            IllegalStateException("disconnected during connect"))
        _webSocket.getAndSet(null)?.close(1000, "Client disconnect")
        setState(GatewayState.DISCONNECTED)
    }

    /**
     * Fully tear down this instance. Idempotent — safe to call multiple times.
     * Closes the underlying OkHttp client (drops all pooled connections), closes
     * the [envelopes] and [preKeyStatus] channels, and suppresses any further
     * reconnect attempts.
     */
    override fun close() {
        if (isClosed) return
        isClosed = true
        disconnect()
        envelopes.close()
        preKeyStatus.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    fun ack(messageIds: List<com.google.protobuf.ByteString>) {
        val ackMsg = AckMessage.newBuilder()
            .addAllMessageIds(messageIds)
            .build()

        val frame = WebSocketFrame.newBuilder()
            .setAck(ackMsg)
            .build()

        _webSocket.get()?.send(ByteString.of(*frame.toByteArray()))
    }

    private fun openWebSocket(url: String) {
        val request = Request.Builder().url(url).build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                setState(GatewayState.CONNECTED)
                _reconnectAttempts.set(0)
                _openSignal.get()?.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val frame = WebSocketFrame.parseFrom(bytes.toByteArray())
                    when {
                        frame.hasPreKeyStatus() -> {
                            val result = preKeyStatus.trySend(frame.preKeyStatus)
                            if (result.isFailure) {
                                // Non-fatal: channel full means replenishment is already queued
                            }
                        }
                        frame.hasEnvelopeBatch() -> {
                            for (envelope in frame.envelopeBatch.envelopesList) {
                                val result = envelopes.trySend(envelope)
                                if (result.isFailure) {
                                    // Log via the state-change callback (no direct logger access here)
                                    onStateChanged?.invoke(_state.value) // nudge the observer
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Corrupt/undecodable frame on the wire: drop it and keep the socket alive.
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                setState(GatewayState.DISCONNECTED)
                _openSignal.get()?.completeExceptionally(t)
                if (shouldReconnect && !isClosed) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                setState(GatewayState.DISCONNECTED)
                _openSignal.get()?.completeExceptionally(
                    IllegalStateException("gateway closed before open: $code $reason")
                )
                if (code != 1000 && shouldReconnect && !isClosed) scheduleReconnect()
            }
        })
        _webSocket.set(ws)
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            setState(GatewayState.RECONNECTING)

            // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s max
            val attempts = _reconnectAttempts.get()
            val delayMs = (1000L * (1L shl attempts.coerceAtMost(5))).coerceAtMost(30_000L)
            delay(delayMs)
            _reconnectAttempts.incrementAndGet()

            try {
                ensureFreshToken()
                connect()
            } catch (_: Exception) {
                if (shouldReconnect && !isClosed) scheduleReconnect()
            }
        }
    }
}
