package app.gains.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.data.ThemeMode
import app.gains.domain.Exercise
import app.gains.domain.Experience
import app.gains.domain.Goal
import app.gains.domain.GoalProfile
import app.gains.domain.Units
import app.gains.domain.WeightUnit
import app.gains.analysis.Format
import app.gains.program.Gzclp
import app.gains.ui.ScreenModel
import app.gains.ui.components.ChipRow
import app.gains.ui.components.Dp16
import app.gains.ui.components.GainsCard
import app.gains.ui.components.KeyValueRow
import app.gains.ui.components.Pill
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.ScreenTitle
import app.gains.ui.components.SecondaryButton
import app.gains.ui.components.SectionHeader
import app.gains.i18n.Strings
import app.gains.ui.i18n.strings
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class SettingsState(
    val account: Account? = null,
    val unit: WeightUnit = WeightUnit.KG,
    val theme: ThemeMode = ThemeMode.DARK,
    val customExercises: List<Exercise> = emptyList(),
    val catalogue: List<Exercise> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
    val overrides: Map<String, Double> = emptyMap(),
    val exercisesById: Map<String, Exercise> = emptyMap(),
    val profile: GoalProfile? = null,
    val activeProgramName: String? = null,
    /** Program days pre-fill warm-up sets. */
    val autoWarmups: Boolean = true,
    val barWeightKg: Double = Gzclp.DEFAULT_BAR_KG,
)

/** The plain preferences, combined first because combine takes five flows at most. */
private data class Prefs(val unit: WeightUnit, val theme: ThemeMode, val account: Account?, val autoWarmups: Boolean, val barWeightKg: Double)

