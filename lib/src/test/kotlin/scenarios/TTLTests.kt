package scenarios

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.db.ObscuraDatabase
import com.obscura.kit.orm.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tier 1: TTL (Time-To-Live) unit tests — no server.
 *
 * Proves ephemeral content expiration works correctly.
 * Stories disappear after 24h, snaps after viewing, etc.
 */
class TTLTests {

    private lateinit var db: ObscuraDatabase
    private lateinit var store: ModelStore
    private lateinit var ttl: TTLManager

    @BeforeEach
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        db = ObscuraDatabase(driver)
        store = ModelStore(db)
        ttl = TTLManager(store)
    }

    // ─── TTL Parsing ──────────────────────────────────────────────

    @Test
    fun `Parse seconds`() {
        assertEquals(30_000L, ttl.parseTTL("30s"))
    }

    @Test
    fun `Parse minutes`() {
        assertEquals(1_800_000L, ttl.parseTTL("30m"))
    }

    @Test
    fun `Parse hours`() {
        assertEquals(86_400_000L, ttl.parseTTL("24h"))
    }

    @Test
    fun `Parse days`() {
        assertEquals(604_800_000L, ttl.parseTTL("7d"))
    }

    @Test
    fun `Invalid format throws`() {
        assertThrows(IllegalArgumentException::class.java) { ttl.parseTTL("abc") }
        assertThrows(IllegalArgumentException::class.java) { ttl.parseTTL("24x") }
        assertThrows(IllegalArgumentException::class.java) { ttl.parseTTL("") }
    }

    // ─── TTL Scheduling + Expiration ──────────────────────────────

    @Test
    fun `Schedule TTL sets expiration in DB`() = runBlocking {
        val entry = OrmEntry("s1", mapOf("content" to "ephemeral"), System.currentTimeMillis(), "d1")
        store.put("story", entry)

        ttl.schedule("story", "s1", "24h")

        val remaining = ttl.getTimeRemaining("story", "s1")
        assertNotNull(remaining)
        // Should be ~24h, give or take a few ms
        assertTrue(remaining!! > 86_000_000L, "Remaining should be close to 24h")
        assertTrue(remaining < 86_500_000L, "Remaining should not exceed 24h + tolerance")
    }

    @Test
    fun `Expired entry is filtered from ModelStore getAll`() = runBlocking {
        // Put entry with already-expired TTL
        store.put("story", OrmEntry("s1", mapOf("content" to "alive"), 1000L, "d1"))
        store.put("story", OrmEntry("s2", mapOf("content" to "expired"), 2000L, "d1"))

        // Set s2 to expire in the past
        store.setTTL("story", "s2", System.currentTimeMillis() - 1000)

        val all = store.getAll("story")
        assertEquals(1, all.size, "Expired entry should be filtered out")
        assertEquals("s1", all[0].id)
    }

    @Test
    fun `Expired entry returns null from ModelStore find`() = runBlocking {
        store.put("story", OrmEntry("s1", mapOf("content" to "gone"), 1000L, "d1"))
        store.setTTL("story", "s1", System.currentTimeMillis() - 1000)

        val found = store.find("story", "s1")
        assertNull(found, "Expired entry should return null from find()")
    }

    @Test
    fun `Non-expired entry is returned normally`() = runBlocking {
        store.put("story", OrmEntry("s1", mapOf("content" to "still here"), 1000L, "d1"))
        store.setTTL("story", "s1", System.currentTimeMillis() + 86_400_000)

        val found = store.find("story", "s1")
        assertNotNull(found)
        assertEquals("still here", found!!.data["content"])
    }

    @Test
    fun `Cleanup removes expired entries`() = runBlocking {
        store.put("story", OrmEntry("s1", mapOf(), 1000L, "d1"))
        store.put("story", OrmEntry("s2", mapOf(), 2000L, "d1"))
        store.setTTL("story", "s1", System.currentTimeMillis() - 1000)  // expired
        store.setTTL("story", "s2", System.currentTimeMillis() + 99999) // alive

        val cleaned = ttl.cleanup { null }
        assertEquals(1, cleaned, "Should clean 1 expired entry")

        // After cleanup, only s2 should remain
        val remaining = store.getAll("story")
        assertEquals(1, remaining.size)
        assertEquals("s2", remaining[0].id)
    }

    @Test
    fun `isExpired reports correctly`() = runBlocking {
        store.put("story", OrmEntry("s1", mapOf(), 1000L, "d1"))
        store.setTTL("story", "s1", System.currentTimeMillis() - 1000)
        assertTrue(ttl.isExpired("story", "s1"))

        store.put("story", OrmEntry("s2", mapOf(), 1000L, "d1"))
        store.setTTL("story", "s2", System.currentTimeMillis() + 99999)
        assertFalse(ttl.isExpired("story", "s2"))
    }
}
