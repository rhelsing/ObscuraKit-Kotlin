package scenarios

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.db.ObscuraDatabase
import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Guards the schema-migration mechanism itself (`obscura-proto/KIT_API.md` P1).
 *
 * **Why this file exists.** Until 2026-07-25 this kit had no `.sqm` files at all, so SQLDelight's
 * generated schema version never moved. Adding a table to a `.sq` file reached *new* installs only:
 * on an existing database neither `create()` nor `migrate()` runs, so the table was silently absent,
 * the first write threw inside the persist step, the kit correctly refused to ack — and that device
 * then received nothing, ever again, while the server pruned it at 1000 messages / 30 days. Silent,
 * permanent, per-device, and invisible to every existing test because every test starts from a fresh
 * database.
 *
 * Phase 3 both adds tables (the inbox) and deletes them (ORM entries, model config), so the reset
 * cannot proceed safely on a kit that can only add by luck and never remove.
 *
 * The load-bearing test is [migrated schema is identical to a freshly created one]: a schema change
 * has to be written **twice** — in the `.sq` for fresh installs and in a `.sqm` for existing ones —
 * and forgetting either half is exactly the mistake that produces the silent kill above.
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
        // 5 as of 4.sqm, which adds Friend.recovery_public_key so a peer's recovery key can be
        // pinned trust-on-first-use instead of read out of the message being verified.
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
        // The v1 shape comes from a FROZEN FIXTURE (test/resources/schema-v1.db), not from the
        // current schema. The first version of this test built "old" by taking the current schema
        // and dropping the one index it knew about — which is circular: any OTHER un-migrated
        // addition was present on both sides, so the test passed while the bug it exists to catch
        // was live. Verified: with an index added to a .sq and deliberately omitted from the .sqm,
        // the circular version reported 0 failures and this version fails.
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

    /**
     * Every subsequent app launch calls `migrate(current, current)`, and it must apply nothing.
     *
     * **This replaces a test that asserted something stronger and untrue** (2026-08-01): it created
     * a fresh database and then ran `migrate(1, current)` over it, i.e. re-applied the entire
     * migration history to a schema that already had every change. That passes only while every
     * statement in every `.sqm` is individually idempotent, and it held only by accident — the two
     * migrations that existed were `CREATE ... IF NOT EXISTS` and `DROP TABLE IF EXISTS`. SQLite has
     * no `ADD COLUMN IF NOT EXISTS`, so `4.sqm` cannot satisfy it, and neither can most real DDL.
     *
     * It also asserted a scenario SQLDelight does not produce. Migrations are applied from the
     * STORED version, never from 1, and the stored-version write happens in the same transaction as
     * the statements (on Android, `SQLiteOpenHelper` wraps `onUpgrade` and `setVersion` together),
     * so "the DDL landed but the version did not" is not a state a crash can leave behind. What
     * genuinely happens every launch after an upgrade is the range below, and this pins that a
     * `.sqm` numbered outside its version range cannot re-fire.
     */
    @Test
    fun `migrating from the current version applies nothing`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        val before = schemaOf(driver)

        ObscuraDatabase.Schema.migrate(driver, ObscuraDatabase.Schema.version, ObscuraDatabase.Schema.version)

        assertEquals(before, schemaOf(driver), "an up-to-date database must not be migrated again")
    }
}
