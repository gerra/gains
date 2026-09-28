package app.gains

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.gains.platform.systemReducesMotion
import app.gains.ui.theme.LocalReduceMotion
import app.gains.ui.theme.Motion
import app.gains.ui.theme.screenSlide
import app.gains.ui.i18n.*
import app.gains.auth.AccountRepository
import app.gains.data.ExerciseRepository
import app.gains.data.LiveSessionRepository
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.domain.LiveSession
import app.gains.root.LiveSessionNotices
import app.gains.root.RootGate
import app.gains.root.RootState
import app.gains.root.StreakReminders
import app.gains.root.UpNext
import app.gains.root.openImportFor
import app.gains.root.findUpNext
import app.gains.sync.SyncController
import kotlinx.coroutines.flow.combine
import app.gains.data.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.platform.LiveSessionNotifier
import app.gains.platform.NudgeScheduler
import app.gains.platform.PhotoPicker
import app.gains.platform.ResumeRequests
import app.gains.ui.components.dismissKeyboardOnTap
import app.gains.ui.inject
import app.gains.ui.nav.BottomNav
import app.gains.ui.nav.LiveSessionBar
import app.gains.ui.nav.Navigator
import app.gains.ui.nav.Screen
import app.gains.ui.nav.ScreenContent
import app.gains.ui.nav.SwipeBack
import app.gains.ui.nav.TopBar
import app.gains.ui.screens.OnboardingScreen
import app.gains.ui.screens.SignInScreen
import app.gains.ui.theme.GainsTheme

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
    val settings = remember { inject<SettingsRepository>() }
    // Each screen's saved UI state (scroll positions and the like) is kept under its stack entry's id
    // while the entry lives, so a screen comes back as it was left once the one covering it is popped.
    // The stack and that state sit above the language, so a change of it leaves the lifter where they were.
    val stateHolder = rememberSaveableStateHolder()
    val navigator = remember { Navigator(onReleased = { stateHolder.removeState(it.id) }) }
    val themeMode by settings.observeThemeMode().collectAsState(ThemeMode.DARK)
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val reduceMotion = remember { systemReducesMotion() }
    systemBars(dark)
    GainsTheme(darkTheme = dark) { CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        // Surface sets the content colour for every Text below it and paints the background.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
            // Everything below is worded in the chosen language, or in the device's where none has
            // been chosen. Nothing is drawn until that preference has been read — a moment, at
            // launch — so the app is never shown in one language and then another.
            val language = settings.observeLanguage().collectAsState(initial = null).value ?: return@Surface
            InLanguage(language) { AppBody(navigator, stateHolder, filePicker, photoPicker, systemBack, notifier, nudges) }
        }
    } }
}

/**
 * Everything under the look and the language: the screens on [navigator]'s back stack, the workout
 * in progress, and the way between them. Composed afresh whenever the language changes, which is
 * what puts every word on screen into the new one; the stack and [stateHolder] outlive that.
 *
 * The streak reminders are planned here too, so that a change of language re-words the ones still
 * to come: their text is settled when the plan is made, not when the platform shows them.
 */
