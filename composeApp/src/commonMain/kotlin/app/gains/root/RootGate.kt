package app.gains.root

import app.gains.auth.AccountRepository
import app.gains.data.ProgramRepository
import app.gains.platform.PickedFile
import app.gains.ui.nav.Navigator
import app.gains.ui.nav.Screen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** What the root draws: nothing yet, the sign-in screen, the goal questions, or the app itself. */
internal enum class RootState { Loading, SignIn, Onboarding, Main }

/**
 * Which of the [RootState]s the root is in, from the account and the onboarding preference. A class
 * of its own rather than early returns in the root, so the order of the gates is tested without
 * composing the app. Either preference not read yet is [RootState.Loading], never a guess: the
 * sign-in screen and the goal questions must not flash at launch for someone who is past them.
 */
internal class RootGate(
    private val accounts: AccountRepository,
    private val programs: ProgramRepository,
) {
    /**
     * Starts at [RootState.Loading] and follows both preferences from there. Only whether there is
     * an account matters here, so a change to the account itself (a new name) doesn't recompose the root.
     */
    val state: Flow<RootState> = combine(
        accounts.observeAccount().map<_, Boolean?> { it != null }.onStart { emit(null) },
        programs.observeOnboardingDone().map<_, Boolean?> { it }.onStart { emit(null) },
        ::rootState,
    ).distinctUntilChanged()
}

/**
 * The gates in order: the account, then the goal questions. `null` is "not read yet". Someone with
 * no account is asked to sign in without waiting for the onboarding preference, which only matters
 * once they are past that.
 */
internal fun rootState(signedIn: Boolean?, onboardingDone: Boolean?): RootState = when {
    signedIn == null -> RootState.Loading
    !signedIn -> RootState.SignIn
    onboardingDone == null -> RootState.Loading
    !onboardingDone -> RootState.Onboarding
    else -> RootState.Main
}

/**
 * Files shared into the app open Import, which takes them from [app.gains.platform.IncomingFiles].
 * Not a second time when Import is already on top: it picks up the new files where it is.
 */
internal fun Navigator.openImportFor(incoming: List<PickedFile>) {
    if (incoming.isNotEmpty() && current != Screen.Import) push(Screen.Import)
}
