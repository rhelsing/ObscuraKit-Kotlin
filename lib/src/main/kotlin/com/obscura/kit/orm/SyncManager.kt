package com.obscura.kit.orm

/**
 * Handles broadcasting model operations to relevant recipients.
 * Uses fan-out + self-sync patterns.
 */
class SyncManager(
    private val store: ModelStore? = null
) {
    private val models = mutableMapOf<String, Model>()

    // Callbacks for broadcast — wired up by ObscuraClient
    var getSelfSyncTargets: () -> List<String> = { emptyList() }
    var getFriendTargets: () -> List<String> = { emptyList() }
    var sendModelSync: suspend (targetDeviceId: String, modelSync: ModelSyncData) -> Unit = { _, _ -> }

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
            sendModelSync(targetDeviceId, syncData)
        }
    }

    suspend fun handleIncoming(modelSync: ModelSyncData, sourceUserId: String): OrmEntry? {
        val model = models[modelSync.model] ?: return null
        return model.handleSync(modelSync)
    }

    private fun getTargets(model: Model, entry: OrmEntry): List<String> {
        val targets = mutableSetOf<String>()

        // Always self-sync
        targets.addAll(getSelfSyncTargets())

        // Private models = only own devices
        if (model.config.isPrivate) return targets.toList()

        // Default: broadcast to all friends
        targets.addAll(getFriendTargets())

        return targets.toList()
    }
}
