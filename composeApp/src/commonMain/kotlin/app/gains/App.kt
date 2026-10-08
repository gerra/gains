package app.gains

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.data.ExerciseRepository
import app.gains.data.LiveSessionRepository
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.data.ThemeMode
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.platform.LiveSessionNotifier
import app.gains.platform.NudgeScheduler
import app.gains.platform.PhotoPicker
import app.gains.platform.ResumeRequests
import app.gains.platform.systemReducesMotion
import app.gains.root.LiveSessionNotices
import app.gains.root.RootGate
import app.gains.root.RootState
import app.gains.root.StreakReminders
import app.gains.root.observeUpNext
import app.gains.root.openImportFor
import app.gains.sync.SyncController
import app.gains.sync.SyncEngine
import app.gains.sync.SyncStore
import app.gains.ui.i18n.InLanguage
import app.gains.ui.i18n.rememberTexts
import app.gains.ui.inject
import app.gains.ui.nav.AppFrame
import app.gains.ui.nav.Navigator
import app.gains.ui.screens.OnboardingScreen
import app.gains.ui.screens.SignInScreen
import app.gains.ui.screens.SyncUi
import app.gains.ui.screens.observeSyncUi
import app.gains.ui.theme.GainsTheme
import app.gains.ui.theme.LocalReduceMotion
import app.gains.ui.theme.isDark

/**
 * Root of the shared UI: the look it is drawn in, the language it is worded in, and [AppBody] with
 * everything else. [filePicker] is supplied by each platform entry point.
 *
 * [systemBack] lets a platform hook its own back affordance (Android's button and predictive back
 * gesture) into the navigator: it is composed with whether the app can go back and what to do then.
 * Swiping in from the left edge goes back on every platform without any hook.
 *
 * [notifier] is told about the workout in progress, so the platform can keep a way back to it in
 * its tray while the lifter is elsewhere; a tap there comes back through [ResumeRequests].
 *
 * [photoPicker] opens the platform's photo library for the picture a workout's summary can carry.
 *
 * [nudges] holds the streak reminders the platform is to deliver while the app is not running. The
 * whole plan is handed over again every time the streak changes, so it can never fall behind what
 * has actually been logged.
 *
 * [systemBars] is composed with whether the app is drawn dark, so a platform that draws the app
 * under its own status and navigation bars (Android 15 and later) can colour their icons to match
 * the app's theme rather than the device's.
 */
@Composable
internal fun App(
    filePicker: CsvFilePicker,
    systemBack: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
    notifier: LiveSessionNotifier = LiveSessionNotifier.None,
    photoPicker: PhotoPicker = PhotoPicker.None,
    nudges: NudgeScheduler = NudgeScheduler.None,
    systemBars: @Composable (dark: Boolean) -> Unit = {},
) {
    val graph = remember { RootGraph(inject(), inject(), inject(), inject(), inject(), inject(), inject(), inject(), inject(), inject()) }
    val settings = graph.settings
    // Each screen's saved UI state (scroll positions and the like) is kept under its stack entry's id
    // while the entry lives, so a screen comes back as it was left once the one covering it is popped.
    // The stack and that state sit above the language, so a change of it leaves the lifter where they were.
    val stateHolder = rememberSaveableStateHolder()
    val navigator = remember { Navigator(onReleased = { stateHolder.removeState(it.id) }) }
    val dark = settings.observeThemeMode().collectAsState(ThemeMode.DARK).value.isDark()
    val reduceMotion = remember { systemReducesMotion() }
    systemBars(dark)
    GainsTheme(darkTheme = dark) { CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        // Surface sets the content colour for every Text below it and paints the background.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
            // Everything below is worded in the chosen language, or in the device's where none has
            // been chosen. Nothing is drawn until that preference has been read — a moment, at
            // launch — so the app is never shown in one language and then another.
            val language = settings.observeLanguage().collectAsState(initial = null).value ?: return@Surface
            InLanguage(language) { AppBody(graph, navigator, stateHolder, filePicker, photoPicker, systemBack, notifier, nudges) }
        }
    } }
}

