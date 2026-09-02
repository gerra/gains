package app.gains.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gains.db.GainsDatabase
import java.io.File

/**
 * JVM driver. Pass `null` to get an in-memory database (used by tests); otherwise the
 * database file lives in the user's home directory.
 */
class DesktopDriverFactory(private val file: File? = defaultFile()) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver {
        val url = if (file == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:${file.absolutePath}"
        file?.parentFile?.mkdirs()
        val driver = JdbcSqliteDriver(url)
        val version = driver.executeQuery(null, "PRAGMA user_version;", { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        }, 0).value
        if (version == 0L) {
            GainsDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA user_version = ${GainsDatabase.Schema.version};", 0)
        } else if (version < GainsDatabase.Schema.version) {
            GainsDatabase.Schema.migrate(driver, version, GainsDatabase.Schema.version)
            driver.execute(null, "PRAGMA user_version = ${GainsDatabase.Schema.version};", 0)
        }
        return driver
    }

    companion object {
        fun defaultFile(): File = File(System.getProperty("user.home"), ".gains/gains.db")
    }
}