internal class SettingsModel(
    strings: Strings,
    private val settings: SettingsRepository = inject(),
    private val accounts: AccountRepository = inject(),
    val authConfig: AuthConfig = inject(),
    private val exercises: ExerciseRepository = inject(),
    private val sessions: SessionRepository = inject(),
    private val programs: ProgramRepository = inject(),
    trainingData: TrainingData = inject(),
) : ScreenModel() {
    val state: StateFlow<SettingsState> = combine(
        combine(settings.observeUnit(), settings.observeThemeMode(), accounts.observeAccount(), settings.observeAutoWarmups(), settings.observeBarWeightKg()) { u, t, a, w, b -> Prefs(u, t, a, w, b) },
        trainingData.snapshot, exercises.observeAliases(), exercises.observeWorkingSetRatios(), programs.observeState(),
    ) { prefs, snapshot, aliases, overrides, programState ->
        SettingsState(
            profile = programState.profile,
            activeProgramName = programState.active?.let(strings::programName),
            account = prefs.account,
            unit = prefs.unit,
            theme = prefs.theme,
            autoWarmups = prefs.autoWarmups,
            barWeightKg = prefs.barWeightKg,
            customExercises = snapshot.exercises.filter { !it.isBuiltIn }.sortedBy { it.name },
            catalogue = snapshot.exercises.filter { it.isBuiltIn }.sortedBy { it.name },
            aliases = aliases,
            overrides = overrides,
            exercisesById = snapshot.exercisesById,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun setUnit(unit: WeightUnit) { scope.launch { settings.setUnit(unit) } }
    fun setTheme(mode: ThemeMode) { scope.launch { settings.setThemeMode(mode) } }
    fun setAutoWarmups(on: Boolean) { scope.launch { settings.setAutoWarmups(on) } }
    fun setBarWeightKg(kg: Double) { scope.launch { settings.setBarWeightKg(kg) } }
    fun signOut() { scope.launch { accounts.signOut() } }
    fun merge(custom: Exercise, into: Exercise) { scope.launch { exercises.merge(custom.id, into.id, custom.name) } }
    fun removeAlias(raw: String) { scope.launch { exercises.removeAlias(raw) } }
    fun clearOverride(exerciseId: String) { scope.launch { exercises.setWorkingSetRatio(exerciseId, null) } }
    fun deleteAllData() { scope.launch { sessions.deleteAll() } }

    fun setGoal(goal: Goal) = updateProfile { it.copy(goal = goal) }
    fun setExperience(experience: Experience) = updateProfile { it.copy(experience = experience) }
    fun setDays(days: Int) = updateProfile { it.copy(daysPerWeek = days) }
    private fun updateProfile(f: (GoalProfile) -> GoalProfile) {
        val current = state.value.profile ?: GoalProfile(Goal.GENERAL_FITNESS, Experience.BEGINNER, 3)
        scope.launch { programs.setProfile(f(current)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(onOpenPrograms: () -> Unit = {}, onOpenOnboarding: () -> Unit = {}) {
    val strings = strings
    val model = rememberScreenModel(strings) { SettingsModel(strings) }
    val state by model.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val palette = GainsColors.palette

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle(strings.settingsTitle)
            SectionHeader(strings.account)
            GainsCard(Modifier.fillMaxWidth()) {
                val account = state.account
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(account?.displayName ?: account?.kind?.let(strings::accountKind) ?: strings.notSignedIn, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                account == null -> ""
                                account.isGuest -> strings.guestDataNote
                                else -> account.email ?: strings.syncedToServer
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { model.signOut() }) { Text(if (account?.isGuest == true) strings.signIn else strings.signOut, color = palette.volt) }
                }
                if (!model.authConfig.googleEnabled && !model.authConfig.appleEnabled) {
                    Spacer(Modifier.height(6.dp))
                    Text(strings.signInNotConfiguredNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionHeader(strings.trainingGoal)
            GainsCard(Modifier.fillMaxWidth()) {
                val profile = state.profile
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (g in Goal.entries) Pill(strings.goal(g), palette.volt, filled = profile?.goal == g, onClick = { model.setGoal(g) })
                }
                Spacer(Modifier.height(10.dp))
                ChipRow(Experience.entries, profile?.experience ?: Experience.BEGINNER, { strings.experience(it) }, { model.setExperience(it) })
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.daysAWeekLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    ChipRow((GoalProfile.MIN_DAYS..GoalProfile.MAX_DAYS).toList(), profile?.daysPerWeek ?: 3, { it.toString() }, { model.setDays(it) })
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KeyValueRow(strings.activeProgram, state.activeProgramName ?: strings.none, Modifier.weight(1f))
                    TextButton(onClick = onOpenPrograms) { Text(strings.change, color = palette.volt) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onOpenOnboarding) { Text(strings.redoSetup, color = palette.volt) }
                }
                Text(
                    if (profile == null) strings.noGoalSetNote else strings.goalSortNote(profile.goal),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionHeader(strings.appearance)
            GainsCard(Modifier.fillMaxWidth()) {
                ChipRow(ThemeMode.entries, state.theme, { strings.themeMode(it) }, { model.setTheme(it) })
            }
            SectionHeader(strings.displayUnits)
            GainsCard(Modifier.fillMaxWidth()) {
                ChipRow(WeightUnit.entries, state.unit, { strings.unit(it) }, { model.setUnit(it) })
                Spacer(Modifier.height(10.dp))
                Text(strings.unitsNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionHeader(strings.warmUps)
            GainsCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.prefillWarmUps, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    ChipRow(listOf(true, false), state.autoWarmups, { if (it) strings.on else strings.off }, { model.setAutoWarmups(it) })
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.emptyBar, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    val options = if (state.unit == WeightUnit.KG) listOf(10.0, 15.0, 20.0) else listOf(25.0, 35.0, 45.0)
                    val current = Units.display(state.barWeightKg, state.unit)
                    val selected = options.minBy { kotlin.math.abs(it - current) }
                    ChipRow(options, selected, { "${Format.number(it, 0)} ${strings.unit(state.unit)}" }, { model.setBarWeightKg(Units.roundToQuarter(Units.fromDisplay(it, state.unit))) })
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    strings.warmUpsNote,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionHeader(strings.customExercises)
            Text(
                if (state.customExercises.isEmpty()) strings.allExercisesMatched else strings.customExercisesNote,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(state.customExercises, key = { it.id }) { custom ->
            MergeRow(custom, state.catalogue, onMerge = { model.merge(custom, it) })
        }
        if (state.aliases.isNotEmpty()) {
            item { SectionHeader(strings.aliases) }
            items(state.aliases.entries.toList(), key = { it.key }) { (raw, id) ->
                GainsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentPadding = Dp16.Tight) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(raw, style = MaterialTheme.typography.titleSmall)
                            Text("→ ${state.exercisesById[id]?.let(strings::exerciseName) ?: id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { model.removeAlias(raw) }) { Text(strings.remove, color = palette.coral) }
                    }
                }
            }
        }
        if (state.overrides.isNotEmpty()) {
            item { SectionHeader(strings.workingSetOverrides) }
            items(state.overrides.entries.toList(), key = { "o" + it.key }) { (id, ratio) ->
                GainsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentPadding = Dp16.Tight) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.exercisesById[id]?.let(strings::exerciseName) ?: id}: ${(ratio * 100).toInt()}%", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { model.clearOverride(id) }) { Text(strings.reset, color = palette.coral) }
                    }
                }
            }
        }
        item {
            SectionHeader(strings.data)
            GainsCard(Modifier.fillMaxWidth()) {
                Text(strings.dataNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                SecondaryButton(strings.deleteAllSessions, onClick = { confirmDelete = true })
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = MaterialTheme.shapes.large,
            title = { Text(strings.deleteAllSessionsTitle) },
            text = { Text(strings.deleteAllSessionsBody) },
            confirmButton = { PrimaryButton(strings.delete, onClick = { model.deleteAllData(); confirmDelete = false }) },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(strings.cancel) } },
        )
    }
}

@Composable
private fun MergeRow(custom: Exercise, catalogue: List<Exercise>, onMerge: (Exercise) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val palette = GainsColors.palette
    val strings = strings
    GainsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentPadding = Dp16.Tight) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(custom.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (custom.muscleGroups.isEmpty()) strings.noMuscleGroupsGuessed else custom.muscleGroups.joinToString { strings.muscleGroup(it.group) },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { open = true }) { Text(strings.mergeInto, color = palette.volt) }
        }
    }
    if (open) ExercisePickerSheet(
        catalogue = catalogue,
        recent = emptyList(),
        alreadyAdded = setOf(custom.id),
        onAdd = { picked -> picked.firstOrNull()?.let(onMerge) },
        onCreate = null,
        onDismiss = { open = false },
        single = true,
    )
}
