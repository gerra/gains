package app.gains.server

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.gains.server.db.ServerDatabase
import app.gains.sync.PushResult
import app.gains.sync.SyncDocument
import app.gains.sync.UserInfo
import java.io.File
import java.time.Instant
import java.util.Properties

/**
 * The server's database (docs/sync.md, "The server"). Every method takes the user it acts for;
 * nothing here can reach another user's rows. Writes that need the feed counter run in one
 * transaction with it, so `seq` is assigned in order and never reused.
 */
class Store(private val db: ServerDatabase) {
    private val q get() = db.serverQueries

    // --- users ------------------------------------------------------------------------------

    /**
     * The user behind a provider's subject, created on first sight. A new identity whose email
     * matches an existing user's joins that user, so one person with both providers is one account.
     */
    fun signIn(provider: String, subject: String, email: String?, name: String?): UserInfo = db.transactionWithResult {
        val existing = q.selectIdentity(provider, subject).executeAsOneOrNull()
        val userId = when {
            existing != null -> existing.user_id
            else -> {
                val byEmail = email?.let { q.selectUserByEmail(it).executeAsOneOrNull() }
                val id = byEmail?.id ?: run {
                    q.insertUser(email, name, Instant.now().toString())
                    q.lastInsertedId().executeAsOne()
                }
                q.insertIdentity(provider, subject, id, email)
                id
            }
        }
        // Apple sends the name and email only the first time; keep what was given, never blank it.
        q.updateUserDetails(email, name, userId)
        user(userId)!!
    }

    fun user(id: Long): UserInfo? {
        val row = q.selectUser(id).executeAsOneOrNull() ?: return null
        return UserInfo(row.id, row.email, row.name, q.selectProvidersForUser(id).executeAsList())
    }

    /** Removes the user, their identities, every document and every blob. */
    fun deleteUser(id: Long) = db.transaction {
        q.deleteBlobsForUser(id)
        q.deleteDocumentsForUser(id)
        q.deleteIdentitiesForUser(id)
        q.deleteUser(id)
    }

    // --- documents --------------------------------------------------------------------------

    /** Upserts each document unless the row already holds a newer one; every accepted write takes the next seq. */
    fun push(userId: Long, documents: List<SyncDocument>): List<PushResult> = db.transactionWithResult {
        documents.map { doc ->
            val current = q.selectDocument(userId, doc.kind, doc.id).executeAsOneOrNull()
            if (current != null && current.updated_at > doc.updatedAt) {
                PushResult(doc.kind, doc.id, current.seq, accepted = false)
            } else {
                val seq = nextSeq()
                q.upsertDocument(userId, doc.kind, doc.id, seq, doc.updatedAt, if (doc.deleted) 1L else 0L, if (doc.deleted) "" else doc.payload)
                if (doc.deleted) q.deleteBlob(userId, doc.kind, doc.id)
                PushResult(doc.kind, doc.id, seq, accepted = true)
            }
        }
    }

    /** The documents after [since] in feed order, at most [limit], and whether more follow. */
    fun pull(userId: Long, since: Long, limit: Int): Pair<List<SyncDocument>, Boolean> {
        val rows = q.selectFeed(userId, since, limit + 1L).executeAsList()
        val page = rows.take(limit).map { SyncDocument(it.kind, it.id, it.updated_at, it.deleted != 0L, it.payload, it.seq) }
        return page to (rows.size > limit)
    }

    // --- blobs ------------------------------------------------------------------------------

    /** Stores the bytes and writes their feed document in one go. Returns the seq, or null when the feed holds a newer version. */
    fun putBlob(userId: Long, kind: String, id: String, updatedAt: String, bytes: ByteArray, payload: String): Long? = db.transactionWithResult {
        val current = q.selectDocument(userId, kind, id).executeAsOneOrNull()
        if (current != null && current.updated_at > updatedAt) {
            null
        } else {
            val seq = nextSeq()
            q.upsertBlob(userId, kind, id, bytes)
            q.upsertDocument(userId, kind, id, seq, updatedAt, 0L, payload)
            seq
        }
    }

    fun blob(userId: Long, kind: String, id: String): ByteArray? = q.selectBlob(userId, kind, id).executeAsOneOrNull()

    /** The next feed position. Only called inside a transaction, so two writes never share one. */
    private fun nextSeq(): Long {
        q.incrementSeq()
        return q.selectSeq().executeAsOne()
    }

    companion object {
        /** Opens (creating or migrating) the database at [file], or an in-memory one when null. */
        fun open(file: File?): Store {
            file?.parentFile?.mkdirs()
            val url = if (file == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:${file.absolutePath}"
            val driver = JdbcSqliteDriver(url, Properties().apply { if (file != null) setProperty("journal_mode", "WAL") })
            val version = driver.executeQuery(null, "PRAGMA user_version;", { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
            }, 0).value
            if (version == 0L) {
                ServerDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA user_version = ${ServerDatabase.Schema.version};", 0)
            } else if (version < ServerDatabase.Schema.version) {
                ServerDatabase.Schema.migrate(driver, version, ServerDatabase.Schema.version)
                driver.execute(null, "PRAGMA user_version = ${ServerDatabase.Schema.version};", 0)
            }
            return Store(ServerDatabase(driver))
        }
    }
}
