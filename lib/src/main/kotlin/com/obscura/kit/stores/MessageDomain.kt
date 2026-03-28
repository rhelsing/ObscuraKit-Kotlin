package com.obscura.kit.stores

import com.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MessageData(
    val id: String,
    val conversationId: String,
    val authorDeviceId: String,
    val content: String,
    val timestamp: Long,
    val type: String = "text"
)

/**
 * MessageDomain - Confined coroutines. Messages by conversationId.
 */
class MessageDomain internal constructor(private val db: ObscuraDatabase) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun add(conversationId: String, message: MessageData) = withContext(dispatcher) {
        db.messageQueries.insert(
            message.id,
            conversationId,
            message.authorDeviceId,
            message.content,
            message.timestamp,
            message.type
        )
    }

    suspend fun getMessages(conversationId: String, limit: Int = 50, offset: Int = 0): List<MessageData> =
        withContext(dispatcher) {
            db.messageQueries.selectByConversation(conversationId, limit.toLong(), offset.toLong())
                .executeAsList()
                .map { row ->
                    MessageData(
                        id = row.id,
                        conversationId = row.conversation_id,
                        authorDeviceId = row.author_device_id,
                        content = row.content,
                        timestamp = row.timestamp,
                        type = row.type
                    )
                }
        }

    suspend fun migrateMessages(from: String, to: String) = withContext(dispatcher) {
        db.messageQueries.migrateConversation(to, from)
    }

    suspend fun deleteByAuthorDevice(deviceId: String) = withContext(dispatcher) {
        db.messageQueries.deleteByAuthorDevice(deviceId)
    }
}
