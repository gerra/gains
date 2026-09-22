package app.gains.auth

import app.gains.data.DesktopDriverFactory
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.sync.SyncApi
import app.gains.sync.SyncStore
import app.gains.sync.TokenVault
import app.gains.sync.createHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A reinstall on iOS: the Keychain still holds the last install's token while the fresh database
 * has no account, and that token is dropped at start. A signed-in account's token is left alone.
 */
class OrphanedTokenTest {
    private class MemoryVault(var token: String? = null) : TokenVault {
        override suspend fun get() = token
        override suspend fun set(token: String) { this.token = token }
        override suspend fun clear() { token = null }
    }

    private class Device(vaultToken: String?) {
        val db = GainsDatabase(DesktopDriverFactory(file = null).createDriver())
        val settings = SettingsRepository(db, Dispatchers.Unconfined)
        val vault = MemoryVault(vaultToken)
        val accounts = AccountRepository(
            settings,
            AuthConfig(),
            SyncApi(createHttpClient(), "https://api.example", token = { null }),
            SyncStore(db, Dispatchers.Unconfined, vault),
            NoIdentityProvider,
        )
    }

    @Test
    fun aTokenWithNoAccountIsDropped() = runTest {
        val d = Device("left over")
        d.accounts.forgetOrphanedToken()
        assertNull(d.vault.token)
    }

    @Test
    fun aTokenUnderAGuestIsDropped() = runTest {
        val d = Device("left over")
        d.accounts.continueAsGuest()
        d.accounts.forgetOrphanedToken()
        assertNull(d.vault.token)
    }

    @Test
    fun aSignedInAccountKeepsItsToken() = runTest {
        val d = Device("mine")
        d.settings.set(AccountRepository.KEY_ACCOUNT, AccountRepository.encode(Account(AccountKind.APPLE, "Ada")))
        d.accounts.forgetOrphanedToken()
        assertEquals("mine", d.vault.token)
    }
}
