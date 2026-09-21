package app.gains

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gains.analysis.Format
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.i18n.*
import org.jetbrains.compose.resources.stringResource
import app.gains.auth.AccountRepository
import app.gains.data.ExerciseRepository
import app.gains.data.LiveSessionRepository
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.domain.LiveSession
import app.gains.domain.ProgramDayRef
import app.gains.program.Rotation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.gains.data.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.platform.LiveSessionNotice
import app.gains.platform.LiveSessionNotifier
import app.gains.platform.PhotoPicker
import app.gains.platform.ResumeRequests
import app.gains.platform.SkipRestRequests
import app.gains.ui.components.GainsWordmark
import app.gains.ui.components.dismissKeyboardOnTap
import app.gains.ui.inject
import app.gains.ui.nav.LocalNavEntry
import app.gains.ui.nav.NavEntry
import app.gains.ui.nav.Navigator
import app.gains.ui.nowMs
import app.gains.ui.nav.Screen
import app.gains.ui.nav.SwipeBack
import app.gains.ui.nav.Tab
import app.gains.ui.screens.BodyweightScreen
import app.gains.ui.screens.HistoryScreen
import app.gains.ui.screens.SessionEditorModel
import app.gains.ui.screens.SessionEditorScreen
import app.gains.ui.screens.SessionSummaryScreen
import app.gains.ui.screens.ExerciseDetailScreen
import app.gains.ui.screens.ExercisesScreen
import app.gains.ui.screens.HomeScreen
import app.gains.ui.screens.ImportScreen
import app.gains.ui.screens.OnboardingScreen
import app.gains.ui.screens.ProgramDetailScreen
import app.gains.ui.screens.ProgramEditorScreen
import app.gains.ui.screens.ProgramsScreen
import app.gains.ui.screens.SettingsScreen
import app.gains.ui.screens.SignInScreen
import app.gains.ui.screens.VolumeScreen
import app.gains.ui.theme.GainsColors
import app.gains.ui.theme.GainsTheme

/**
 * Root of the shared UI. [filePicker] is supplied by each platform entry point.
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
 * Everything on screen is in the device's language, through the string resources: a change of
 * language takes a relaunch.
 */
