package scenarios

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.db.ObscuraDatabase
import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Guards schema migration for existing encrypted databases.
 *
 * A schema change must appear in both `.sq` files for fresh installs and a
 * `.sqm` migration for existing installs. The principal invariant is that a
 * migrated schema is identical to a freshly created schema.
 */
class SchemaMigrationTests {

    /** `type name sql` for every object in the database, excluding SQLite's internal tables. */
    private fun schemaOf(driver: SqlDriver): List<String> =
        driver.executeQuery(
            null,
            """
            SELECT type || ' ' || name || ' ' || COALESCE(sql, '')
            FROM sqlite_master
            WHERE name NOT LIKE 'sqlite_%'
            ORDER BY type, name
            """.trimIndent(),
            { cursor ->
                val rows = mutableListOf<String>()
                while (cursor.next().value) rows.add(cursor.getString(0) ?: "")
                QueryResult.Value(rows.toList())
            },
            0
        ).value

    @Test
    fun `schema version is derived from migrations and must be bumped deliberately`() {
        // A tripwire, not a fact worth asserting for its own sake: if you add or remove a table,
        // this number MUST move, because moving it is what makes existing installs run migrate().
        // If this fails, do not just update the constant — add the matching .sqm.
        // 4.sqm adds Friend.recovery_public_key for trust-on-first-use pinning.
        assertEquals(5L, ObscuraDatabase.Schema.version,
            "schema version = (highest .sqm number) + 1; a schema change without a new .sqm never " +
                "reaches an existing install")
    }

    @Test
    fun `migrated schema is identical to a freshly created one`() {
        // Path A — a NEW install: Schema.create() runs the CREATE statements in the .sq files.
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(fresh)

        // Path B — a REAL v1 install, upgraded.
        //
        // The v1 shape is a frozen fixture, not a derivative of the current
        // schema; deriving both sides from current DDL would be circular.
        val work = File.createTempFile("obscura-schema-v1-", ".db").apply { deleteOnExit() }
        javaClass.getResourceAsStream("/schema-v1.db").use { input ->
            requireNotNull(input) { "schema-v1.db fixture missing from test resources" }
            work.outputStream().use { input.copyTo(it) }
        }
        val migrated = JdbcSqliteDriver("jdbc:sqlite:${work.absolutePath}")
        ObscuraDatabase.Schema.migrate(migrated, 1L, ObscuraDatabase.Schema.version)

        val freshSchema = schemaOf(fresh)
        val migratedSchema = schemaOf(migrated)

        assertEquals(freshSchema, migratedSchema,
            "a migrated database must end up with exactly the schema a fresh install gets. A " +
                "difference means a change was written to the .sq but not the .sqm (existing " +
                "installs silently lack it) or to the .sqm but not the .sq (new installs do)."
        )
        // Cheap sanity check that the comparison is not vacuously comparing two empty lists.
        assertTrue(freshSchema.any { it.contains("Friend") }, "expected the Friend table in both")
        assertTrue(freshSchema.any { it.contains("friend_status_idx") },
            "expected the migration's index in the fresh schema too — that is the double-entry")
    }

    /** A current-version open must not reapply any migration. */
    @Test
    fun `migrating from the current version applies nothing`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        val before = schemaOf(driver)

        ObscuraDatabase.Schema.migrate(driver, ObscuraDatabase.Schema.version, ObscuraDatabase.Schema.version)

        assertEquals(before, schemaOf(driver), "an up-to-date database must not be migrated again")
    }
}
