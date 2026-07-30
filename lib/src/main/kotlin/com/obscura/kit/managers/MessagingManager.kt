package com.obscura.kit.managers

import obscura.client.v1.Client.ClientMessage
import org.json.JSONObject

/**
 * Send text, attachments, model sync, raw messages. Upload/download attachments.
 */
internal class MessagingManager(
    private val ctx: ClientContext
) {
    private val session get() = ctx.session
    private val api get() = ctx.api
    private val messenger get() = ctx.messenger
    private val friends get() = ctx.friends
    private val devices get() = ctx.devices
    private val messageSender get() = ctx.messageSender
    suspend fun send(friendUsername: String, text: String) {
        val friendData = friends.getAccepted().find { it.username == friendUsername }
            ?: throw com.obscura.kit.ObscuraError.NotFriends(friendUsername)

        val msg = ClientMessage.newBuilder()
            .setText(obscura.client.v1.textMessage { this.text = text })
            .setTimestamp(System.currentTimeMillis()).build()

        messageSender.sendToAllDevices(friendData.userId, msg)

        // Self-sync: SENT_SYNC to own devices
        val selfTargets = devices.getSelfSyncTargets().filter { it != session.deviceId }
        if (selfTargets.isNotEmpty()) {
            val msgId = java.util.UUID.randomUUID().toString()
            val sentSync = ClientMessage.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setSentSync(obscura.client.v1.sentSync {
                    recipientUsername = friendUsername
                    messageId = msgId
                    timestamp = System.currentTimeMillis()
                    content = com.google.protobuf.ByteString.copyFrom(text.toByteArray())
                })
                .build()
            for (devId in selfTargets) {
                messenger.queueMessage(devId, sentSync, session.userId)
            }
            messenger.flushMessages()
        }
    }

    suspend fun sendAttachment(friendUsername: String, attachmentId: String, contentKey: ByteArray, nonce: ByteArray, mimeType: String, sizeBytes: Long) {
        val friendData = friends.getAccepted().find { it.username == friendUsername }
            ?: throw com.obscura.kit.ObscuraError.NotFriends(friendUsername)

        val msg = ClientMessage.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .setContentReference(obscura.client.v1.contentReference {
                this.attachmentId = attachmentId
                this.contentKey = com.google.protobuf.ByteString.copyFrom(contentKey)
                this.nonce = com.google.protobuf.ByteString.copyFrom(nonce)
                this.contentType = mimeType
                this.sizeBytes = sizeBytes
            }).build()

        messageSender.sendToAllDevices(friendData.userId, msg)
    }

    suspend fun sendEncryptedAttachment(friendUsername: String, plaintext: ByteArray, mimeType: String = "application/octet-stream") {
        val encrypted = com.obscura.kit.crypto.AttachmentCrypto.encrypt(plaintext)
        val result = api.uploadAttachment(encrypted.ciphertext)
        sendAttachment(friendUsername, result.id, encrypted.contentKey, encrypted.nonce, mimeType, encrypted.sizeBytes)
    }

    suspend fun sendModelSync(friendUsername: String, model: String, entryId: String, op: String = "CREATE", data: Map<String, Any?>) {
        val friendData = friends.getAccepted().find { it.username == friendUsername }
            ?: throw com.obscura.kit.ObscuraError.NotFriends(friendUsername)

        val opEnum = com.obscura.kit.orm.WireCodec.encodeOp(com.obscura.kit.orm.ModelOp.fromApp(op))

        val msg = ClientMessage.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .setModelSync(obscura.client.v1.modelSync {
                this.model = model; this.id = entryId; this.op = opEnum
                timestamp = System.currentTimeMillis()
                this.data = com.google.protobuf.ByteString.copyFrom(JSONObject(data).toString().toByteArray())
                authorDeviceId = session.deviceId ?: ""
            }).build()

        messageSender.sendToAllDevices(friendData.userId, msg)
    }

    /**
     * Send an application entry (`obscura-proto/KIT_API.md` §5) — the outbox half of the thin kit.
     *
     * ```
     * send(recipientUserIds, modelKey, entryId, op, sentAt, payload)
     * ```
     *
     * **The caller names the recipients** (SPEC §0.4). The kit fans out to every device of every
     * listed userId, plus the author's own *other* devices, and makes **no delivery decision of its
     * own** — no audience resolution, no reading of `payload` to discover who it is for. That is the
     * whole difference from [sendModelSync], which takes a `friendUsername`, looks it up, and is
     * therefore the kit deciding an audience from an application concept.
     *
     * Two properties §5 asks to be proven rather than assumed, both pinned by `EntrySendTests`:
     *
     * 1. **The sending device is excluded from its own fan-out.** `getSelfSyncTargets()` returns
     *    every own device *including this one*, and a message encrypted to yourself is at best waste
     *    and at worst a duplicate the app must dedupe.
     * 2. **The sender gets no inbox row.** Nothing loops back locally, so the app must write its own
     *    outgoing entry — one write path in the kit, two in the app. `payload` is what the app
     *    stores; the kit never opens it.
     *
     * An empty `recipientUserIds` is legitimate and not an error: it means "my own devices only",
     * which is what a self-scoped model wants. Failing loud is for an audience the kit was asked to
     * *guess*, and here it never guesses.
     */
    suspend fun sendEntry(
        recipientUserIds: List<String>,
        modelKey: String,
        entryId: String,
        op: String,
        sentAt: Long,
        payload: ByteArray,
    ) {
        val msg = ClientMessage.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .setModelSync(obscura.client.v1.modelSync {
                this.model = modelKey
                this.id = entryId
                this.op = com.obscura.kit.orm.WireCodec.encodeOp(com.obscura.kit.orm.ModelOp.fromApp(op))
                timestamp = sentAt
                this.data = com.google.protobuf.ByteString.copyFrom(payload)
                authorDeviceId = session.deviceId ?: ""
            }).build()

        // `distinct()` because the app may legitimately name the same user twice — e.g. both
        // participants of a canonical `userIdA_userIdB` conversation where one of them is you.
        val targets = recipientUserIds.distinct().filter { it != session.userId }

        // PER-RECIPIENT, not all-or-nothing. `sendToAllDevices` throws for a recipient with no
        // registered devices, so letting the first failure escape would abandon recipients 2..N —
        // and, worse, skip the own-device self-sync below, so the user's own other devices would
        // silently never receive something they wrote. One unreachable friend must not cost the
        // other four, or the sender's own copy.
        val failures = mutableListOf<Pair<String, Exception>>()
        val selfSyncFailures = mutableListOf<Pair<String, Exception>>()
        for (userId in targets) {
            try {
                messageSender.sendToAllDevices(userId, msg)
            } catch (e: Exception) {
                // Collected rather than logged: `ClientContext` carries no logger, and the detail is
                // more useful aggregated into the throw below than scattered across log lines.
                failures.add(userId to e)
            }
        }

        // Own OTHER devices. Runs whether or not a recipient failed — see above. The filter is
        // §5 property 1: without it this device encrypts to itself.
        val selfTargets = devices.getSelfSyncTargets().filter { it != session.deviceId }
        val selfUserId = session.userId
        if (selfTargets.isNotEmpty() && selfUserId != null) {
            // PER-DEVICE, and the flush is unconditional — the same rule as the recipient loop above,
            // for the same reason. One own device with a broken Signal session would otherwise abort
            // the loop, leave the messages already queued for the OTHER own devices unflushed, and
            // propagate out of `sendEntry`. The app then reports "reached nobody" for a send that
            // reached everybody, because `writeEntry` re-throws on a total failure.
            //
            // Swift already did this correctly (per-device `do/catch`, unconditional flush); this is
            // Kotlin catching up, not a new rule.
            for (devId in selfTargets) {
                try {
                    messenger.queueMessage(devId, msg, selfUserId)
                } catch (e: Exception) {
                    selfSyncFailures.add(devId to e)
                }
            }
            messenger.flushMessages()
        }

        // Throw only when NOBODY named got it. A partial failure is logged and survivable — the
        // entry is stored, the other recipients have it, and the caller can retry. A total failure
        // is different in kind: the app believes it sent something that reached no one, and it must
        // be able to tell the user so.
        if (targets.isNotEmpty() && failures.size == targets.size) {
            throw com.obscura.kit.ObscuraError.SendFailed(
                "$modelKey/${entryId.take(20)} reached none of its ${targets.size} recipient(s): " +
                    failures.joinToString { "${it.first.take(8)}=${it.second.message}" }
            )
        }
    }

    suspend fun sendRaw(targetUserId: String, msg: ClientMessage) = messageSender.sendToAllDevices(targetUserId, msg)

    suspend fun uploadAttachment(data: ByteArray): Pair<String, Long> {
        val result = api.uploadAttachment(data)
        return Pair(result.id, result.expiresAt)
    }

    suspend fun downloadAttachment(id: String): ByteArray = api.fetchAttachment(id)

    companion object {
        private const val MAX_CACHE_BYTES = 50L * 1024 * 1024 // 50MB
    }

    suspend fun downloadDecryptedAttachment(id: String, contentKey: ByteArray, nonce: ByteArray, expectedHash: ByteArray? = null): ByteArray {
        // Check cache first
        val cached = ctx.db.attachmentCacheQueries.selectById(id).executeAsOneOrNull()
        if (cached != null) return cached

        // Cache miss — download, decrypt, cache
        val ciphertext = api.fetchAttachment(id)
        val plaintext = com.obscura.kit.crypto.AttachmentCrypto.decrypt(ciphertext, contentKey, nonce, expectedHash)

        // Store in encrypted DB
        ctx.db.attachmentCacheQueries.insert(id, plaintext, plaintext.size.toLong(), System.currentTimeMillis())

        // Evict if over size limit
        val totalSize = ctx.db.attachmentCacheQueries.totalSize().executeAsOne()
        if (totalSize > MAX_CACHE_BYTES) {
            ctx.db.attachmentCacheQueries.deleteOldest(10)
        }

        return plaintext
    }
}