@Composable
internal fun App(
    filePicker: CsvFilePicker,
    systemBack: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
    notifier: LiveSessionNotifier = LiveSessionNotifier.None,
    photoPicker: PhotoPicker = PhotoPicker.None,
) {
    // Each screen's saved UI state (scroll positions and the like) is kept under its stack entry's id
    // while the entry lives, so a screen comes back as it was left once the one covering it is popped.
    val stateHolder = rememberSaveableStateHolder()
    val navigator = remember { Navigator(onReleased = { stateHolder.removeState(it.id) }) }
    val exercises = remember { inject<ExerciseRepository>() }
    LaunchedEffect(Unit) { exercises.seedCatalogue() }

    // Files shared into the app open the import screen.
    val incoming by IncomingFiles.pending.collectAsState()
    LaunchedEffect(incoming) { if (incoming.isNotEmpty() && navigator.current != Screen.Import) navigator.push(Screen.Import) }
    val accounts = remember { inject<AccountRepository>() }
    // null = still loading the preference; Optional-ish wrapper keeps "no account" distinct from "unknown".
    val accountState by accounts.observeAccount().collectAsState(initial = AccountLoading)
    systemBack(navigator.canGoBack) { navigator.pop() }

    val settings = remember { inject<SettingsRepository>() }
    val programs = remember { inject<ProgramRepository>() }
    val sessions = remember { inject<SessionRepository>() }
    // null = not read yet; false = the goal questions have never been answered or skipped.
    val onboardingDone by programs.observeOnboardingDone().collectAsState(initial = null)
    // The active program's next day, for the "+" menu.
    val texts = rememberTexts()
    val upNext by remember(texts) {
        combine(programs.observeState(), sessions.observeProgramLinks()) { state, links ->
            state.active?.let { p -> Rotation.nextDay(p, links)?.let { UpNext(ProgramDayRef(p.id, it.id), it.resolvedName(texts)) } }
        }
    }.collectAsState(initial = null)
    // The workout in progress, if any: shown as a resume bar on every screen but its own.
    val liveSessions = remember { inject<LiveSessionRepository>() }
    val live by liveSessions.observe().collectAsState(initial = null)
    // Keep the platform's tray in step with it. Only what the notice shows is watched, so typing a
    // weight does not re-post it, and a rest countdown is re-posted without one once it is over.
    LaunchedEffect(Unit) {
        liveSessions.observe()
            .map { it?.let { s -> LiveSessionNotice(s.title, s.startedAtMs, s.rest?.endsAtMs) } }
            .distinctUntilChanged()
            .collectLatest { notice ->
                val restEnds = notice?.restEndsAtMs
                if (restEnds != null && restEnds > nowMs()) {
                    notifier.update(notice)
                    delay(restEnds - nowMs())
                }
                notifier.update(notice?.copy(restEndsAtMs = null))
            }
    }
    // A tap on that notice: open the running workout once the database has said there is one.
    val resumeRequest by ResumeRequests.pending.collectAsState()
    LaunchedEffect(resumeRequest) {
        if (resumeRequest == 0) return@LaunchedEffect
        val running = liveSessions.observe().first()
        ResumeRequests.consume()
        val current = navigator.current
        if (running != null && !(current is Screen.EditSession && current.live)) {
            navigator.push(Screen.EditSession(null, running.program, live = true))
        }
    }
    // "Skip rest" on that notice: the editor running the workout drops it when there is one (its next
    // persist reaches the database and, through it, the notice); otherwise the database is changed directly.
    val skipRequest by SkipRestRequests.pending.collectAsState()
    LaunchedEffect(skipRequest) {
        if (skipRequest == 0) return@LaunchedEffect
        SkipRestRequests.consume()
        // Any editor may be open (a past workout's, say); only the one running the workout takes it.
        val editors = navigator.stack.mapNotNull { it.peek(SessionEditorModel::class) }
        if (editors.none { it.skipRest() }) liveSessions.clearRest()
    }
    val themeMode by settings.observeThemeMode().collectAsState(ThemeMode.DARK)
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    GainsTheme(darkTheme = dark) {
        val screen = navigator.current
        // Surface sets the content colour for every Text below it and paints the background.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
            if (accountState === AccountLoading) return@Surface
            if (accountState == null) { SignInScreen(); return@Surface }
            if (onboardingDone == null) return@Surface
            if (onboardingDone == false) { OnboardingScreen(onDone = {}); return@Surface }
            // Tapping outside a text field anywhere in the app puts the keyboard away.
            Column(Modifier.fillMaxSize().statusBarsPadding().dismissKeyboardOnTap()) {
                TopBar(navigator, screen, upNext)
                val transition = updateTransition(navigator.currentEntry, label = "screen")
                SwipeBack(
                    // While a screen is still sliding out it is on screen already; the swipe would draw it a second time.
                    enabled = navigator.canGoBack && !transition.isRunning && transition.currentState === transition.targetState,
                    onBack = { navigator.pop(animated = false) },
                    modifier = Modifier.weight(1f),
                    previous = { navigator.previousEntry?.let { ScreenContent(it, navigator, filePicker, photoPicker, stateHolder) } },
                ) {
                    transition.AnimatedContent(
                        transitionSpec = {
                            if (navigator.skipTransition) {
                                // The swipe-back gesture has already slid the old screen away.
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                val forward = navigator.stack.size > 1 && targetState.screen !is Screen.Home
                                val enter = fadeIn(tween(220)) + slideInHorizontally(tween(260)) { if (forward) it / 12 else -it / 12 }
                                val exit = fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { if (forward) -it / 16 else it / 16 }
                                enter togetherWith exit
                            }
                        },
                    ) { entry -> ScreenContent(entry, navigator, filePicker, photoPicker, stateHolder) }
                }
                live?.let { running ->
                    if (!(screen is Screen.EditSession && screen.live)) {
                        LiveSessionBar(running, onResume = { navigator.push(Screen.EditSession(null, running.program, live = true)) })
                    }
                }
                BottomNav(navigator)
            }
        }
    }
}

