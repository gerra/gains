package app.gains.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gains.db.GainsDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Upgrades a database created by the previous schema version through the `.sqm` migrations and
 * checks it ends up identical to a fresh install. This is the only guard against a migration typo
 * reaching phones that already have data.
 */
class MigrationTest {
    /** Schema version 2: the tables as they were before programs and equipment existed. */
    private val v2 = listOf(
        """CREATE TABLE session (
          id TEXT NOT NULL PRIMARY KEY,
          timestamp TEXT NOT NULL,
          date TEXT NOT NULL,
          duration_minutes INTEGER,
          fingerprint TEXT NOT NULL,
          content_hash TEXT NOT NULL,
          source TEXT NOT NULL DEFAULT 'import'
        )""",
        "CREATE INDEX session_date ON session(date)",
        """CREATE TABLE exercise_entry (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          session_id TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
          exercise_id TEXT NOT NULL,
          position INTEGER NOT NULL,
          note TEXT
        )""",
        "CREATE INDEX exercise_entry_session ON exercise_entry(session_id)",
        "CREATE INDEX exercise_entry_exercise ON exercise_entry(exercise_id)",
        """CREATE TABLE set_entry (
          id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
          entry_id INTEGER NOT NULL REFERENCES exercise_entry(id) ON DELETE CASCADE,
          set_order INTEGER NOT NULL,
          type TEXT NOT NULL,
          weight_kg REAL,
          reps INTEGER,
          seconds INTEGER,
          distance_km REAL,
          rpe REAL
        )""",
        "CREATE INDEX set_entry_entry ON set_entry(entry_id)",
        """CREATE TABLE exercise (
          id TEXT NOT NULL PRIMARY KEY,
          name TEXT NOT NULL,
          canonical_name TEXT NOT NULL,
          modality TEXT NOT NULL,
          is_dumbbell INTEGER NOT NULL DEFAULT 0,
          is_builtin INTEGER NOT NULL DEFAULT 0,
          muscles TEXT NOT NULL
        )""",
        "CREATE TABLE exercise_alias (raw_name TEXT NOT NULL PRIMARY KEY, exercise_id TEXT NOT NULL)",
        "CREATE TABLE exercise_override (exercise_id TEXT NOT NULL PRIMARY KEY, working_set_ratio REAL)",
        "CREATE TABLE bodyweight (date TEXT NOT NULL PRIMARY KEY, weight_kg REAL NOT NULL)",
        "CREATE TABLE setting (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
    )

    private fun columns(driver: SqlDriver, table: String): List<String> =
        driver.executeQuery(null, "PRAGMA table_info($table);", { cursor ->
            val out = ArrayList<String>()
            while (cursor.next().value) out.add("${cursor.getString(1)}:${cursor.getString(2)}:${cursor.getLong(3)}:${cursor.getString(4)}")
            QueryResult.Value(out)
        }, 0).value

    private fun tables(driver: SqlDriver): List<String> =
        driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%' ORDER BY name;", { cursor ->
            val out = ArrayList<String>()
            while (cursor.next().value) out.add(cursor.getString(0)!!)
            QueryResult.Value(out)
        }, 0).value

    @Test
    fun upgradeFromV2MatchesFreshSchema() {
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        GainsDatabase.Schema.create(fresh)

        val upgraded = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        for (ddl in v2) upgraded.execute(null, ddl, 0)
        upgraded.execute(null, "INSERT INTO session(id, timestamp, date, fingerprint, content_hash) VALUES ('s1', '2026-01-01T10:00', '2026-01-01', 'f', 'h');", 0)
        upgraded.execute(null, "INSERT INTO exercise(id, name, canonical_name, modality, muscles) VALUES ('bench_press', 'Bench Press', 'Bench Press', 'WEIGHTED', 'CHEST:1.0');", 0)
        GainsDatabase.Schema.migrate(upgraded, 2, GainsDatabase.Schema.version)

        assertEquals(tables(fresh), tables(upgraded))
        for (table in listOf("session", "exercise", "program", "program_day", "program_slot", "strava_link")) {
            assertEquals(columns(fresh, table), columns(upgraded, table), "columns of $table")
        }
        // Existing rows survive with the new columns defaulted.
        val db = GainsDatabase(upgraded)
        val session = db.sessionQueries.selectSessions().executeAsList().single()
        assertEquals(null, session.program_id)
        assertEquals("", db.exerciseQueries.selectExercises().executeAsList().single().equipment)
        // The Strava table arrived in version 4 and starts empty.
        assertEquals(0, db.stravaQueries.selectLinks().executeAsList().size)
        assertTrue(GainsDatabase.Schema.version >= 4)
    }
}
