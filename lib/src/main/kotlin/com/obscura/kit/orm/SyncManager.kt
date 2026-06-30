package com.obscura.kit.orm

/**
 * Handles broadcasting model operations to relevant recipients.
 *
 * Targeting rules (in order):
 * 1. Always self-sync to own devices
 * 2. If model.config.isPrivate → ONLY own devices (settings, drafts)
 * 3. If model has belongs_to a targeting model (group) → group members only
 * 4. If the entry declares a 1:1 / direct recipient (directMessage, pix) → those participants only
 * 5. Default → all friend devices
 *
 * The developer never calls this directly. model.create() triggers broadcast
 * automatically based on the model's config.
 */
class SyncManager(
    private val store: ModelStore? = null
) {
    private val models = mutableMapOf<String, Model>()

    // Callbacks — wired by ObscuraClient at init time
    var getSelfSyncTargets: suspend () -> List<String> = { emptyList() }
    var getFriendTargets: suspend () -> List<String> = { emptyList() }
    var getDevicesForUsername: suspend (username: String) -> List<String> = { emptyList() }
    var getDevicesForUserId: suspend (userId: String) -> List<String> = { emptyList() }
    var queueModelSync: suspend (targetDeviceId: String, modelSync: ModelSyncData) -> Unit = { _, _ -> }
    var flushQueue: suspend () -> Unit = { }

    fun register(name: String, model: Model) {
        models[name] = model
    }

    fun getModel(name: String): Model? = models[name]

    suspend fun broadcast(model: Model, entry: OrmEntry) {
        val targets = getTargets(model, entry)
        if (targets.isEmpty()) return

        val syncData = ModelSyncData(
            model = model.name,
            id = entry.id,
            op = 0, // CREATE
            timestamp = entry.timestamp,
            data = org.json.JSONObject(entry.data).toString().toByteArray(),
            authorDeviceId = entry.authorDeviceId,
            signature = entry.signature
        )

        for (targetDeviceId in targets) {
            queueModelSync(targetDeviceId, syncData)
        }

        flushQueue()
    }

    suspend fun handleIncoming(modelSync: ModelSyncData, sourceUserId: String): OrmEntry? {
        val model = models[modelSync.model] ?: return null
        return model.handleSync(modelSync)
    }

    private suspend fun getTargets(model: Model, entry: OrmEntry): List<String> {
        val targets = mutableSetOf<String>()

        // 1. Always self-sync to own devices
        targets.addAll(getSelfSyncTargets())

        // 2. Private models = only own devices
        if (model.config.isPrivate) return targets.toList()

        // 3. If belongs_to a group-like model, target group members only
        val assoc = model.getTargetingAssociation()
        if (assoc != null) {
            val (parentModelName, foreignKey) = assoc
            val parentId = entry.data[foreignKey.removePrefix("data.")] as? String
                ?: entry.data[foreignKey] as? String
            if (parentId != null) {
                val memberTargets = resolveGroupMembers(parentModelName, parentId)
                if (memberTargets.isNotEmpty()) {
                    targets.addAll(memberTargets)
                    return targets.toList()
                }
            }
        }

        // 4. Scoped 1:1 / direct-recipient delivery (before the all-friends broadcast).
        // directMessage and pix carry their intended audience in their own data. Without this
        // rule they fall through to "all friends" and leak to every mutual friend — a
        // confidentiality breach for what are meant to be private 1:1 payloads.
        val scoped = resolveScopedRecipients(entry)
        if (scoped != null) {
            targets.addAll(scoped)
            return targets.toList()
        }

        // 5. Default: broadcast to all friends
        targets.addAll(getFriendTargets())

        return targets.toList()
    }

    /**
     * Device IDs for an entry whose audience is a single recipient or a 1:1 conversation,
     * or null if the entry declares no such scoping (→ caller broadcasts to all friends).
     *
     *  - data.recipientUsername present (e.g. pix) → that user's devices
     *  - data.conversationId of the canonical 1:1 form "userIdA_userIdB" → both participants
     *
     * The sender's own devices are always added separately (step 1 of getTargets), so a
     * conversationId that contains the sender's own id is fine (it dedupes).
     */
    private suspend fun resolveScopedRecipients(entry: OrmEntry): List<String>? {
        (entry.data["recipientUsername"] as? String)?.takeIf { it.isNotBlank() }?.let { username ->
            return getDevicesForUsername(username)
        }
        (entry.data["conversationId"] as? String)?.let { convId ->
            val participantIds = convId.split("_").filter { it.isNotBlank() }
            // Only canonical 1:1 conversations are scoped here. Anything else (a group id,
            // an unexpected format) falls through to the all-friends default unchanged.
            if (participantIds.size == 2) {
                return participantIds.flatMap { getDevicesForUserId(it) }
            }
        }
        return null
    }

    private suspend fun resolveGroupMembers(parentModelName: String, parentId: String): List<String> {
        val parentModel = models[parentModelName] ?: return emptyList()
        val parent = parentModel.find(parentId) ?: return emptyList()

        // Convention: members stored as JSON array string in data.members or data.participants
        val membersRaw = parent.data["members"] ?: parent.data["participants"] ?: return emptyList()

        val usernames = try {
            when (membersRaw) {
                is String -> {
                    val arr = org.json.JSONArray(membersRaw)
                    (0 until arr.length()).map { arr.getString(it) }
                }
                is List<*> -> membersRaw.filterIsInstance<String>()
                else -> emptyList()
            }
        } catch (e: Exception) { emptyList() }

        if (usernames.isEmpty()) return emptyList()

        // Resolve each username to device IDs
        return usernames.flatMap { username -> getDevicesForUsername(username) }
    }
}
