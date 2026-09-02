package app.gains.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gains.analysis.TrainingData
import app.gains.auth.Account
import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.data.ExerciseRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.data.ThemeMode
import app.gains.domain.Exercise
import app.gains.domain.WeightUnit
import app.gains.ui.ScreenModel
import app.gains.ui.components.ChipRow
import app.gains.ui.components.Dp16
import app.gains.ui.components.GainsCard
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.ScreenTitle
import app.gains.ui.components.SecondaryButton
import app.gains.ui.components.SectionHeader
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsState(
    val account: Account? = null,
    val unit: WeightUnit = WeightUnit.KG,
    val theme: ThemeMode = ThemeMode.DARK,
    val customExercises: List<Exercise> = emptyList(),
    val catalogue: List<Exercise> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
    val overrides: Map<String, Double> = emptyMap(),
    val exercisesById: Map<String, Exercise> = emptyMap(),
)

class SettingsModel(
    private val settings: SettingsRepository = inject(),
    private val accounts: AccountRepository = inject(),
    val authConfig: AuthConfig = inject(),
    private val exercises: ExerciseRepository = inject(),
    private val sessions: SessionRepository = inject(),
    trainingData: TrainingData = inject(),
) : ScreenModel() {
    val state: StateFlow<SettingsState> = combine(
        combine(settings.observeUnit(), settings.observeThemeMode(), accounts.observeAccount()) { u, t, a -> Triple(u, t, a) },
        trainingData.snapshot, exercises.observeAliases(), exercises.observeWorkingSetRatios(),
    ) { (unit, theme, account), snapshot, aliases, overrides ->
        SettingsState(
            account = account,
            unit = unit,
            theme = theme,
            customExercises = snapshot.exercises.filter { !it.isBuiltIn }.sortedBy { it.name },
            catalogue = snapshot.exercises.filter { it.isBuiltIn }.sortedBy { it.name },
            aliases = aliases,
            overrides = overrides,
            exercisesById = snapshot.exercisesById,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun setUnit(unit: WeightUnit) { scope.launch { settings.setUnit(unit) } }
    fun setTheme(mode: ThemeMode) { scope.launch { settings.setThemeMode(mode) } }
    fun signOut() { scope.launch { accounts.signOut() } }
    fun merge(custom: Exercise, into: Exercise) { scope.launch { exercises.merge(custom.id, into.id, custom.name) } }
    fun removeAlias(raw: String) { scope.launch { exercises.removeAlias(raw) } }
    fun clearOverride(exerciseId: String) { scope.launch { exercises.setWorkingSetRatio(exerciseId, null) } }
    fun deleteAllData() { scope.launch { sessions.deleteAll() } }
}

@Composable
fun SettingsScreen() {
    val model = rememberScreenModel { SettingsModel() }
    val state by model.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val palette = GainsColors.palette

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle("Settings")
            SectionHeader("Account")
            GainsCard(Modifier.fillMaxWidth()) {
                val account = state.account
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(account?.displayName ?: account?.kind?.label ?: "Not signed in", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                account == null -> ""
                                account.isGuest -> "Data is saved on this device only. Sign in later to back it up and sync."
                                else -> account.email ?: "Synced to your server"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { model.signOut() }) { Text(if (account?.isGuest == true) "Sign in" else "Sign out", color = palette.volt) }
                }
                if (!model.authConfig.googleEnabled && !model.authConfig.appleEnabled) {
                    Spacer(Modifier.height(6.dp))
                    Text("Google and Apple sign-in are not configured yet; the sync server is coming later.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionHeader("Appearance")
            GainsCard(Modifier.fillMaxWidth()) {
                ChipRow(ThemeMode.entries, state.theme, { it.label }, { model.setTheme(it) })
            }
            SectionHeader("Display units")
            GainsCard(Modifier.fillMaxWidth()) {
                ChipRow(WeightUnit.entries, state.unit, { it.label }, { model.setUnit(it) })
                Spacer(Modifier.height(10.dp))
                Text("Weights are stored in kg (rounded to 0.25 kg) whatever you display. Dumbbell exercises show the per-dumbbell weight.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SectionHeader("Custom exercises")
            Text(
                if (state.customExercises.isEmpty()) "Every imported exercise matched the built-in catalogue."
                else "Names the catalogue didn't recognise. Merge one into a catalogue exercise to combine its history and remember the mapping for future imports.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(state.customExercises, key = { it.id }) { custom ->
            MergeRow(custom, state.catalogue, onMerge = { model.merge(custom, it) })
        }
        if (state.aliases.isNotEmpty()) {
            item { SectionHeader("Aliases") }
            items(state.aliases.entries.toList(), key = { it.key }) { (raw, id) ->
                GainsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentPadding = Dp16.Tight) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(raw, style = MaterialTheme.typography.titleSmall)
                            Text("→ ${state.exercisesById[id]?.name ?: id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { model.removeAlias(raw) }) { Text("Remove", color = palette.coral) }
                    }
                }
            }
        }
        if (state.overrides.isNotEmpty()) {
            item { SectionHeader("Working-set overrides") }
            items(state.overrides.entries.toList(), key = { "o" + it.key }) { (id, ratio) ->
                GainsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentPadding = Dp16.Tight) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.exercisesById[id]?.name ?: id}: ${(ratio * 100).toInt()}%", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { model.clearOverride(id) }) { Text("Reset", color = palette.coral) }
                    }
                }
            }
        }
        item {
            SectionHeader("Data")
            GainsCard(Modifier.fillMaxWidth()) {
                Text("Imported workouts and bodyweight entries live in a local database on this device. Nothing leaves the device until sync exists and you sign in.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                SecondaryButton("Delete all imported sessions", onClick = { confirmDelete = true })
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = MaterialTheme.shapes.large,
            title = { Text("Delete all sessions?") },
            text = { Text("Imported workouts will be removed. Bodyweight entries, aliases and overrides are kept. You can re-import the CSV at any time.") },
            confirmButton = { PrimaryButton("Delete", onClick = { model.deleteAllData(); confirmDelete = false }) },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MergeRow(custom: Exercise, catalogue: List<Exercise>, onMerge: (Exercise) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val palette = GainsColors.palette
    GainsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentPadding = Dp16.Tight) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(custom.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (custom.muscleGroups.isEmpty()) "No muscle groups guessed" else custom.muscleGroups.joinToString { it.group.displayName },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column {
                TextButton(onClick = { open = true }) { Text("Merge into…", color = palette.volt) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.medium) {
                    for (e in catalogue) DropdownMenuItem(text = { Text(e.name) }, onClick = { onMerge(e); open = false })
                }
            }
        }
    }
}
