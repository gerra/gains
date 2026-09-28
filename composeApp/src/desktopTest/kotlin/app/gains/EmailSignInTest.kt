package app.gains

import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.auth.EmailSignInException
import app.gains.auth.NoIdentityProvider
import app.gains.data.DesktopDriverFactory
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.sync.SyncApi
import app.gains.sync.SyncStore
import app.gains.sync.createHttpClient
import app.gains.ui.reportingHandler
import app.gains.ui.screens.EmailSignIn
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

/**
 * The email form's side of things without a server: what is plainly wrong is refused before a
 * call, a build without the switch says so, and an unreachable server is a failure that leaves
 * the account as it was. The round trip through the real routes is `PasswordSignInTest`.
 */
class EmailSignInTest {
    private fun accounts(passwordSignIn: Boolean): AccountRepository {
        val db = GainsDatabase(DesktopDriverFactory(null).createDriver())
        val store = SyncStore(db)
        // Nothing listens on port 1, so a call fails the way an offline phone's would.
        val config = AuthConfig(serverBaseUrl = "http://127.0.0.1:1", passwordSignIn = passwordSignIn)
        return AccountRepository(SettingsRepository(db), config, SyncApi(createHttpClient(), config.serverBaseUrl!!, token = { store.token() }), store, NoIdentityProvider)
    }

    private val reporter = RecordingReporter()

    @Test
    fun whatIsPlainlyWrongNeverLeavesTheDevice() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + reportingHandler(reporter))
        val form = EmailSignIn(scope, accounts(passwordSignIn = true))
        form.email = "not an address"; form.password = "correct horse"
        form.submit().join()
        assertEquals(EmailSignIn.Outcome.Refused(EmailSignInException.Reason.INVALID), form.outcome)
        form.email = "ada@example.com"; form.password = "short"
        form.submit().join()
        assertEquals(EmailSignIn.Outcome.Refused(EmailSignInException.Reason.INVALID), form.outcome)
        // A reset asks for the address only; the password is not looked at.
        form.switchTo(EmailSignIn.Mode.RESET)
        assertNull(form.outcome)
        form.submit().join()
        assertEquals(EmailSignIn.Outcome.Failed, form.outcome, "reached for the server and found none")
        assertFalse(form.running)
        assertEquals(1, reporter.reported.size, "only the call that went out and failed is reported")
        scope.cancel()
    }

    @Test
    fun withoutTheSwitchTheFormSaysSoAndOfflineItFails() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + reportingHandler(reporter))
        val off = EmailSignIn(scope, accounts(passwordSignIn = false))
        off.email = "ada@example.com"; off.password = "correct horse"
        off.submit().join()
        assertEquals(EmailSignIn.Outcome.NotConfigured, off.outcome)

        val accounts = accounts(passwordSignIn = true)
        val on = EmailSignIn(scope, accounts)
        on.email = "ada@example.com"; on.password = "correct horse"
        on.switchTo(EmailSignIn.Mode.SIGN_UP)
        on.submit().join()
        assertEquals(EmailSignIn.Outcome.Failed, on.outcome)
        assertEquals(EmailSignIn.Mode.SIGN_UP, on.mode, "still on the form that failed")
        assertEquals("correct horse", on.password, "nothing typed is lost")
        assertNull(accounts.observeAccount().first())
        assertEquals(1, reporter.reported.size, "a build without the switch is expected, the offline server is not")
        scope.cancel()
    }
}
