package com.obscura.kit.orm

/**
 * Handles broadcasting model operations to relevant recipients.
 *
 * The delivery audience is declared per model via [ModelConfig.audience]; the
 * sender's own devices are always included. The kit never inspects
 * application-specific field names — the 1:1 audiences name the field that
 * carries the recipient, and an unresolved 1:1 audience FAILS LOUD rather than
 * falling back to a broadcast (a misrouted 1:1 payload is a confidentiality
 * breach, so refusing to send is the safe failure).
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
        // Own devices are always included, regardless of audience.
        val targets = getSelfSyncTargets().toMutableSet()

        when (val audience = model.config.audience) {
            is Audience.Self -> { /* own devices only */ }

            is Audience.Friends -> targets.addAll(getFriendTargets())

            is Audience.Recipient -> {
                val username = (entry.data[audience.usernameField] as? String)?.takeIf { it.isNotBlank() }
                    ?: throw com.obscura.kit.ObscuraError.DirectRoutingUnresolved(
                        "Model '${model.name}' has a Recipient audience but its field " +
                        "'${audience.usernameField}' is missing or blank. Refusing to broadcast a 1:1 payload.")
                targets.addAll(getDevicesForUsername(username))
            }

            is Audience.Conversation -> {
                val convId = entry.data[audience.conversationField] as? String
                val participantIds = convId?.split("_")?.filter { it.isNotBlank() }.orEmpty()
                if (participantIds.size != 2) throw com.obscura.kit.ObscuraError.DirectRoutingUnresolved(
                    "Model '${model.name}' has a Conversation audience but its field " +
                    "'${audience.conversationField}'=\"$convId\" is not a canonical two-party " +
                    "\"userIdA_userIdB\" value. Refusing to broadcast a 1:1 payload.")
                targets.addAll(participantIds.flatMap { getDevicesForUserId(it) })
            }
        }

        return targets.toList()
    }
}
