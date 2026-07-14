package com.obscura.kit

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * SQLite schema migration registry for ObscuraKit.
 *
 * ## Why this does not use `PRAGMA user_version`
 *
 * `user_version` is **not ours to own**. SQLDelight's `AndroidSqliteDriver` is built on
 * `SupportSQLiteOpenHelper`, which stores *its* schema version (`ObscuraDatabase.Schema.version`)
 * in exactly that pragma. Production Android apps pass an `AndroidSqliteDriver`, so by the time
 * the kit looks, `user_version` already equals the SQLDelight schema version — not a count of
 * ObscuraKit migrations. A migration registry keyed on `user_version` therefore reads a number it
 * did not write, concludes it is already up to date, and silently skips every migration on
 * precisely the platform that needs them. Fresh installs look fine (`Schema.create` builds the
 * current shape), so the failure only appears on *upgrade* installs — the worst place to find it.
 *
 * Instead, each migration is **idempotent and introspection-guarded**: it asks the database what
 * shape it is actually in (`PRAGMA table_info`) and applies only what is missing. That is correct
 * for a fresh database, an upgraded one, and an arbitrary caller-supplied driver alike, and it
 * cannot drift out of step with a version counter someone else maintains.
 *
 * ## Adding a migration
 * 1. Add a step to [migrate] that is safe to run repeatedly.
 * 2. Guard it with an introspection check (see [columnExists]) — never with a try/catch that
 *    swallows failures, which cannot tell "already applied" from "genuinely broken".
 * 3. Update the corresponding `.sq` file so fresh databases get the same shape.
 */
internal object DatabaseMigrations {

    /**
     * Bring [driver]'s schema up to date. Idempotent — safe to run on every startup, on a fresh
     * database, and on one created by an external driver.
     *
     * Failures are **not** swallowed: if a migration genuinely cannot apply (locked database, I/O
     * error) this throws, rather than letting the process run on against a schema that silently
     * lacks the column every announce write depends on.
     */
    fun migrate(driver: SqlDriver) {
        // M4 replay protection: track the last accepted device-announce timestamp per friend,
        // so replayed (or clock-rolled-back) announces can be rejected.
        if (!columnExists(driver, table = "Friend", column = "last_announce_at")) {
            driver.execute(
                null,
                "ALTER TABLE Friend ADD COLUMN last_announce_at INTEGER NOT NULL DEFAULT 0",
                0,
            )
        }
    }

    /**
     * True if [table] currently has a column named [column].
     *
     * `PRAGMA table_info` returns one row per column, with the column name at index 1. A missing
     * table yields zero rows, and therefore `false`.
     */
    private fun columnExists(driver: SqlDriver, table: String, column: String): Boolean {
        var found = false
        driver.executeQuery(
            identifier = null,
            // PRAGMA arguments cannot be bound. `table` is a kit-internal literal, never user input.
            sql = "PRAGMA table_info($table)",
            mapper = { cursor ->
                while (cursor.next().value) {
                    if (cursor.getString(1) == column) {
                        found = true
                        break
                    }
                }
                QueryResult.Unit
            },
            parameters = 0,
        )
        return found
    }
}
