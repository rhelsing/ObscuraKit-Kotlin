package com.obscura.kit.network

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import xyz.obscura.server.contracts.ObscuraProtocol.*
import java.util.concurrent.TimeUnit

enum class GatewayState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

/**
 * WebSocket connection to Obscura gateway.
 * Handles automatic reconnection and message delivery.
 */
class GatewayConnection(
    private val api: APIClient,
    private val scope: CoroutineScope
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null

    private val _state = MutableStateFlow(GatewayState.DISCONNECTED)
    val state: StateFlow<GatewayState> = _state

    /** Channel for received envelopes */
    val envelopes = Channel<Envelope>(capacity = 1000)

    /** Channel for prekey status notifications */
    val preKeyStatus = Channel<PreKeyStatus>(capacity = 10)

    /** Callback for connection events */
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    /**
     * Connect to the gateway using a ticket-based auth flow.
     */
    suspend fun connect() {
        if (_state.value == GatewayState.CONNECTED || _state.value == GatewayState.CONNECTING) return
        _state.value = GatewayState.CONNECTING

        try {
            val ticket = api.fetchGatewayTicket()
            val url = api.getGatewayUrl(ticket)
            openWebSocket(url)
        } catch (e: Exception) {
            _state.value = GatewayState.DISCONNECTED
            throw e
        }
    }

    /**
     * Disconnect from the gateway.
     */
    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _state.value = GatewayState.DISCONNECTED
        onDisconnected?.invoke()
    }

    /**
     * Send an ACK for processed message IDs.
     */
    fun ack(messageIds: List<com.google.protobuf.ByteString>) {
        val ackMsg = AckMessage.newBuilder()
            .addAllMessageIds(messageIds)
            .build()

        val frame = WebSocketFrame.newBuilder()
            .setAck(ackMsg)
            .build()

        webSocket?.send(ByteString.of(*frame.toByteArray()))
    }

    private fun openWebSocket(url: String) {
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = GatewayState.CONNECTED
                onConnected?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val frame = WebSocketFrame.parseFrom(bytes.toByteArray())
                    when {
                        frame.hasPreKeyStatus() -> {
                            preKeyStatus.trySend(frame.preKeyStatus)
                        }
                        frame.hasEnvelopeBatch() -> {
                            for (envelope in frame.envelopeBatch.envelopesList) {
                                envelopes.trySend(envelope)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Failed to parse frame — skip
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = GatewayState.DISCONNECTED
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = GatewayState.DISCONNECTED
                if (code != 1000) {
                    scheduleReconnect()
                }
                onDisconnected?.invoke()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            _state.value = GatewayState.RECONNECTING
            delay(3000) // Wait before reconnecting
            try {
                connect()
            } catch (e: Exception) {
                // Will retry on next failure
            }
        }
    }
}