/** The active program's next day, shown in the "+" menu. */
private data class UpNext(val ref: ProgramDayRef, val dayName: String)

/**
 * One screen of the stack. Opaque, so it can slide over the screen beneath it during a swipe back
 * and so the outgoing screen never shows through the incoming one mid-transition. Its models and
 * saved UI state belong to [entry], not to this composition, so they outlive the screen being covered.
 */
@Composable
private fun ScreenContent(entry: NavEntry, navigator: Navigator, filePicker: CsvFilePicker, photoPicker: PhotoPicker, stateHolder: SaveableStateHolder) {
    DisposableEffect(entry) {
        entry.attach()
        onDispose { entry.detach() }
    }
    CompositionLocalProvider(LocalNavEntry provides entry) {
        stateHolder.SaveableStateProvider(entry.id) {
            ScreenBody(entry.screen, navigator, filePicker, photoPicker)
        }
    }
}

@Composable
private fun ScreenBody(screen: Screen, navigator: Navigator, filePicker: CsvFilePicker, photoPicker: PhotoPicker) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (screen) {
            Screen.Home -> HomeScreen(
                onImport = { navigator.push(Screen.Import) },
                onLog = { navigator.push(Screen.EditSession(null)) },
                onOpenExercise = { navigator.push(Screen.ExerciseDetail(it)) },
                onOpenSession = { navigator.push(Screen.EditSession(it)) },
                onOpenVolume = { navigator.switchTab(Tab.VOLUME) },
                onOpenOnboarding = { navigator.push(Screen.Onboarding) },
                onOpenPrograms = { navigator.push(Screen.Programs) },
                onOpenProgram = { navigator.push(Screen.ProgramDetail(it)) },
                onStartDay = { navigator.push(Screen.EditSession(null, it, live = true)) },
            )
            Screen.Exercises -> ExercisesScreen(onOpen = { navigator.push(Screen.ExerciseDetail(it)) })
            Screen.Volume -> VolumeScreen()
            Screen.Body -> BodyweightScreen()
            Screen.History -> HistoryScreen(
                onOpen = { navigator.push(Screen.EditSession(it)) },
                onLog = { navigator.push(Screen.EditSession(null)) },
            )
            is Screen.EditSession -> SessionEditorScreen(
                screen.sessionId, screen.programDay, screen.live,
                onDone = { navigator.pop() },
                // An ended workout hands over to its summary, which Back then leaves for whatever came before it.
                onEnded = { navigator.replace(Screen.SessionSummary(it)) },
                onOpenSummary = { navigator.push(Screen.SessionSummary(it)) },
            )
            is Screen.SessionSummary -> SessionSummaryScreen(screen.sessionId, photoPicker, onDone = { navigator.pop() })
            Screen.Settings -> SettingsScreen(
                onOpenPrograms = { navigator.push(Screen.Programs) },
                onOpenOnboarding = { navigator.push(Screen.Onboarding) },
            )
            Screen.Onboarding -> OnboardingScreen(onDone = { navigator.pop() })
            Screen.Programs -> ProgramsScreen(
                onOpen = { navigator.push(Screen.ProgramDetail(it)) },
                onNew = { navigator.push(Screen.ProgramEditor(null)) },
            )
            is Screen.ProgramDetail -> ProgramDetailScreen(
                screen.programId,
                onStartDay = { navigator.push(Screen.EditSession(null, it, live = true)) },
                onEdit = { navigator.push(Screen.ProgramEditor(it)) },
                onDeleted = { navigator.pop() },
            )
            is Screen.ProgramEditor -> ProgramEditorScreen(screen.programId, onDone = { navigator.pop() })
            Screen.Import -> ImportScreen(filePicker, onDone = { navigator.pop() })
            is Screen.ExerciseDetail -> ExerciseDetailScreen(screen.exerciseId, onOpenSession = { navigator.push(Screen.EditSession(it)) })
        }
    }
}

