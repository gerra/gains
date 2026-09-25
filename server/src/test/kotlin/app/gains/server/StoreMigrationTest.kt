package app.gains.server

import java.io.File
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** A database file from before `email_verified` (schema 1) opens, migrates through every later migration and keeps its users. */
class StoreMigrationTest {
    private val dir = createTempDirectory("gains-store").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun schemaOneWith(file: File, statements: List<String>) {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { st ->
                for (sql in SCHEMA_ONE + statements) st.execute(sql)
                st.execute("PRAGMA user_version = 1")
            }
        }
    }

    @Test
    fun identitiesFromBeforeAreUnverifiedUntilTheirNextSignIn() {
        val file = File(dir, "gains.db")
        schemaOneWith(file, listOf(
            "INSERT INTO user(email, name, created_at) VALUES ('same@x.y', 'Ada', '2026-09-01T00:00:00Z')",
            "INSERT INTO identity(provider, subject, user_id, email) VALUES ('apple', 'a-1', 1, 'same@x.y')",
        ))
        val store = Store.open(file)

        val before = store.signIn("google", "g-1", "same@x.y", emailVerified = true, name = null)
        assertNotEquals(1L, before.id, "an old row's email is not trusted")

        val apple = store.signIn("apple", "a-1", "same@x.y", emailVerified = true, name = null)
        assertEquals(1L, apple.id)
        assertEquals("Ada", apple.name)
        val after = store.signIn("google", "g-2", "same@x.y", emailVerified = true, name = null)
        assertEquals(1L, after.id)
        assertEquals(listOf("apple", "google"), after.providers)
    }

    @Test
    fun appleIdentitiesFromBeforeHaveNoRefreshTokenUntilTheirNextSignIn() {
        val file = File(dir, "gains.db")
        schemaOneWith(file, listOf(
            "INSERT INTO user(email, name, created_at) VALUES ('a@x.y', 'Ada', '2026-09-01T00:00:00Z')",
            "INSERT INTO identity(provider, subject, user_id, email) VALUES ('apple', 'a-1', 1, 'a@x.y')",
        ))
        val store = Store.open(file)
        assertEquals(emptyList(), store.refreshTokens(1, "apple"))

        store.setRefreshToken("apple", "a-1", "rt-1", "app.gains.Gains")
        assertEquals(listOf("rt-1" to "app.gains.Gains"), store.refreshTokens(1, "apple"))
    }

    private companion object {
        /** The server's tables as they were created before migrations/1.sqm. */
        val SCHEMA_ONE = listOf(
            "CREATE TABLE user (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, email TEXT, name TEXT, created_at TEXT NOT NULL)",
            "CREATE TABLE identity (provider TEXT NOT NULL, subject TEXT NOT NULL, user_id INTEGER NOT NULL REFERENCES user(id), email TEXT, PRIMARY KEY (provider, subject))",
            "CREATE INDEX identity_user ON identity(user_id)",
            "CREATE TABLE document (user_id INTEGER NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, seq INTEGER NOT NULL, updated_at TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, payload TEXT NOT NULL DEFAULT '', PRIMARY KEY (user_id, kind, id))",
            "CREATE INDEX document_feed ON document(user_id, seq)",
            "CREATE TABLE blob (user_id INTEGER NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, bytes BLOB NOT NULL, PRIMARY KEY (user_id, kind, id))",
            "CREATE TABLE counter (name TEXT NOT NULL PRIMARY KEY, value INTEGER NOT NULL)",
            "INSERT INTO counter(name, value) VALUES ('seq', 0)",
        )
    }
}
