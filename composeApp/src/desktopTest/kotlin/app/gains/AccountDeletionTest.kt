package app.gains

import app.gains.auth.Account
import app.gains.auth.AccountKind
import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.auth.NoIdentityProvider
import app.gains.data.DesktopDriverFactory
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.sync.SyncApi
import app.gains.sync.SyncStore
import app.gains.sync.createHttpClient
import app.gains.ui.screens.AccountDeletion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deleting the account from Settings when the server can't be reached: the card says so, and the
 * person stays signed in with their token, so nothing is lost and they can try again. The success
 * path runs through the real routes in `SyncRoundTripTest`.
 */
class AccountDeletionTest {
    @Test
    fun aFailedDeleteStaysSignedIn() = runBlocking {
        val db = GainsDatabase(DesktopDriverFactory(null).createDriver())
        val settings = SettingsRepository(db)
        val store = SyncStore(db)
        // Nothing listens on port 1, so the call fails the way an offline phone's would.
        val config = AuthConfig(appleServiceId = "app.gains.Gains", serverBaseUrl = "http://127.0.0.1:1")
        val accounts = AccountRepository(settings, config, SyncApi(createHttpClient(), config.serverBaseUrl!!, token = { store.token() }), store, NoIdentityProvider)
        val me = Account(AccountKind.APPLE, "Me", "me@x.y")
        settings.set(AccountRepository.KEY_ACCOUNT, AccountRepository.encode(me))
        store.setToken("t")
        store.startFeed(userId = 7)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val deletion = AccountDeletion(scope, accounts)
        deletion.run().join()
        assertFalse(deletion.running)
        assertTrue(deletion.failed)
        assertEquals(me, accounts.observeAccount().first())
        assertEquals("t", store.token())
        assertEquals(7L, store.userId())
        scope.cancel()
    }
}
