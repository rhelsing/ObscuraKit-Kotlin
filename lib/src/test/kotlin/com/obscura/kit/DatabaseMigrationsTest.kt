package com.obscura.kit

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Guards [DatabaseMigrations] against the failure mode that a `user_version`-keyed registry has:
 * it reads a number SQLDelight/Android already wrote, and skips migrations it has not run.
 */
class DatabaseMigrationsTest {

    private fun driver(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    /** The pre-`last_announce_at` shape of the Friend table, as an older install would have it. */
    private fun createLegacyFriendTable(driver: SqlDriver) {
        driver.execute(
            null,
            """
            CREATE TABLE Friend (
                user_id TEXT NOT NULL PRIMARY KEY,
                username TEXT NOT NULL,
                status TEXT NOT NULL,
                devices TEXT NOT NULL DEFAULT '[]'
            )
            """.trimIndent(),
            0,
        )
    }

    private fun columns(driver: SqlDriver, table: String): List<String> {
        val names = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            mapper = { cursor ->
                while (cursor.next().value) cursor.getString(1)?.let(names::add)
                QueryResult.Unit
            },
            parameters = 0,
        )
        return names
    }

    private fun setUserVersion(driver: SqlDriver, version: Int) =
        driver.execute(null, "PRAGMA user_version = $version", 0)

    @Test
    fun `adds last_announce_at to a legacy Friend table`() {
        val db = driver()
        createLegacyFriendTable(db)
        assertFalse("last_announce_at" in columns(db, "Friend"), "precondition: column absent")

        DatabaseMigrations.migrate(db)

        assertTrue("last_announce_at" in columns(db, "Friend"), "migration must add the column")
        db.close()
    }

    /**
     * The regression this class exists for.
     *
     * `AndroidSqliteDriver` is backed by `SupportSQLiteOpenHelper`, which persists
     * `ObscuraDatabase.Schema.version` (currently 1) in `PRAGMA user_version`. So on a real Android
     * upgrade install the pragma already reads 1 while the Friend table is still the *old* shape.
     * A migration registry that trusts `user_version` as its own bookkeeping concludes "already at
     * version 1, nothing to do" and never adds the column — and every subsequent
     * `getLastAnnounceAt` / `updateDevicesAndAnnounceTime` fails with "no such column".
     *
     * Migrations must therefore key off the schema's actual shape, not that counter.
     */
    @Test
    fun `still migrates when user_version was already stamped by SQLDelight or Android`() {
        val db = driver()
        createLegacyFriendTable(db)
        setUserVersion(db, 1) // what AndroidSqliteDriver/SupportSQLiteOpenHelper leaves behind

        DatabaseMigrations.migrate(db)

        assertTrue(
            "last_announce_at" in columns(db, "Friend"),
            "a pre-stamped user_version must not cause the migration to be skipped",
        )
        db.close()
    }

    @Test
    fun `is idempotent — running twice is a no-op and preserves data`() {
        val db = driver()
        createLegacyFriendTable(db)

        DatabaseMigrations.migrate(db)
        db.execute(
            null,
            "INSERT INTO Friend (user_id, username, status, last_announce_at) VALUES ('u1','alice','accepted',42)",
            0,
        )
        DatabaseMigrations.migrate(db) // second run must not throw or clobber

        assertEquals(1, columns(db, "Friend").count { it == "last_announce_at" })

        var stored = -1L
        db.executeQuery(
            identifier = null,
            sql = "SELECT last_announce_at FROM Friend WHERE user_id = 'u1'",
            mapper = { cursor ->
                if (cursor.next().value) stored = cursor.getLong(0) ?: -1L
                QueryResult.Unit
            },
            parameters = 0,
        )
        assertEquals(42L, stored, "re-running the migration must not clobber existing rows")
        db.close()
    }

    @Test
    fun `is a no-op on a fresh database that already has the column`() {
        val db = driver()
        ObscuraDatabaseTestSchema.create(db)

        DatabaseMigrations.migrate(db) // must not throw on the already-current shape

        assertTrue("last_announce_at" in columns(db, "Friend"))
        db.close()
    }
}

/** Creates the current Friend shape, as `Schema.create` would for a fresh install. */
private object ObscuraDatabaseTestSchema {
    fun create(driver: SqlDriver) {
        driver.execute(
            null,
            """
            CREATE TABLE Friend (
                user_id TEXT NOT NULL PRIMARY KEY,
                username TEXT NOT NULL,
                status TEXT NOT NULL,
                devices TEXT NOT NULL DEFAULT '[]',
                last_announce_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            0,
        )
    }
}
