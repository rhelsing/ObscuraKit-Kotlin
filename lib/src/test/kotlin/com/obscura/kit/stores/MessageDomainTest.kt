package com.obscura.kit.stores

import com.obscura.kit.newInMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * MessageDomain: per-conversation message storage with pagination,
 * conversation migration (deviceId rotation), and bulk delete by
 * author device (used on device-revocation).
 */
class MessageDomainTest {

    private fun newDomain() = MessageDomain(newInMemoryDatabase())

    private fun msg(id: String, conv: String = "conv-1", author: String = "d1",
                   content: String = "hi", ts: Long = 100, type: String = "text") =
        MessageData(id = id, conversationId = conv, authorDeviceId = author,
                    content = content, timestamp = ts, type = type)

    @Test
    fun `add then getMessages returns the message`() = runTest {
        val d = newDomain()
        d.add("conv-1", msg("m1", content = "hello"))

        val list = d.getMessages("conv-1")
        assertEquals(1, list.size)
        assertEquals("hello", list[0].content)
        assertEquals("d1", list[0].authorDeviceId)
        assertEquals(100L, list[0].timestamp)
    }

    @Test
    fun `getMessages filters by conversationId`() = runTest {
        val d = newDomain()
        d.add("conv-1", msg("m1", conv = "conv-1"))
        d.add("conv-2", msg("m2", conv = "conv-2"))

        assertEquals(listOf("m1"), d.getMessages("conv-1").map { it.id })
        assertEquals(listOf("m2"), d.getMessages("conv-2").map { it.id })
    }

    @Test
    fun `getMessages respects limit`() = runTest {
        val d = newDomain()
        for (i in 1..10) d.add("conv-1", msg("m$i", ts = i.toLong()))

        val limited = d.getMessages("conv-1", limit = 3)
        assertEquals(3, limited.size)
    }

    @Test
    fun `getMessages respects offset for pagination`() = runTest {
        val d = newDomain()
        for (i in 1..10) d.add("conv-1", msg("m$i", ts = i.toLong()))

        val page1 = d.getMessages("conv-1", limit = 4, offset = 0)
        val page2 = d.getMessages("conv-1", limit = 4, offset = 4)
        assertEquals(4, page1.size)
        assertEquals(4, page2.size)
        // Pages must not overlap
        val overlap = page1.map { it.id }.toSet() intersect page2.map { it.id }.toSet()
        assertEquals(emptySet<String>(), overlap, "Pagination must yield disjoint pages")
    }

    @Test
    fun `migrateMessages moves messages from old conversationId to new`() = runTest {
        // Used when a friend's deviceId changes — their old per-device
        // conversation rolls into the new one.
        val d = newDomain()
        d.add("old-conv", msg("m1"))
        d.add("old-conv", msg("m2"))

        d.migrateMessages(from = "old-conv", to = "new-conv")

        assertEquals(0, d.getMessages("old-conv").size, "Old conversation must be empty after migration")
        assertEquals(2, d.getMessages("new-conv").size, "New conversation must hold the migrated messages")
    }

    @Test
    fun `deleteByAuthorDevice removes only that authors messages`() = runTest {
        // Device revocation: when device-A is revoked, blow away anything
        // authored from device-A across all conversations.
        val d = newDomain()
        d.add("conv-1", msg("m1", author = "device-A"))
        d.add("conv-1", msg("m2", author = "device-B"))
        d.add("conv-2", msg("m3", author = "device-A"))

        d.deleteByAuthorDevice("device-A")

        val conv1 = d.getMessages("conv-1")
        val conv2 = d.getMessages("conv-2")
        assertEquals(setOf("m2"), conv1.map { it.id }.toSet())
        assertEquals(emptyList<String>(), conv2.map { it.id })
    }

    @Test
    fun `type field round-trips for non-text messages`() = runTest {
        val d = newDomain()
        d.add("conv-1", msg("m1", content = "https://...", type = "image"))
        assertEquals("image", d.getMessages("conv-1").first().type)
    }
}
