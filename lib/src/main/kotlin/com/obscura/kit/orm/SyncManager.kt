package com.obscura.kit.orm

/**
 * Handles broadcasting model operations to relevant recipients.
 *
 * Targeting rules (in order):
 * 1. Always self-sync to own devices
 * 2. If model.config.isPrivate → ONLY own devices (settings, drafts)
 * 3. If model has belongs_to a targeting model (group) → group members only
 * 4. Default → all friend devices
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

        // 4. Default: broadcast to all friends
        targets.addAll(getFriendTargets())

        return targets.toList()
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
