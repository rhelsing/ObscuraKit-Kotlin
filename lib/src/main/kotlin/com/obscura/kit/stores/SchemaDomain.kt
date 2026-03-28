package com.obscura.kit.stores

import com.obscura.kit.orm.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SchemaDomain - Confined coroutines. ORM model definitions and sync handling.
 */
class SchemaDomain internal constructor(
    private val store: ModelStore,
    private val syncManager: SyncManager,
    private val ttlManager: TTLManager,
    private val deviceId: String = ""
) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val schema = Schema(store, syncManager, ttlManager, deviceId)

    suspend fun define(definitions: Map<String, ModelConfig>) = withContext(dispatcher) {
        schema.define(definitions)
    }

    suspend fun model(name: String): Model? = withContext(dispatcher) {
        schema.model(name)
    }

    suspend fun handleSync(modelSync: ModelSyncData, from: String): OrmEntry? = withContext(dispatcher) {
        syncManager.handleIncoming(modelSync, from)
    }
}
