package com.obscura.kit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.db.ObscuraDatabase

/**
 * Raw in-memory ObscuraDatabase for the store tests (DeviceDomain, FriendDomain, InboxDomain,
 * EntryStore).
 *
 * `newInMemoryStore()` went with the ORM — it built a `ModelStore`, which no longer exists.
 *
 * Unlike the integration suite, this never touches the network. Each test gets its own DB instance
 * so there is no shared-state leakage. Construction takes about 30ms (schema migration dominates),
 * so it is safe to call per test.
 */
fun newInMemoryDatabase(): ObscuraDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    ObscuraDatabase.Schema.create(driver)
    return ObscuraDatabase(driver)
}
