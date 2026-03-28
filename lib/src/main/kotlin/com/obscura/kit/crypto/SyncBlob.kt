package com.obscura.kit.crypto

import com.obscura.kit.stores.FriendData
import com.obscura.kit.stores.MessageData
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Export/import local state as compressed SyncBlob for device linking.
 * Matches the web client's SyncBlob format: gzipped JSON of { friends, messages }.
 */
object SyncBlob {

    /**
     * Export friends and messages as compressed data.
     */
    fun export(friends: List<FriendData>, messages: Map<String, List<MessageData>> = emptyMap()): ByteArray {
        val dict = JSONObject()

        val friendsList = JSONArray(friends.map { friend ->
            JSONObject().apply {
                put("userId", friend.userId)
                put("username", friend.username)
                put("status", friend.status.value)
            }
        })
        dict.put("friends", friendsList)

        val allMessages = JSONArray()
        for ((convId, msgs) in messages) {
            for (msg in msgs) {
                allMessages.put(JSONObject().apply {
                    put("messageId", msg.id)
                    put("conversationId", convId)
                    put("content", msg.content)
                    put("timestamp", msg.timestamp)
                    put("type", msg.type)
                    put("authorDeviceId", msg.authorDeviceId)
                })
            }
        }
        dict.put("messages", allMessages)
        dict.put("timestamp", System.currentTimeMillis())

        return gzipCompress(dict.toString().toByteArray())
    }

    /**
     * Parse compressed sync blob back into structured data.
     */
    fun parse(data: ByteArray): ParsedSyncBlob? {
        return try {
            val json = String(gzipDecompress(data))
            val dict = JSONObject(json)

            val friends = mutableListOf<Map<String, String>>()
            val friendsArr = dict.optJSONArray("friends")
            if (friendsArr != null) {
                for (i in 0 until friendsArr.length()) {
                    val f = friendsArr.getJSONObject(i)
                    friends.add(mapOf(
                        "userId" to f.optString("userId", ""),
                        "username" to f.optString("username", ""),
                        "status" to f.optString("status", "accepted")
                    ))
                }
            }

            val messages = mutableListOf<Map<String, Any>>()
            val msgsArr = dict.optJSONArray("messages")
            if (msgsArr != null) {
                for (i in 0 until msgsArr.length()) {
                    val m = msgsArr.getJSONObject(i)
                    messages.add(mapOf(
                        "messageId" to m.optString("messageId", ""),
                        "conversationId" to m.optString("conversationId", ""),
                        "content" to m.optString("content", ""),
                        "timestamp" to m.optLong("timestamp", 0),
                        "type" to m.optString("type", "text"),
                        "authorDeviceId" to m.optString("authorDeviceId", "")
                    ))
                }
            }

            ParsedSyncBlob(friends = friends, messages = messages)
        } catch (e: Exception) {
            null
        }
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gzipDecompress(data: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }
}

data class ParsedSyncBlob(
    val friends: List<Map<String, String>>,
    val messages: List<Map<String, Any>>
)
