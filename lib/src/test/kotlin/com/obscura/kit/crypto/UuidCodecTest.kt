package com.obscura.kit.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * UuidCodec is the wire-format bridge between Kotlin's UUID and protobuf
 * ByteString used by MessengerDomain. Round-trip is the invariant that
 * matters — a regression here corrupts every message envelope.
 */
class UuidCodecTest {

    @Test
    fun `round-trips a random uuid`() {
        repeat(100) {
            val uuid = UUID.randomUUID()
            val bytes = UuidCodec.uuidToBytes(uuid).toByteArray()
            assertEquals(16, bytes.size, "UUID must encode to exactly 16 bytes")
            assertEquals(uuid, UuidCodec.bytesToUuid(bytes))
        }
    }

    @Test
    fun `round-trips the nil uuid`() {
        val nil = UUID(0, 0)
        val bytes = UuidCodec.uuidToBytes(nil).toByteArray()
        assertEquals(nil, UuidCodec.bytesToUuid(bytes))
    }

    @Test
    fun `round-trips uuid with max bits`() {
        val max = UUID(-1L, -1L)
        val bytes = UuidCodec.uuidToBytes(max).toByteArray()
        assertEquals(max, UuidCodec.bytesToUuid(bytes))
    }

    @Test
    fun `bytesToUuid returns nil on short input rather than crashing`() {
        // Defensive: protobuf decoding accidents shouldn't blow up the
        // whole message pipeline. Returning UUID(0,0) is a sentinel that
        // higher layers can detect (no real user has the nil UUID).
        assertEquals(UUID(0, 0), UuidCodec.bytesToUuid(ByteArray(0)))
        assertEquals(UUID(0, 0), UuidCodec.bytesToUuid(ByteArray(15)))
    }

    @Test
    fun `encoding is big-endian most-then-least`() {
        // Lock the byte order. Changing this silently breaks every
        // device that already shipped, including iOS interop.
        val uuid = UUID(0x0123456789ABCDEFL, 0xFEDCBA9876543210uL.toLong())
        val bytes = UuidCodec.uuidToBytes(uuid).toByteArray()
        assertEquals(0x01.toByte(), bytes[0])
        assertEquals(0xEF.toByte(), bytes[7])
        assertEquals(0xFE.toByte(), bytes[8])
        assertEquals(0x10.toByte(), bytes[15])
    }
}
