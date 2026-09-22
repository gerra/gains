package app.gains

import app.gains.auth.Account
import app.gains.auth.AccountKind
import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.auth.IdentityAssertion
import app.gains.auth.IdentityProvider
import app.gains.auth.SignInCancelledException
import app.gains.data.DesktopDriverFactory
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.sync.SyncApi
import app.gains.sync.SyncStore
import app.gains.sync.createHttpClient
import app.gains.ui.screens.SignInAttempt
import app.gains.ui.screens.SignInModel
import app.gains.ui.screens.signInButtons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sign-in screen's and Settings' side of the native sheets: closing one is not an error, a
 * sheet that fails is, a guest who gives up stays a guest, and only the providers that can work
 * get a button.
 */
class SignInModelTest {
    private val apple = AuthConfig(appleServiceId = "app.gains.Gains", serverBaseUrl = "https://api.example")

    /** An account repository whose sheet answers with [outcome]; the server is never reached because the sheet answers first. */
    private fun accounts(outcome: () -> IdentityAssertion): AccountRepository {
        val db = GainsDatabase(DesktopDriverFactory(null).createDriver())
        val store = SyncStore(db)
        val sheet = object : IdentityProvider {
            override suspend fun signIn(kind: AccountKind): IdentityAssertion = outcome()
        }
        return AccountRepository(SettingsRepository(db), apple, SyncApi(createHttpClient(), apple.serverBaseUrl!!, token = { null }), store, sheet)
    }

    private fun model(outcome: () -> IdentityAssertion): Pair<SignInModel, AccountRepository> {
        val accounts = accounts(outcome)
        return SignInModel(accounts, apple) to accounts
    }

    @Test
    fun closingTheSheetShowsNothing() = runBlocking {
        val (model, accounts) = model { throw SignInCancelledException() }
        model.signInWithApple().join()
        assertNull(model.error)
        assertFalse(model.failed)
        assertNull(accounts.observeAccount().first())
        model.onCleared()
    }

    @Test
    fun aSheetThatFailsSaysSo() = runBlocking {
        val (model, accounts) = model { throw IllegalStateException("The operation couldn't be completed.") }
        model.signInWithApple().join()
        assertNull(model.error)
        assertTrue(model.failed)
        assertNull(accounts.observeAccount().first())
        model.onCleared()
    }

    @Test
    fun aGuestWhoGivesUpLinkingInSettingsIsStillAGuest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        for (outcome in listOf(SignInCancelledException(), IllegalStateException("offline"))) {
            val accounts = accounts { throw outcome }
            accounts.continueAsGuest()
            val link = SignInAttempt(scope, accounts)
            link.apple().join()
            assertFalse(link.running)
            assertNull(link.error)
            assertEquals(outcome !is SignInCancelledException, link.failed)
            assertEquals(Account(AccountKind.GUEST), accounts.observeAccount().first())
        }
        scope.cancel()
    }

    @Test
    fun onlyEnabledProvidersGetAButtonAppleFirst() {
        assertEquals(emptyList(), signInButtons(AuthConfig()))
        // A client id without a server enables nothing: there is nowhere to send the token.
        assertEquals(emptyList(), signInButtons(AuthConfig(googleClientId = "id", appleServiceId = "app.gains.Gains")))
        assertEquals(listOf(AccountKind.APPLE), signInButtons(apple))
        assertEquals(listOf(AccountKind.APPLE, AccountKind.GOOGLE), signInButtons(apple.copy(googleClientId = "id")))
        assertEquals(listOf(AccountKind.GOOGLE), signInButtons(apple.copy(appleServiceId = null, googleClientId = "id")))
    }
}
