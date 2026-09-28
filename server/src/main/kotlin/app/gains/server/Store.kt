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
 * A write that would take a user past the document ceiling ([Services.maxDocumentsPerUser]).
 * Nothing of it was stored; the routes answer 413.
 */
class TooManyDocumentsException(val max: Long) : Exception("over $max documents for this account")

/**
 * An upload that would take a user's photos past the byte ceiling ([Services.maxBlobBytesPerUser]).
 * Nothing of it was stored; the routes answer 507.
 */
class StorageFullException(val max: Long) : Exception("over $max bytes of photos for this account")

/**
 * The server's database (docs/sync.md, "The server"). Every method takes the user it acts for;
 * nothing here can reach another user's rows. Writes that need the feed counter run in one
 * transaction with it, so `seq` is assigned in order and never reused.
 */
class Store(private val db: ServerDatabase) {
    private val q get() = db.serverQueries

    // --- users ------------------------------------------------------------------------------

    /**
     * The user behind a provider's subject, created on first sight. A new identity whose email is
     * verified joins an existing user one of whose identities has the same verified email
     * (compared ignoring case), so one person with both providers is one account. An unverified
     * email is stored on the identity but never merged on, on either side: otherwise anyone who
     * could get a provider to issue a token for an unconfirmed address could walk into, or be
     * joined by, the account that owns it.
     */
    fun signIn(provider: String, subject: String, email: String?, emailVerified: Boolean, name: String?): UserInfo = db.transactionWithResult {
        val verifiedEmail = email?.takeIf { emailVerified }
        val verified = if (verifiedEmail != null) 1L else 0L
        val existing = q.selectIdentity(provider, subject).executeAsOneOrNull()
        val userId = when {
            existing != null -> {
                // Keep the claim current, so rows from before migrations/1.sqm catch up; a token
                // without an email leaves the row as it was.
                if (email != null) q.updateIdentityEmail(email, verified, provider, subject)
                existing.user_id
            }
            else -> {
                val byEmail = verifiedEmail?.let { q.selectUserIdByVerifiedEmail(it).executeAsOneOrNull() }
                val id = byEmail ?: run {
                    q.insertUser(email, name, Instant.now().toString())
                    q.lastInsertedId().executeAsOne()
                }
                q.insertIdentity(provider, subject, id, email, verified)
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

    /**
     * Keeps the refresh token Apple issued for this identity, replacing an older one: only the
     * latest is needed, since revoking any of them removes the app from the person's Apple ID.
     */
    fun setRefreshToken(provider: String, subject: String, refreshToken: String, clientId: String) =
        q.updateIdentityRefreshToken(refreshToken, clientId, provider, subject)

    /** The [provider]'s refresh tokens stored for the user, each with the client id it was issued to. */
    fun refreshTokens(userId: Long, provider: String): List<Pair<String, String>> =
        q.selectRefreshTokensForUser(userId, provider).executeAsList().mapNotNull { row ->
            val token = row.refresh_token ?: return@mapNotNull null
            val clientId = row.refresh_client_id ?: return@mapNotNull null
            token to clientId
        }

    /** Removes the user, their identities and password, every document and every blob. */
    fun deleteUser(id: Long) = db.transaction {
        for (email in q.selectCredentialEmailsForUser(id).executeAsList()) q.deleteEmailTokensForEmail(email)
        q.deleteCredentialsForUser(id)
        q.deleteBlobsForUser(id)
        q.deleteDocumentsForUser(id)
        q.deleteIdentitiesForUser(id)
        q.deleteUser(id)
    }

    // --- password credentials ---------------------------------------------------------------

    /** An email sign-up: [userId] is null until the address is confirmed ([confirmCredential]). */
    data class Credential(val email: String, val hash: String, val userId: Long?)

    fun credential(email: String): Credential? =
        q.selectCredential(email).executeAsOneOrNull()?.let { Credential(it.email, it.hash, it.user_id) }

    fun createCredential(email: String, hash: String, createdAt: String) = q.insertCredential(email, hash, createdAt)

    fun setCredentialHash(email: String, hash: String) = q.updateCredentialHash(hash, email)

    /**
     * Gives a confirmed address its user, through [signIn] with the provider `password` and the
     * address as subject, so that the verified-email rule decides whether it joins an existing
     * account. Already confirmed: the same user again. Null when there is no such sign-up.
     */
    fun confirmCredential(email: String): UserInfo? = db.transactionWithResult {
        val credential = credential(email) ?: return@transactionWithResult null
        credential.userId?.let { return@transactionWithResult user(it) }
        val user = signIn(Providers.PASSWORD, credential.email.lowercase(), credential.email, emailVerified = true, name = null)
        q.updateCredentialUser(user.id, credential.email)
        user
    }

    /**
     * Stores a mailed link's token (hashed) with its expiry. One link per address and purpose: a
     * new one replaces the last, so a repeated sign-up or reset request leaves the earlier mail
     * dead. The tokens that have expired by [now] are dropped on the way.
     */
    fun putEmailToken(tokenHash: String, email: String, purpose: String, expiresAt: Long, now: Long) = db.transaction {
        q.deleteExpiredEmailTokens(now)
        q.deleteEmailTokensForPurpose(email, purpose)
        q.insertEmailToken(tokenHash, email, purpose, expiresAt)
    }

    /** The address a token was mailed to, once: the row goes whether or not it was still good. Null when unknown, used, expired or for another [purpose]. */
    fun takeEmailToken(tokenHash: String, purpose: String, now: Long): String? = db.transactionWithResult {
        val row = q.selectEmailToken(tokenHash).executeAsOneOrNull() ?: return@transactionWithResult null
        q.deleteEmailToken(tokenHash)
        row.email.takeIf { row.purpose == purpose && row.expires_at > now }
    }

    // --- documents --------------------------------------------------------------------------

    /**
     * Upserts each document unless the row already holds a newer one; every accepted write takes the
     * next seq. A push that would take the user past [maxDocuments] rows (tombstones count: they
     * stay) throws [TooManyDocumentsException] and writes nothing. A push of edits to rows the user
     * already has always passes, so an account at the ceiling can still change and delete what it
     * holds.
     */
    fun push(userId: Long, documents: List<SyncDocument>, maxDocuments: Long = Long.MAX_VALUE): List<PushResult> = db.transactionWithResult {
        val before = q.countDocumentsForUser(userId).executeAsOne()
        val results = documents.map { doc ->
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
        // Counted after the writes, so a batch that repeats an id or edits existing rows is judged
        // by what it adds; throwing rolls the whole push back, seqs included.
        val after = q.countDocumentsForUser(userId).executeAsOne()
        if (after > before && after > maxDocuments) throw TooManyDocumentsException(maxDocuments)
        results
    }

    /** The documents after [since] in feed order, at most [limit], and whether more follow. */
    fun pull(userId: Long, since: Long, limit: Int): Pair<List<SyncDocument>, Boolean> {
        val rows = q.selectFeed(userId, since, limit + 1L).executeAsList()
        val page = rows.take(limit).map { SyncDocument(it.kind, it.id, it.updated_at, it.deleted != 0L, it.payload, it.seq) }
        return page to (rows.size > limit)
    }

    // --- blobs ------------------------------------------------------------------------------

    /**
     * Stores the bytes and writes their feed document in one go. Returns the seq, or null when the
     * feed holds a newer version. Throws, storing nothing, when the user's blobs would come to more
     * than [maxBytes] ([StorageFullException]; a replaced blob's old bytes don't count), or when a
     * new photo's feed row would take them past [maxDocuments] ([TooManyDocumentsException]).
     */
    fun putBlob(
        userId: Long,
        kind: String,
        id: String,
        updatedAt: String,
        bytes: ByteArray,
        payload: String,
        maxBytes: Long = Long.MAX_VALUE,
        maxDocuments: Long = Long.MAX_VALUE,
    ): Long? = db.transactionWithResult {
        val current = q.selectDocument(userId, kind, id).executeAsOneOrNull()
        if (current != null && current.updated_at > updatedAt) {
            null
        } else {
            if (q.sumOtherBlobBytesForUser(userId, kind, id).executeAsOne() + bytes.size > maxBytes) throw StorageFullException(maxBytes)
            if (current == null && q.countDocumentsForUser(userId).executeAsOne() >= maxDocuments) throw TooManyDocumentsException(maxDocuments)
            val seq = nextSeq()
            q.upsertBlob(userId, kind, id, bytes)
            q.upsertDocument(userId, kind, id, seq, updatedAt, 0L, payload)
            seq
        }
    }

    fun blob(userId: Long, kind: String, id: String): ByteArray? = q.selectBlob(userId, kind, id).executeAsOneOrNull()

    // --- guest list -------------------------------------------------------------------------

    /**
     * Puts [email] on the launch guest list. An address already there (in any case) is left as
     * it was. Returns false only when the list already holds [limit] addresses, so that a script
     * filling it can't fill the disk.
     */
    fun joinGuestList(email: String, limit: Long): Boolean = db.transactionWithResult {
        when {
            q.selectGuest(email).executeAsOneOrNull() != null -> true
            q.countGuests().executeAsOne() >= limit -> false
            else -> {
                q.insertGuest(email, Instant.now().toString())
                true
            }
        }
    }

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