/**
 * What the root takes from the Koin graph, looked up once in [App] and handed down, so the root's
 * lookups sit in one place and the pieces in `app.gains.root` are given theirs in their
 * constructors. It sits above the language with the navigator; the pieces made from it are still
 * made afresh under each language, as before.
 */
private class RootGraph(
    val settings: SettingsRepository,
    val accounts: AccountRepository,
    val exercises: ExerciseRepository,
    val programs: ProgramRepository,
    val sessions: SessionRepository,
    val liveSessions: LiveSessionRepository,
    val sync: SyncController,
    val syncEngine: SyncEngine,
    val syncStore: SyncStore,
    val authConfig: AuthConfig,
)

/**
 * Everything under the look and the language: the gates, the pieces in `app.gains.root` that keep
 * the app in step with its data, and [AppFrame] with the screens on [navigator]'s back stack.
 * Composed afresh whenever the language changes, which is what puts every word on screen into the
 * new one; the stack and [stateHolder] outlive that.
 *
 * Each piece is made with `remember` and run from a `LaunchedEffect` here, so none of them outlives
 * the composition. The streak reminders are planned here too, so that a change of language re-words
 * the ones still to come: their text is settled when the plan is made, not when the platform shows them.
 */
@Composable
private fun AppBody(
    graph: RootGraph,
    navigator: Navigator,
    stateHolder: SaveableStateHolder,
    filePicker: CsvFilePicker,
    photoPicker: PhotoPicker,
    systemBack: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit,
    notifier: LiveSessionNotifier,
    nudges: NudgeScheduler,
) {
    LaunchedEffect(Unit) { graph.exercises.seedCatalogue() }
    // The sync runs for as long as the app does; it does nothing for a guest or without a server.
    LaunchedEffect(Unit) { graph.sync.start(this) }

    // Files shared into the app open the import screen.
    val incoming by IncomingFiles.pending.collectAsState()
    LaunchedEffect(incoming) { navigator.openImportFor(incoming) }
    val programs = graph.programs
    val sessions = graph.sessions
    // Sign-in, the goal questions or the app; nothing until the account and onboarding have been read.
    val gate = remember { RootGate(graph.accounts, programs) }
    val rootState by gate.state.collectAsState(initial = RootState.Loading)
    systemBack(navigator.canGoBack) { navigator.pop() }

    // The active program's next day, for the "+" menu.
    val texts = rememberTexts()
    val upNext by remember(texts) { observeUpNext(programs, sessions, texts) }.collectAsState(initial = null)
    // The workout in progress, if any: shown as a resume bar on every screen but its own.
    val liveSessions = graph.liveSessions
    val live by liveSessions.observe().collectAsState(initial = null)
    // Keep the platform's tray in step with it, and answer the notice's taps on "resume" and "skip rest".
    val notices = remember { LiveSessionNotices(liveSessions, notifier, navigator) }
    LaunchedEffect(Unit) { notices.run() }
    // The streak reminders, re-worded when the texts change.
    val reminders = remember { StreakReminders(sessions, programs, graph.settings, nudges) }
    LaunchedEffect(texts) { reminders.run(texts) }
    // A failed sync or a sign-out by the server, shown on Home and on the Settings button rather than only inside Settings.
    val sync by remember { observeSyncUi(graph.authConfig.syncEnabled, graph.accounts, graph.syncEngine, graph.syncStore) }
        .collectAsState(initial = SyncUi.Hidden)
    when (rootState) {
        RootState.Loading -> Unit
        RootState.SignIn -> SignInScreen()
        RootState.Onboarding -> OnboardingScreen(onDone = {})
        RootState.Main -> AppFrame(navigator, stateHolder, filePicker, photoPicker, upNext, live, sync, onRetrySync = graph.sync::requestSync)
    }
}
