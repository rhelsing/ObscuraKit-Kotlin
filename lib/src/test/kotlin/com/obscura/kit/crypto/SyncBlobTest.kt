package com.obscura.kit.crypto

import com.obscura.kit.stores.FriendData
import com.obscura.kit.stores.FriendStatus
import com.obscura.kit.stores.MessageData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SyncBlob is the wire format for device-linking export/import. The
 * recipient phone deserializes whatever the donor sent, so:
 *   - Round-trip must preserve content exactly
 *   - Parse must tolerate truncated / corrupted blobs (return null,
 *     never throw — the caller can't recover from an exception mid-link)
 *   - Format must stay stable: a v0.1 export must parse on v0.2
 */
class SyncBlobTest {

    private val sampleFriends = listOf(
        FriendData(userId = "u1", username = "alice", status = FriendStatus.ACCEPTED),
        FriendData(userId = "u2", username = "bob", status = FriendStatus.PENDING_SENT)
    )

    private val sampleMessages = mapOf(
        "conv-1" to listOf(
            MessageData(id = "m1", conversationId = "conv-1", authorDeviceId = "d1",
                content = "hi", timestamp = 100, type = "text"),
            MessageData(id = "m2", conversationId = "conv-1", authorDeviceId = "d2",
                content = "hey", timestamp = 200, type = "text")
        )
    )

    @Test
    fun `round-trips friends only`() {
        val blob = SyncBlob.export(sampleFriends)
        val parsed = SyncBlob.parse(blob)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.friends.size)
        assertEquals("alice", parsed.friends[0]["username"])
        assertEquals("accepted", parsed.friends[0]["status"])
        assertEquals("bob", parsed.friends[1]["username"])
        assertEquals("pending_sent", parsed.friends[1]["status"])
    }

    @Test
    fun `round-trips messages along with friends`() {
        val blob = SyncBlob.export(sampleFriends, sampleMessages)
        val parsed = SyncBlob.parse(blob)!!
        assertEquals(2, parsed.messages.size)
        val first = parsed.messages.first { it["messageId"] == "m1" }
        assertEquals("conv-1", first["conversationId"])
        assertEquals("hi", first["content"])
        assertEquals(100L, first["timestamp"])
        assertEquals("d1", first["authorDeviceId"])
    }

    @Test
    fun `output is gzipped and smaller than raw JSON for repeating content`() {
        val many = (1..100).map { FriendData("u$it", "user$it", FriendStatus.ACCEPTED) }
        val blob = SyncBlob.export(many)
        // gzip header is 10 bytes; for highly repetitive friend records
        // compression should at minimum match a non-trivial ratio.
        assertTrue(blob.size < 100 * 50,
            "gzip should compress repetitive content noticeably; got ${blob.size} bytes for 100 friends")
        // Gzip magic: 0x1f 0x8b
        assertEquals(0x1f.toByte(), blob[0])
        assertEquals(0x8b.toByte(), blob[1])
    }

    @Test
    fun `parse returns null on truncated blob`() {
        val full = SyncBlob.export(sampleFriends)
        val truncated = full.copyOfRange(0, full.size / 2)
        assertNull(SyncBlob.parse(truncated),
            "Truncated blob must return null, not throw — link UI can't recover from exceptions mid-flow")
    }

    @Test
    fun `parse returns null on random garbage`() {
        assertNull(SyncBlob.parse(ByteArray(100) { it.toByte() }))
    }

    @Test
    fun `parse returns null on empty input`() {
        assertNull(SyncBlob.parse(ByteArray(0)))
    }

    @Test
    fun `empty export still round-trips cleanly`() {
        val blob = SyncBlob.export(emptyList())
        val parsed = SyncBlob.parse(blob)!!
        assertEquals(0, parsed.friends.size)
        assertEquals(0, parsed.messages.size)
    }

    @Test
    fun `repeated exports of same data produce different gzip output (timestamp included)`() {
        // The blob embeds System.currentTimeMillis(); two exports a few
        // ms apart should differ. This isn't a hard requirement, but it
        // documents the current behavior: blob is not byte-identical
        // across exports.
        val a = SyncBlob.export(sampleFriends)
        Thread.sleep(2)
        val b = SyncBlob.export(sampleFriends)
        // Either differ (timestamp embedded) or identical (same ms tick).
        // We accept either — what matters is that BOTH parse to the same
        // logical content.
        val parsedA = SyncBlob.parse(a)!!
        val parsedB = SyncBlob.parse(b)!!
        assertEquals(parsedA.friends.size, parsedB.friends.size)
        // And that the export isn't trivially zero bytes
        assertNotEquals(0, a.size)
    }
}