@Composable
private fun TopBar(navigator: Navigator, screen: Screen, upNext: UpNext?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigator.canGoBack) {
            IconCircle(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back)) { navigator.pop() }
            Spacer(Modifier.size(8.dp))
        } else {
            GainsWordmark(Modifier.padding(start = 4.dp))
        }
        Spacer(Modifier.weight(1f))
        // "+" offers both ways of getting a session in; hidden on the screens that already are one of them.
        if (screen != Screen.Import && screen !is Screen.EditSession && screen !is Screen.SessionSummary) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconCircle(Icons.Default.Add, stringResource(Res.string.add_description)) { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, shape = MaterialTheme.shapes.medium) {
                    if (upNext != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.menu_start_day, upNext.dayName)) },
                            leadingIcon = { Icon(Icons.Default.Star, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; navigator.push(Screen.EditSession(null, upNext.ref, live = true)) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.menu_start_workout)) },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; navigator.push(Screen.EditSession(null, live = true)) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.menu_log_past_workout)) },
                        leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; navigator.push(Screen.EditSession(null)) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.menu_import_csv)) },
                        leadingIcon = { Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; navigator.push(Screen.Import) },
                    )
                    if (screen != Screen.Programs) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.menu_programs)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; navigator.push(Screen.Programs) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        if (screen != Screen.Settings) IconCircle(Icons.Default.Settings, stringResource(Res.string.settings_description)) { navigator.push(Screen.Settings) }
    }
}

@Composable
private fun IconCircle(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun BottomNav(navigator: Navigator) {
    val palette = GainsColors.palette
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            for (tab in Tab.entries) {
                val selected = navigator.currentTab == tab && !navigator.canGoBack
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(if (selected) palette.volt else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(interaction, indication = null) { navigator.switchTab(tab) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val label = tab.label()
                    Icon(
                        tab.icon(), label,
                        tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The workout in progress, above the tabs: its name, the total time, the rest left, and a tap to get
 * back to it. Both clocks run against wall time, so the bar is right straight after a relaunch.
 */
@Composable
private fun LiveSessionBar(live: LiveSession, onResume: () -> Unit) {
    val palette = GainsColors.palette
    var now by remember { mutableStateOf(nowMs()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = nowMs() } }
    val remaining = live.rest?.remainingSeconds(now)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(CircleShape)
            .background(palette.volt)
            .clickable(onClick = onResume)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val onAccent = MaterialTheme.colorScheme.onPrimary
        Text(live.title, style = MaterialTheme.typography.titleSmall, color = onAccent, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(Format.clock(live.elapsedMs(now) / 1000), style = MaterialTheme.typography.titleSmall, color = onAccent)
        if (remaining != null && remaining > 0) {
            Spacer(Modifier.size(10.dp))
            Text(stringResource(Res.string.rest_countdown, Format.clock(remaining.toLong())), style = MaterialTheme.typography.bodySmall, color = onAccent)
        }
        Spacer(Modifier.size(10.dp))
        Text(stringResource(Res.string.resume_chevron), style = MaterialTheme.typography.labelSmall, color = onAccent)
    }
}

/** Sentinel for "account preference not read yet", so the sign-in screen does not flash on launch. */
private val AccountLoading = app.gains.auth.Account(app.gains.auth.AccountKind.GUEST, displayName = "__loading__")

private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Default.Home
    Tab.HISTORY -> Icons.Default.DateRange
    Tab.EXERCISES -> Icons.AutoMirrored.Filled.List
    Tab.VOLUME -> Icons.Default.Star
    Tab.BODY -> Icons.Default.Favorite
}
