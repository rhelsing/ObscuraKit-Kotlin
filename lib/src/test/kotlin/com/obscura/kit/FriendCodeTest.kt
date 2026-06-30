package com.obscura.kit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * FriendCode is a user-facing wire format — it's QR-encoded and pasted
 * between phones. Round-trip stability and rejection of malformed input
 * are the two invariants we must never lose.
 */
class FriendCodeTest {

    @Test
    fun `round-trips a normal userId and username`() {
        val encoded = FriendCode.encode("019f025a-3745-7de6-84b6-d932cae7d45f", "alice")
        val decoded = FriendCode.decode(encoded)
        assertEquals("019f025a-3745-7de6-84b6-d932cae7d45f", decoded.userId)
        assertEquals("alice", decoded.username)
    }

    @Test
    fun `round-trips a username with unicode`() {
        // Snapchat permits emoji-adjacent characters; make sure JSON encoding
        // doesn't drop them on the floor.
        val encoded = FriendCode.encode("uid", "✨nolan✨")
        val decoded = FriendCode.decode(encoded)
        assertEquals("✨nolan✨", decoded.username)
    }

    @Test
    fun `decode tolerates leading and trailing whitespace`() {
        val encoded = FriendCode.encode("uid", "alice")
        val decoded = FriendCode.decode("  \n$encoded\t  ")
        assertEquals("alice", decoded.username)
    }

    @Test
    fun `decode normalises url-safe base64`() {
        val encoded = FriendCode.encode("uid", "alice")
        // Some QR scanners hand us the url-safe variant. The decode contract
        // explicitly maps - -> + and _ -> /.
        val urlSafe = encoded.replace('+', '-').replace('/', '_')
        val decoded = FriendCode.decode(urlSafe)
        assertEquals("alice", decoded.username)
    }

    @Test
    fun `decode rejects empty string`() {
        assertThrows(Exception::class.java) { FriendCode.decode("") }
    }

    @Test
    fun `decode rejects non-base64 garbage`() {
        assertThrows(Exception::class.java) { FriendCode.decode("not a friend code!!!") }
    }

    @Test
    fun `decode rejects payload missing required fields`() {
        // Valid base64, valid JSON, but no "u" or "n" keys.
        val empty = java.util.Base64.getEncoder().encodeToString("{}".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { FriendCode.decode(empty) }
    }
}
