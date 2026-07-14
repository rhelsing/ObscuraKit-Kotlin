package com.obscura.kit

import app.cash.sqldelight.db.SqlDriver

/**
 * SQLite schema migration registry for ObscuraKit.
 *
 * Migrations run once at startup (before any ORM or query operations) and are
 * protected by SQLite's built-in `user_version` pragma so they never run twice.
 * Fresh databases receive the current schema from [com.obscura.kit.db.ObscuraDatabase.Schema]
 * but still need their `user_version` stamped so later launches skip the migration.
 *
 * ## Versioning contract
 * - Every schema change that modifies an existing table requires a new migration.
 * - `CREATE TABLE IF NOT EXISTS` additions are safe without a migration entry.
 * - Version numbers are monotonically increasing integers starting at 1.
 *
 * ## Adding a new migration
 * 1. Add a version constant and increment [CURRENT_VERSION].
 * 2. Add a `VERSION_N -> { … }` branch to [runMigrations].
 * 3. Update the corresponding `.sq` file.
 * 4. Add a unit test in `FriendDomainTest` / `ModelTests` that verifies the new schema.
 */
internal object DatabaseMigrations {

    /** Increment when a new migration is added. */
    private const val CURRENT_VERSION = 1

    /**
     * Run any pending migrations against [driver], then stamp [CURRENT_VERSION].
     * Must be called before [com.obscura.kit.db.ObscuraDatabase] is used.
     *
     * Safe to call on a fresh database (the `.sq` CREATE TABLE statements are
     * `IF NOT EXISTS` so the ALTER will fail harmlessly and be swallowed).
     */
    fun migrate(driver: SqlDriver) {
        val currentVersion = getUserVersion(driver)
        for (version in (currentVersion + 1)..CURRENT_VERSION) {
            runMigrations(driver, version)
        }
    }

    private fun runMigrations(driver: SqlDriver, version: Int) {
        when (version) {
            1 -> {
                // M4 replay protection: track the last device-announce timestamp per
                // friend so replayed (or clock-rolled-back) announces are rejected.
                // ALTER TABLE is a no-op if the column already exists (fresh db with
                // the updated schema); the exception is intentionally swallowed.
                try {
                    driver.execute(null,
                        "ALTER TABLE Friend ADD COLUMN last_announce_at INTEGER NOT NULL DEFAULT 0", 0)
                } catch (_: Exception) { /* column already present — fresh database */ }
            }
        }
        // Stamp the version AFTER the migration succeeds so a crash mid-migration
        // causes the migration to re-run on the next launch rather than being skipped.
        setUserVersion(driver, version)
    }

    private fun getUserVersion(driver: SqlDriver): Int {
        var version = 0
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor ->
                if (cursor.next().value) {
                    version = cursor.getLong(0)?.toInt() ?: 0
                }
                app.cash.sqldelight.db.QueryResult.Unit
            },
            parameters = 0
        )
        return version
    }

    private fun setUserVersion(driver: SqlDriver, version: Int) {
        // user_version cannot be set via a bound parameter — it must be a literal
        driver.execute(null, "PRAGMA user_version = $version", 0)
    }
}
