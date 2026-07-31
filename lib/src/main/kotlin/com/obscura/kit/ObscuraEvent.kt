package com.obscura.kit

import com.obscura.kit.stores.FriendData

/**
 * Typed event stream — bridges subscribe to `client.events` and relay to JS.
 * Replaces raw incomingMessages channel + separate StateFlow observations.
 */
sealed class ObscuraEvent {
    data class FriendsUpdated(val friends: List<FriendData>) : ObscuraEvent()
    data class ConnectionChanged(val state: ConnectionState) : ObscuraEvent()
    data class AuthChanged(val state: AuthState) : ObscuraEvent()
    /**
     * A MODEL_SYNC arrived for [model]. Carries no entry: the payload is in the kit's inbox, and the
     * app drains it (`KIT_API.md` §3). This used to carry an `OrmEntry`, which made the event a
     * second delivery path competing with the store — exactly what §2 rejects.
     */
    data class MessageReceived(val model: String) : ObscuraEvent()
    data class TypingChanged(val conversationId: String, val typers: List<String>) : ObscuraEvent()
}
