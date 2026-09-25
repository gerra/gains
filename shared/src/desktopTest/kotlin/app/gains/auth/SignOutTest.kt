package app.gains.auth

import app.gains.data.DesktopDriverFactory
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.sync.SyncApi
import app.gains.sync.SyncStore
import app.gains.sync.TokenVault
import app.gains.sync.createHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Signing out when the vault can't clear the token, as the iOS Keychain can't when `SecItemDelete`
 * fails. The exception would crash the app from Settings' scope, so the account is cleared anyway,
 * and the token it leaves behind is dropped at the next start like any other orphan.
 */
class SignOutTest {
    private class StuckVault(var token: String?) : TokenVault {
        var failing = true
        override suspend fun get() = token
        override suspend fun set(token: String) { this.token = token }
        override suspend fun clear() {
            if (failing) error("Keychain delete failed: -25300")
            token = null
        }
    }

    private class Device {
        val db = GainsDatabase(DesktopDriverFactory(file = null).createDriver())
        val settings = SettingsRepository(db, Dispatchers.Unconfined)
        val vault = StuckVault("mine")
        val accounts = AccountRepository(
            settings,
            AuthConfig(),
            SyncApi(createHttpClient(), "https://api.example", token = { null }),
            SyncStore(db, Dispatchers.Unconfined, vault),
            NoIdentityProvider,
        )

        suspend fun signedIn() =
            settings.set(AccountRepository.KEY_ACCOUNT, AccountRepository.encode(Account(AccountKind.APPLE, "Ada")))
    }

    @Test
    fun signOutClearsTheAccountWhenTheVaultThrows() = runTest {
        val d = Device()
        d.signedIn()
        d.accounts.signOut()
        assertEquals("", d.settings.observe(AccountRepository.KEY_ACCOUNT).first())
        assertNull(d.accounts.observeAccount().first())
    }

    @Test
    fun theTokenLeftBehindIsDroppedAtTheNextStart() = runTest {
        val d = Device()
        d.signedIn()
        d.accounts.signOut()
        assertEquals("mine", d.vault.token)
        d.vault.failing = false
        d.accounts.forgetOrphanedToken()
        assertNull(d.vault.token)
    }
}
