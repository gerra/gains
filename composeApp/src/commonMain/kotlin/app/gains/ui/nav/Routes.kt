package app.gains.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Modifier
import app.gains.platform.CsvFilePicker
import app.gains.platform.PhotoPicker
import app.gains.ui.screens.BodyweightScreen
import app.gains.ui.screens.ExerciseDetailScreen
import app.gains.ui.screens.ExercisesScreen
import app.gains.ui.screens.HistoryScreen
import app.gains.ui.screens.HomeScreen
import app.gains.ui.screens.ImportScreen
import app.gains.ui.screens.OnboardingScreen
import app.gains.ui.screens.ProgramDetailScreen
import app.gains.ui.screens.ProgramEditorScreen
import app.gains.ui.screens.ProgramsScreen
import app.gains.ui.screens.SessionEditorScreen
import app.gains.ui.screens.SessionSummaryScreen
import app.gains.ui.screens.SettingsScreen
import app.gains.ui.screens.TrophiesScreen
import app.gains.ui.screens.VolumeScreen

// Which composable draws each Screen, and where each of its buttons leads on the navigator: the one
// place a new screen is wired in, kept out of App.kt so that file is left with the assembly.

/**
 * One screen of the stack. Opaque, so it can slide over the screen beneath it during a swipe back
 * and so the outgoing screen never shows through the incoming one mid-transition. Its models and
 * saved UI state belong to [entry], not to this composition, so they outlive the screen being covered.
 */
@Composable
internal fun ScreenContent(entry: NavEntry, navigator: Navigator, filePicker: CsvFilePicker, photoPicker: PhotoPicker, stateHolder: SaveableStateHolder) {
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
                onOpenHistory = { navigator.switchTab(Tab.HISTORY) },
                onOpenOnboarding = { navigator.push(Screen.Onboarding) },
                onOpenPrograms = { navigator.push(Screen.Programs) },
                onOpenProgram = { navigator.push(Screen.ProgramDetail(it)) },
                onStartDay = { navigator.push(Screen.EditSession(null, it, live = true)) },
                onOpenTrophies = { navigator.push(Screen.Trophies) },
            )
            Screen.Trophies -> TrophiesScreen(
                onOpenExercise = { navigator.push(Screen.ExerciseDetail(it)) },
                onOpenSession = { navigator.push(Screen.EditSession(it)) },
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
