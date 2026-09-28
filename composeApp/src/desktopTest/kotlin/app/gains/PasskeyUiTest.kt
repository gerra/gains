package app.gains

import app.gains.auth.Account
import app.gains.auth.AccountKind
import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.auth.AuthNotConfiguredException
import app.gains.auth.IdentityProvider
import app.gains.auth.NoPasskeyException
import app.gains.auth.SignInCancelledException
import app.gains.auth.SignInProof
import app.gains.data.DesktopDriverFactory
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.sync.PasskeyChallenge
import app.gains.sync.SyncApi
import app.gains.sync.SyncJson
import app.gains.sync.SyncStore
import app.gains.ui.screens.PasskeyAddition
import app.gains.ui.screens.SignInAttempt
import app.gains.ui.screens.signInButtons
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
 * The screens' side of passkeys (docs/launch-plan.md, item 19): a phone without one is pointed at
 * Settings rather than told the sign-in failed, closing the sheet says nothing, and adding one
 * from Settings says whether it went through. The server here only hands out a challenge; the
 * real ceremonies run against the real routes in :server's `PasskeysTest`.
 */
class PasskeyUiTest {
    private val config = AuthConfig(serverBaseUrl = "https://api.example", passkeys = true)

    /** A server that starts every ceremony and accepts every new passkey. */
    private val server = HttpClient(MockEngine { request ->
        if (request.url.encodedPath.endsWith("/start")) {
            respond(
                SyncJson.encodeToString(PasskeyChallenge.serializer(), PasskeyChallenge("c-1", """{"challenge":"AA","rpId":"gains.example"}""")),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        } else {
            respond("", HttpStatusCode.NoContent)
        }
    })

    /** Accounts whose passkey sheet ends with [outcome]. */
    private fun accounts(outcome: () -> String): AccountRepository {
        val db = GainsDatabase(DesktopDriverFactory(null).createDriver())
        val store = SyncStore(db)
        val sheet = object : IdentityProvider {
            override suspend fun signIn(kind: AccountKind): SignInProof = throw AuthNotConfiguredException(kind)
            override suspend fun createPasskey(options: String): String = outcome()
            override suspend fun getPasskey(options: String): String = outcome()
        }
        return AccountRepository(SettingsRepository(db), config, SyncApi(server, "https://api.example", token = { "t" }), store, sheet)
    }

    @Test
    fun aPhoneWithoutAPasskeyIsToldWhereToAddOne() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val accounts = accounts { throw NoPasskeyException() }
        accounts.continueAsGuest()
        val attempt = SignInAttempt(scope, accounts)
        attempt.passkey().join()
        assertTrue(attempt.noPasskey)
        assertFalse(attempt.failed)
        assertEquals(Account(AccountKind.GUEST), accounts.observeAccount().first())

        val closed = SignInAttempt(scope, accounts { throw SignInCancelledException() })
        closed.passkey().join()
        assertFalse(closed.noPasskey)
        assertFalse(closed.failed)
        assertNull(closed.error)
        scope.cancel()
    }

    @Test
    fun addingAPasskeySaysWhetherItWentThrough() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val added = PasskeyAddition(scope, accounts { "{}" })
        added.run().join()
        assertTrue(added.added)
        assertFalse(added.failed)

        val closed = PasskeyAddition(scope, accounts { throw SignInCancelledException() })
        closed.run().join()
        assertFalse(closed.added)
        assertFalse(closed.failed, "closing the sheet is a choice")

        val broken = PasskeyAddition(scope, accounts { throw IllegalStateException("The operation couldn't be completed.") })
        broken.run().join()
        assertFalse(broken.running)
        assertTrue(broken.failed)
        scope.cancel()
    }

    @Test
    fun thePasskeyButtonComesAfterTheProvidersAndBeforeEmail() {
        val all = config.copy(appleServiceId = "app.gains.Gains", googleClientId = "id", passwordSignIn = true)
        assertEquals(listOf(AccountKind.APPLE, AccountKind.GOOGLE, AccountKind.PASSKEY, AccountKind.EMAIL), signInButtons(all))
        assertEquals(emptyList(), signInButtons(AuthConfig(passkeys = true)), "the switch alone, without a server, offers nothing")
        assertEquals(emptyList(), signInButtons(config.copy(passkeys = false)))
    }
}
