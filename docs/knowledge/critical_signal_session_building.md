---
name: Signal session auto-building is required before encrypt
description: SessionCipher.encrypt() silently fails if no session exists. Must fetchPreKeyBundles + SessionBuilder.process(bundle) first. The ensureSession() pattern is critical.
type: feedback
---

`SessionCipher.encrypt()` will throw if no Signal session exists at the target address. Sessions are NOT created automatically.

**The pattern (in MessengerDomain.queueMessage):**
```kotlin
private suspend fun ensureSession(targetUserId: String, registrationId: Int) {
    val address = SignalProtocolAddress(targetUserId, registrationId)
    if (!signalStore.containsSession(address)) {
        val bundles = fetchPreKeyBundlesInternal(targetUserId)
        val bundle = bundles.find { it.registrationId == registrationId } ?: bundles.firstOrNull()
        SessionBuilder(signalStore, address).process(bundle)
    }
}
```

**For decryption of PreKey messages:** Try addresses WITHOUT existing sessions first (opposite of normal Whisper messages). The session gets established during PreKey decryption. Also try own registrationId as a candidate — for self-sync messages.

**How to apply:** Never call `encrypt()` without ensuring a session exists. The `sendToAllDevices()` path handles this via `ensureSession()` inside `queueMessage()`. If adding new send paths, make sure they go through `queueMessage()`.
