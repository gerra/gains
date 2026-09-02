package app.gains.data

import app.cash.sqldelight.db.SqlDriver

/** Platform-specific SQLite driver creation. Each platform registers its actual in Koin. */
interface DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}
