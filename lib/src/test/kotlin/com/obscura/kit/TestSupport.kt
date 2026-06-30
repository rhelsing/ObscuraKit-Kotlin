package com.obscura.kit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.db.ObscuraDatabase
import com.obscura.kit.orm.ModelStore

/**
 * Shared test helper: an in-memory SQLDelight DB and a fresh ModelStore.
 *
 * Unlike the integration suite, this never touches the network. Each test
 * gets its own DB instance so there's no shared-state leakage.
 *
 * Construction takes about 30ms (schema migration is the dominant cost),
 * so it's safe to call per test.
 */
fun newInMemoryStore(): ModelStore {
    return ModelStore(newInMemoryDatabase())
}

/**
 * Raw in-memory ObscuraDatabase for tests that exercise queries outside
 * the ORM layer (DeviceDomain, FriendDomain, MessageDomain, etc.).
 */
fun newInMemoryDatabase(): ObscuraDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    ObscuraDatabase.Schema.create(driver)
    return ObscuraDatabase(driver)
}
