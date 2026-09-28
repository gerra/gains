package app.gains.root

import app.gains.auth.Account
import app.gains.auth.AccountKind
import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.auth.NoIdentityProvider
import app.gains.data.DesktopDriverFactory
import app.gains.data.ProgramRepository
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.platform.PickedFile
import app.gains.sync.SyncApi
import app.gains.sync.SyncStore
import app.gains.sync.createHttpClient
import app.gains.ui.nav.Navigator
import app.gains.ui.nav.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the root shows, from the account and the onboarding preference, against real (in-memory)
 * repositories; and the rule that opens Import for files shared into the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootGateTest {
    private class Rig {
        private val db = GainsDatabase(DesktopDriverFactory(file = null).createDriver())
        val settings = SettingsRepository(db, Dispatchers.Unconfined)
        val accounts = AccountRepository(
            settings,
            AuthConfig(),
            SyncApi(createHttpClient(), "https://api.example", token = { null }),
            SyncStore(db, Dispatchers.Unconfined),
            NoIdentityProvider,
        )
        val programs = ProgramRepository(db, settings, Dispatchers.Unconfined)
        /** Every state the root was in, in order. */
        val shown = mutableListOf<RootState>()

        suspend fun signedInAs(name: String) =
            settings.set(AccountRepository.KEY_ACCOUNT, AccountRepository.encode(Account(AccountKind.APPLE, name)))
    }

    private fun TestScope.started(rig: Rig) {
        backgroundScope.launch { RootGate(rig.accounts, rig.programs).state.collect { rig.shown += it } }
        runCurrent()
    }

    @Test
    fun notReadYetIsLoadingAndNeverSignInOrTheGoalQuestions() {
        for (onboardingDone in listOf(null, false, true)) {
            assertEquals(RootState.Loading, rootState(signedIn = null, onboardingDone = onboardingDone))
        }
        assertEquals(RootState.Loading, rootState(signedIn = true, onboardingDone = null))
    }

    @Test
    fun theAccountComesBeforeTheGoalQuestions() {
        assertEquals(RootState.SignIn, rootState(signedIn = false, onboardingDone = null))
        assertEquals(RootState.SignIn, rootState(signedIn = false, onboardingDone = false))
        assertEquals(RootState.SignIn, rootState(signedIn = false, onboardingDone = true))
        assertEquals(RootState.Onboarding, rootState(signedIn = true, onboardingDone = false))
        assertEquals(RootState.Main, rootState(signedIn = true, onboardingDone = true))
    }

    @Test
    fun someoneAlreadyInGoesStraightToTheApp() = runTest {
        val rig = Rig()
        rig.signedInAs("Ada")
        rig.programs.markOnboardingDone()
        started(rig)
        assertEquals(RootState.Main, rig.shown.last())
        assertEquals(emptyList(), rig.shown.filter { it == RootState.SignIn || it == RootState.Onboarding })
    }

    @Test
    fun aFreshInstallGoesThroughSignInAndTheGoalQuestions() = runTest {
        val rig = Rig()
        started(rig)
        assertEquals(RootState.SignIn, rig.shown.last())

        rig.accounts.continueAsGuest()
        runCurrent()
        assertEquals(RootState.Onboarding, rig.shown.last())

        rig.programs.markOnboardingDone()
        runCurrent()
        assertEquals(RootState.Main, rig.shown.last())

        rig.accounts.signOut()
        runCurrent()
        assertEquals(RootState.SignIn, rig.shown.last())
    }

    @Test
    fun aChangeToTheAccountItselfShowsNothingNew() = runTest {
        val rig = Rig()
        rig.signedInAs("Ada")
        rig.programs.markOnboardingDone()
        started(rig)
        val before = rig.shown.toList()

        rig.signedInAs("Ada Lovelace")
        runCurrent()
        assertEquals(before, rig.shown)
    }

    @Test
    fun sharedFilesOpenImportOnce() {
        val navigator = Navigator()
        navigator.openImportFor(emptyList())
        assertEquals(Screen.Home, navigator.current)

        val file = PickedFile("strong.csv", "")
        navigator.openImportFor(listOf(file))
        assertEquals(Screen.Import, navigator.current)
        val depth = navigator.stack.size

        // More files while Import is on top: it takes them where it is.
        navigator.openImportFor(listOf(file, file))
        assertEquals(depth, navigator.stack.size)
    }
}
