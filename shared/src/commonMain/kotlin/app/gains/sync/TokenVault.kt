package app.gains.sync

import app.gains.db.GainsDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Where the sync server's bearer token is kept. It is the one secret the sync holds, so it has a
 * home of its own rather than a row next to the cursor: on iOS that is the Keychain
 * (`KeychainTokenVault`), on Android a Keystore key (`KeystoreTokenVault`); both keep it out of
 * backups and out of the database file. The rest of the sync's state is not secret and stays in
 * `sync_state`. See docs/sync.md.
 */
interface TokenVault {
    /** The token, or null when there is none. */
    suspend fun get(): String?

    suspend fun set(token: String)

    suspend fun clear()
}

/**
 * The token as a row of `sync_state`, where it has always lived. The default for the desktop
 * until it gets a vault of its own (docs/launch-plan.md, item 17), and what the tests use.
 */
class SqliteTokenVault(
    private val db: GainsDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TokenVault {
    private val q get() = db.syncQueries

    override suspend fun get(): String? = withContext(io) { q.selectState(SyncStore.KEY_TOKEN).executeAsOneOrNull()?.ifBlank { null } }

    override suspend fun set(token: String) {
        withContext(io) { q.upsertState(SyncStore.KEY_TOKEN, token) }
    }

    override suspend fun clear() {
        withContext(io) { q.deleteState(SyncStore.KEY_TOKEN) }
    }
}
