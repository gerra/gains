package app.gains.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The bearer token in the desktop's OS keyring ([Keyring]: the macOS Keychain, the Secret
 * Service on Linux, DPAPI on Windows), outside the database file, which is plain SQLite in the
 * home directory. Each keyring call starts a process, and the sync asks for the token on every
 * request, so the token is read from the OS once and then kept here; this vault is the only
 * writer, so the copy can't go stale. A keyring that won't answer throws, as the Keychain vault
 * does, so a passing fault never signs anyone out. The keyring outlives the database, which is why
 * [app.gains.auth.AccountRepository.forgetOrphanedToken] runs at start. See docs/sync.md.
 */
class KeyringTokenVault(
    private val keyring: Keyring,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TokenVault {
    private val lock = Mutex()
    private var loaded = false
    private var cached: String? = null

    override suspend fun get(): String? = lock.withLock {
        if (!loaded) {
            cached = withContext(io) { keyring.read()?.ifBlank { null } }
            loaded = true
        }
        cached
    }

    override suspend fun set(token: String) {
        lock.withLock {
            withContext(io) { keyring.write(token) }
            cached = token
            loaded = true
        }
    }

    override suspend fun clear() {
        lock.withLock {
            withContext(io) { keyring.delete() }
            cached = null
            loaded = true
        }
    }
}
