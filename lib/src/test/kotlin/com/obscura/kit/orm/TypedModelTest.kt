package com.obscura.kit.orm

import com.obscura.kit.newInMemoryStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TypedModel is the app-developer-facing surface: `@Serializable` in, decoded
 * value out. It layers kotlinx.serialization over the untyped Map-based Model,
 * so a bug here (dropped field, silent decode failure) is invisible until an
 * app reads garbage. These tests exercise the encode/decode round-trip and the
 * typed query builder without any network.
 */
class TypedModelTest {

    @Serializable
    data class Note(val title: String, val body: String, val likes: Int)

    private fun notes(): TypedModel<Note> {
        val store = newInMemoryStore()
        val schema = Schema(store, SyncManager(store), TTLManager(store), deviceIdProvider = { "dev-1" })
        schema.define(mapOf("note" to ModelConfig(
            fields = mapOf("title" to "string", "body" to "string", "likes" to "number"),
            sync = "lww"
        )))
        return TypedModel.wrap(schema.model("note"))
    }

    @Test
    fun `create round-trips the value and stamps the device id`() = runTest {
        val notes = notes()
        val entry = notes.create(Note("Hello", "World", 3))

        assertEquals(Note("Hello", "World", 3), entry.value)
        assertEquals("dev-1", entry.authorDeviceId)
        assertTrue(entry.id.startsWith("note_"))
        assertTrue(entry.timestamp > 0)
    }

    @Test
    fun `find decodes a stored entry and returns null when absent`() = runTest {
        val notes = notes()
        val created = notes.upsert("n1", Note("Pinned", "Body", 0))

        assertEquals(created.value, notes.find("n1")?.value)
        assertNull(notes.find("missing"))
    }

    @Test
    fun `all and allSorted decode every entry`() = runTest {
        val notes = notes()
        notes.upsert("n1", Note("A", "x", 1))
        notes.upsert("n2", Note("B", "y", 2))

        assertEquals(setOf("A", "B"), notes.all().map { it.value.title }.toSet())
        assertEquals(2, notes.allSorted().size)
    }

    @Test
    fun `upsert overwrites the same id`() = runTest {
        val notes = notes()
        notes.upsert("n1", Note("First", "b", 1))
        notes.upsert("n1", Note("Second", "b", 9))

        assertEquals(1, notes.all().size)
        assertEquals(Note("Second", "b", 9), notes.find("n1")?.value)
    }

    @Test
    fun `delete removes the entry from reads`() = runTest {
        val notes = notes()
        notes.upsert("n1", Note("Doomed", "b", 0))
        notes.delete("n1")

        assertNull(notes.find("n1"))
        assertTrue(notes.all().isEmpty())
    }

    // ── typed query builder ─────────────────────────────────────────────

    @Test
    fun `where with a condition map filters and decodes`() = runTest {
        val notes = notes()
        notes.upsert("n1", Note("keep", "match", 5))
        notes.upsert("n2", Note("drop", "other", 5))

        val result = notes.where(mapOf("data.body" to "match")).exec()
        assertEquals(listOf("keep"), result.map { it.value.title })
    }

    @Test
    fun `where DSL block with orderBy and limit`() = runTest {
        val notes = notes()
        notes.upsert("n1", Note("alice-1", "b", 10))
        notes.upsert("n2", Note("alice-2", "b", 50))
        notes.upsert("n3", Note("bob-1", "b", 99))

        val result = notes
            .where { "body" eq "b" }
            .orderBy("likes", "desc")
            .limit(2)
            .exec()

        assertEquals(listOf(99, 50), result.map { it.value.likes })
    }

    @Test
    fun `first returns the top match and count returns the total`() = runTest {
        val notes = notes()
        notes.upsert("n1", Note("a", "b", 1))
        notes.upsert("n2", Note("c", "b", 2))

        assertEquals(2, notes.where(mapOf("data.body" to "b")).count())
        assertEquals("a", notes.where(mapOf("data.title" to "a")).first()?.value?.title)
        assertNull(notes.where(mapOf("data.title" to "nope")).first())
    }

    // ── decode resilience ───────────────────────────────────────────────

    @Test
    fun `entries that cannot be decoded are skipped rather than crashing`() = runTest {
        // Write a raw entry that satisfies the (permissive) schema but is
        // missing a field the strict Note serializer requires. TypedModel must
        // drop it silently instead of throwing on read.
        val store = newInMemoryStore()
        val schema = Schema(store, SyncManager(store), TTLManager(store), deviceIdProvider = { "d" })
        schema.define(mapOf("note" to ModelConfig(
            fields = mapOf("title" to "string?", "body" to "string?", "likes" to "number?"),
            sync = "lww"
        )))
        val raw = schema.model("note")
        raw.upsert("good", mapOf("title" to "T", "body" to "B", "likes" to 1))
        raw.upsert("bad", mapOf("title" to "OnlyTitle"))

        val typed = TypedModel.wrap<Note>(raw)
        assertEquals(listOf("T"), typed.all().map { it.value.title })
        assertNull(typed.find("bad"))
    }

    @Test
    fun `observe emits decoded typed entries`() = runTest {
        val notes = notes()
        notes.upsert("n1", Note("Observed", "b", 0))

        val emitted = notes.observe().first()
        assertEquals(listOf("Observed"), emitted.map { it.value.title })
    }
}
