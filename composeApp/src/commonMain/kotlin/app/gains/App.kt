package app.gains

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.gains.data.ExerciseRepository
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.ui.inject
import app.gains.ui.nav.Navigator
import app.gains.ui.nav.Screen
import app.gains.ui.nav.Tab
import app.gains.ui.screens.BodyweightScreen
import app.gains.ui.screens.ConsistencyScreen
import app.gains.ui.screens.ExerciseDetailScreen
import app.gains.ui.screens.ExercisesScreen
import app.gains.ui.screens.HomeScreen
import app.gains.ui.screens.ImportScreen
import app.gains.ui.screens.SettingsScreen
import app.gains.ui.screens.VolumeScreen
import app.gains.ui.theme.GainsTheme

/** Root of the shared UI. [filePicker] is supplied by each platform entry point. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(filePicker: CsvFilePicker, onBackHandler: ((() -> Boolean)) -> Unit = {}) {
    val navigator = remember { Navigator() }
    val exercises = remember { inject<ExerciseRepository>() }
    LaunchedEffect(Unit) { exercises.seedCatalogue() }

    // Files shared into the app open the import screen.
    val incoming by IncomingFiles.pending.collectAsState()
    LaunchedEffect(incoming) { if (incoming != null && navigator.current != Screen.Import) navigator.push(Screen.Import) }
    LaunchedEffect(navigator) { onBackHandler { navigator.pop() } }

    GainsTheme {
        val screen = navigator.current
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title(screen)) },
                    navigationIcon = {
                        if (navigator.canGoBack) IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        if (screen != Screen.Import) IconButton(onClick = { navigator.push(Screen.Import) }) { Icon(Icons.Default.Add, "Import CSV") }
                        if (screen != Screen.Settings) IconButton(onClick = { navigator.push(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    for (tab in Tab.entries) {
                        NavigationBarItem(
                            selected = navigator.currentTab == tab && !navigator.canGoBack,
                            onClick = { navigator.switchTab(tab) },
                            icon = { Icon(tab.icon(), tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    Screen.Home -> HomeScreen(
                        onImport = { navigator.push(Screen.Import) },
                        onOpenExercise = { navigator.push(Screen.ExerciseDetail(it)) },
                        onOpenVolume = { navigator.switchTab(Tab.VOLUME) },
                    )
                    Screen.Exercises -> ExercisesScreen(onOpen = { navigator.push(Screen.ExerciseDetail(it)) })
                    Screen.Volume -> VolumeScreen()
                    Screen.Body -> BodyweightScreen()
                    Screen.Consistency -> ConsistencyScreen()
                    Screen.Settings -> SettingsScreen()
                    Screen.Import -> ImportScreen(filePicker, onDone = { navigator.pop() })
                    is Screen.ExerciseDetail -> ExerciseDetailScreen(screen.exerciseId)
                }
            }
        }
    }
}

private fun title(screen: Screen): String = when (screen) {
    Screen.Home -> "Gains"
    Screen.Exercises -> "Lifts"
    Screen.Volume -> "Weekly volume"
    Screen.Body -> "Bodyweight"
    Screen.Consistency -> "Consistency"
    Screen.Settings -> "Settings"
    Screen.Import -> "Import"
    is Screen.ExerciseDetail -> "Lift"
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Default.Home
    Tab.EXERCISES -> Icons.AutoMirrored.Filled.List
    Tab.VOLUME -> Icons.Default.Star
    Tab.BODY -> Icons.Default.Favorite
    Tab.CONSISTENCY -> Icons.Default.DateRange
}