@Composable
private fun AppBody(
    navigator: Navigator,
    stateHolder: SaveableStateHolder,
    filePicker: CsvFilePicker,
    photoPicker: PhotoPicker,
    systemBack: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit,
    notifier: LiveSessionNotifier,
    nudges: NudgeScheduler,
) {
    val settings = remember { inject<SettingsRepository>() }
    val exercises = remember { inject<ExerciseRepository>() }
    LaunchedEffect(Unit) { exercises.seedCatalogue() }
    // The sync runs for as long as the app does; it does nothing for a guest or without a server.
    val sync = remember { inject<SyncController>() }
    LaunchedEffect(Unit) { sync.start(this) }

    // Files shared into the app open the import screen.
    val incoming by IncomingFiles.pending.collectAsState()
    LaunchedEffect(incoming) { navigator.openImportFor(incoming) }
    val programs = remember { inject<ProgramRepository>() }
    val sessions = remember { inject<SessionRepository>() }
    // Sign-in, the goal questions or the app; nothing until the account and onboarding have been read.
    val gate = remember { RootGate(inject<AccountRepository>(), programs) }
    val rootState by gate.state.collectAsState(initial = RootState.Loading)
    systemBack(navigator.canGoBack) { navigator.pop() }

    // The active program's next day, for the "+" menu.
    val texts = rememberTexts()
    val upNext by remember(texts) {
        combine(programs.observeState(), sessions.observeProgramLinks()) { state, links -> findUpNext(state, links, texts) }
    }.collectAsState(initial = null)
    // The workout in progress, if any: shown as a resume bar on every screen but its own.
    val liveSessions = remember { inject<LiveSessionRepository>() }
    val live by liveSessions.observe().collectAsState(initial = null)
    // Keep the platform's tray in step with it, and answer the notice's taps on "resume" and "skip rest".
    val notices = remember { LiveSessionNotices(liveSessions, notifier, navigator) }
    LaunchedEffect(Unit) { notices.run() }
    // The streak reminders, re-worded when the texts change.
    val reminders = remember { StreakReminders(sessions, programs, settings, nudges) }
    LaunchedEffect(texts) { reminders.run(texts) }
    when (rootState) {
        RootState.Loading -> Unit
        RootState.SignIn -> SignInScreen()
        RootState.Onboarding -> OnboardingScreen(onDone = {})
        RootState.Main -> Main(navigator, stateHolder, filePicker, photoPicker, upNext, live)
    }
}

/**
 * The app past its gates: the top bar, the screen on top of [navigator]'s stack, the workout bar and
 * the tabs. The pieces are drawn in `ui/nav/` (`AppChrome.kt` for the bars, `Routes.kt` for the
 * screens); what is left here is how they are laid out and animated against each other.
 */
@Composable
private fun Main(
    navigator: Navigator,
    stateHolder: SaveableStateHolder,
    filePicker: CsvFilePicker,
    photoPicker: PhotoPicker,
    upNext: UpNext?,
    live: LiveSession?,
) {
    val screen = navigator.current
    // Tapping outside a text field anywhere in the app puts the keyboard away.
    Column(Modifier.fillMaxSize().statusBarsPadding().dismissKeyboardOnTap()) {
        TopBar(navigator, screen, upNext)
        val transition = updateTransition(navigator.currentEntry, label = "screen")
        val reduceMotion = LocalReduceMotion.current
        SwipeBack(
            // While a screen is still sliding out it is on screen already; the swipe would draw it a second time.
            enabled = navigator.canGoBack && !transition.isRunning && transition.currentState === transition.targetState,
            onBack = { navigator.pop(animated = false) },
            modifier = Modifier.weight(1f),
            previous = { navigator.previousEntry?.let { ScreenContent(it, navigator, filePicker, photoPicker, stateHolder) } },
        ) {
            transition.AnimatedContent(
                transitionSpec = {
                    // The swipe-back gesture has already slid the old screen away. Otherwise the new screen comes
                    // in the way the lifter went: deeper or to a tab on the right from the right, back or left from the left.
                    screenSlide(forward = navigator.direction > 0, reduce = navigator.skipTransition || reduceMotion)
                },
            ) { entry -> ScreenContent(entry, navigator, filePicker, photoPicker, stateHolder) }
        }
        // The bar rises in when a workout starts and folds away when it ends, rather than shoving the tabs.
        // The workout it last showed, so the bar can still be drawn while it folds away after the workout ends.
        var lastLive by remember { mutableStateOf(live) }
        SideEffect { if (live != null) lastLive = live }
        val shownLive = live ?: lastLive
        AnimatedVisibility(
            visible = live != null && !(screen is Screen.EditSession && screen.live),
            enter = if (reduceMotion) EnterTransition.None else expandVertically(tween(Motion.STANDARD)) + fadeIn(tween(Motion.STANDARD)),
            exit = if (reduceMotion) ExitTransition.None else shrinkVertically(tween(Motion.STANDARD)) + fadeOut(tween(Motion.EXIT)),
        ) {
            shownLive?.let { running ->
                LiveSessionBar(running, onResume = { navigator.push(Screen.EditSession(null, running.program, live = true)) })
            }
        }
        BottomNav(navigator)
    }
}
