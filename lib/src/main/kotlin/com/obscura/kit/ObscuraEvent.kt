package com.obscura.kit

import com.obscura.kit.orm.OrmEntry
import com.obscura.kit.stores.FriendData

/**
 * Typed event stream — bridges subscribe to `client.typedEvents` and relay to JS.
 * Replaces raw incomingMessages channel + separate StateFlow observations.
 */
sealed class ObscuraEvent {
    data class FriendsUpdated(val friends: List<FriendData>) : ObscuraEvent()
    data class ConnectionChanged(val state: ConnectionState) : ObscuraEvent()
    data class AuthChanged(val state: AuthState) : ObscuraEvent()
    data class MessageReceived(val model: String, val entry: OrmEntry) : ObscuraEvent()
    /**
     * Emitted when the set of active typers for a model + context changes.
     * [contextId] is the opaque context key provided by the application when
     * calling [com.obscura.kit.orm.Model.typing] — typically a conversation ID
     * or channel name, but the kit treats it as an opaque string.
     * [typers] contains the [authorDeviceId] strings of active typers.
     */
    data class TypingChanged(val contextId: String, val typers: List<String>) : ObscuraEvent()
}
