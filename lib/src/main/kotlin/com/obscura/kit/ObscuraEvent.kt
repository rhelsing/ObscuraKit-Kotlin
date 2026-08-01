package com.obscura.kit

import com.obscura.kit.stores.FriendData

/**
 * Optional aggregate event stream for consumers that prefer one subscription.
 * The current Android bridge observes StateFlows and incomingMessages directly.
 */
sealed class ObscuraEvent {
    data class FriendsUpdated(val friends: List<FriendData>) : ObscuraEvent()
    data class ConnectionChanged(val state: ConnectionState) : ObscuraEvent()
    data class AuthChanged(val state: AuthState) : ObscuraEvent()
    /**
     * A MODEL_SYNC arrived for [model]. The payload is in the durable inbox; this event is only a
     * wake-up (`KIT_API.md` §3).
     */
    data class MessageReceived(val model: String) : ObscuraEvent()
}
