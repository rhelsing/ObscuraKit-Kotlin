package com.obscura.kit.orm

import com.obscura.kit.orm.crdt.GSet
import com.obscura.kit.orm.crdt.LWWMap

/**
 * Schema - Defines and wires together models from config.
 */
class Schema(
    private val store: ModelStore,
    private val syncManager: SyncManager,
    private val ttlManager: TTLManager,
    private val deviceId: String = ""
) {
    private val models = mutableMapOf<String, Model>()

    fun define(definitions: Map<String, ModelConfig>) {
        for ((name, config) in definitions) {
            val model = if (config.sync == "lww") {
                Model(
                    name = name,
                    config = config,
                    lwwMap = LWWMap(store, name),
                    syncManager = syncManager,
                    ttlManager = ttlManager,
                    deviceId = deviceId
                )
            } else {
                Model(
                    name = name,
                    config = config,
                    gset = GSet(store, name),
                    syncManager = syncManager,
                    ttlManager = ttlManager,
                    deviceId = deviceId
                )
            }
            models[name] = model
            syncManager.register(name, model)
        }
    }

    fun model(name: String): Model? = models[name]

    fun allModels(): Map<String, Model> = models.toMap()
}
