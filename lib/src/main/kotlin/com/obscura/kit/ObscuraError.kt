package com.obscura.kit

/**
 * Typed, code-carrying errors for the kit's public surface.
 *
 * Every case exposes a stable [code] that a consumer (e.g. the RN bridge) can
 * propagate to its own callers so they can branch on *what* went wrong instead
 * of pattern-matching an English message. The [code] is the contract; the
 * message is for humans/logs and may change.
 */
sealed class ObscuraError(val code: String, message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** No authenticated session (no user id / not logged in). */
    class NotAuthenticated(message: String = "Not authenticated") :
        ObscuraError("NOT_AUTHENTICATED", message)

    /** Logged in but this device has not been provisioned/linked yet. */
    class NotProvisioned(message: String = "Device not provisioned") :
        ObscuraError("NOT_PROVISIONED", message)

    /** The target user is not an accepted friend. */
    class NotFriends(username: String) :
        ObscuraError("NOT_FRIENDS", "Not friends with $username")

    /** No devices/prekey bundles could be resolved for the target user. */
    class NoDevices(user: String) :
        ObscuraError("NO_DEVICES", "No devices found for $user")

    /** A message was addressed but every delivery attempt failed. */
    class SendFailed(message: String) :
        ObscuraError("SEND_FAILED", message)

    // `DirectRoutingUnresolved` and `InvalidSchema` were here. Both belonged to the deleted ORM —
    // one to the audience-routing engine, one to the schema parser — and neither was thrown or
    // caught anywhere after it went. They are also live in the RN bridge's error union
    // (`obscura-pix/src/native/ObscuraModule.ts`) and in ObscuraKit-swift, which must follow.
}
