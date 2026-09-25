package app.gains.ui.screens

import androidx.compose.foundation.layout.Box
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
import app.gains.analysis.Format
import app.gains.analysis.StreakEngine
import app.gains.analysis.TrainingData
import app.gains.auth.Account
import app.gains.auth.AccountKind
import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.data.AppLanguage
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
import app.gains.program.Gzclp
import app.gains.sync.SyncController
import app.gains.sync.SyncEngine
import app.gains.sync.SyncStore
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
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.i18n.*
import org.jetbrains.compose.resources.stringResource
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class SettingsState(
    val account: Account? = null,
    val unit: WeightUnit = WeightUnit.KG,
    val theme: ThemeMode = ThemeMode.DARK,
    val language: AppLanguage = AppLanguage.SYSTEM,
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
    /** The streak reminder may be sent. Off until asked for. */
    val streakReminder: Boolean = false,
    /** The hour it would arrive at: the one they usually train. */
    val reminderHour: Int = StreakEngine.REMINDER_HOURS.first,
)

/** The plain preferences, combined first — and two of them paired — because combine takes five flows at most. */
private data class Prefs(val unit: WeightUnit, val theme: ThemeMode, val language: AppLanguage, val account: Account?, val autoWarmups: Boolean, val barWeightKg: Double)

internal class SettingsModel(
    texts: Texts,
    private val settings: SettingsRepository = inject(),
    private val accounts: AccountRepository = inject(),
    val authConfig: AuthConfig = inject(),
    private val exercises: ExerciseRepository = inject(),
    private val sessions: SessionRepository = inject(),
    private val programs: ProgramRepository = inject(),
    trainingData: TrainingData = inject(),
    syncEngine: SyncEngine = inject(),
    syncStore: SyncStore = inject(),
    private val syncController: SyncController = inject(),
) : ScreenModel() {
    val state: StateFlow<SettingsState> = combine(
        combine(
            settings.observeUnit(),
            combine(settings.observeThemeMode(), settings.observeLanguage()) { theme, language -> theme to language },
            accounts.observeAccount(),
            settings.observeAutoWarmups(),
            settings.observeBarWeightKg(),
        ) { u, (t, l), a, w, b -> Prefs(u, t, l, a, w, b) },
        combine(trainingData.snapshot, settings.observeStreakReminder()) { snapshot, reminder -> snapshot to reminder },
        exercises.observeAliases(), exercises.observeWorkingSetRatios(), programs.observeState(),
    ) { prefs, (snapshot, reminder), aliases, overrides, programState ->
        SettingsState(
            streakReminder = reminder == true,
            reminderHour = StreakEngine.usualHour(snapshot.sessions),
            profile = programState.profile,
            activeProgramName = programState.active?.resolvedName(texts),
            account = prefs.account,
            unit = prefs.unit,
            theme = prefs.theme,
            language = prefs.language,
            autoWarmups = prefs.autoWarmups,
            barWeightKg = prefs.barWeightKg,
            customExercises = snapshot.exercises.filter { !it.isBuiltIn }.sortedBy { it.name },
            catalogue = snapshot.exercises.filter { it.isBuiltIn }.sortedBy { it.name },
            aliases = aliases,
            overrides = overrides,
            exercisesById = snapshot.exercisesById,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    /** The account card's sync line; see [syncUi]. */
    val sync: StateFlow<SyncUi> = combine(
        accounts.observeAccount(), syncEngine.status, syncStore.observePendingCount(), syncStore.observeLastSyncedAt(),
    ) { account, status, pending, last -> syncUi(authConfig.syncEnabled, account, status, pending, last) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), SyncUi.Hidden)

    /** "Sync now": the controller runs it after its usual short wait, never alongside another run. */
    fun syncNow() = syncController.requestSync()

    fun setUnit(unit: WeightUnit) { scope.launch { settings.setUnit(unit) } }
    fun setTheme(mode: ThemeMode) { scope.launch { settings.setThemeMode(mode) } }
    fun setLanguage(language: AppLanguage) { scope.launch { settings.setLanguage(language) } }
    fun setAutoWarmups(on: Boolean) { scope.launch { settings.setAutoWarmups(on) } }
    fun setStreakReminder(on: Boolean) { scope.launch { settings.setStreakReminder(on) } }
    fun setBarWeightKg(kg: Double) { scope.launch { settings.setBarWeightKg(kg) } }
    fun signOut() { scope.launch { accounts.signOut() } }

    /**
     * Signs a guest in where they stand, without the sign-out that would drop them on the welcome
     * screen: the account turns into the provider's when the server answers, the sync controller
     * starts the first sync because of it, and that sync uploads everything on the device.
     */
    val link = SignInAttempt(scope, accounts)
    /** A link is in flight; the buttons wait for it. */
    val linking: Boolean get() = link.running
    fun linkGoogle() = link.google()
    fun linkApple() = link.apple()

    /**
     * Signing back in after the server answered 401, with the same buttons. The account row
     * usually doesn't change (same person, same provider), so the controller sees no new sign-in;
     * the sync is asked for here instead, which also clears the "signed out" line once it passes.
     */
    fun relinkGoogle() { link.google().invokeOnCompletion { syncNow() } }
    fun relinkApple() { link.apple().invokeOnCompletion { syncNow() } }

    /** Deletes the account on the server; see [AccountDeletion]. */
    val deletion = AccountDeletion(scope, accounts)
    fun deleteAccount() = deletion.run()

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

/**
 * Deleting the signed-in account from Settings, which App Store guideline 5.1.1(v) asks of any app
 * that creates accounts. While the call is out the card's buttons wait for it. A failure (offline,
 * a 5xx) leaves the person signed in with a line saying so, because [AccountRepository.deleteAccount]
 * signs out only once the server has confirmed; on success the account turns `null` and the app
 * shows the sign-in screen, as after a sign-out.
 */
internal class AccountDeletion(private val scope: CoroutineScope, private val accounts: AccountRepository) {
    var running by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun run(): Job {
        if (running) return Job().apply { complete() }
        running = true
        failed = false
        return scope.launch {
            try {
                accounts.deleteAccount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            } finally {
                running = false
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(onOpenPrograms: () -> Unit = {}, onOpenOnboarding: () -> Unit = {}) {
    val texts = rememberTexts()
    val model = rememberScreenModel { SettingsModel(texts) }
    val state by model.state.collectAsState()
    val sync by model.sync.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteAccount by remember { mutableStateOf(false) }
    val palette = GainsColors.palette

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle(stringResource(Res.string.settings_title))
            SectionHeader(stringResource(Res.string.account))
            GainsCard(Modifier.fillMaxWidth()) {
                val account = state.account
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(account?.displayName ?: account?.kind?.label() ?: stringResource(Res.string.not_signed_in), style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                account == null -> ""
                                account.isGuest -> stringResource(Res.string.guest_data_note)
                                else -> account.email ?: stringResource(Res.string.synced_to_server)
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (account?.isGuest != true) {
                        TextButton(onClick = { model.signOut() }, enabled = !model.deletion.running) { Text(stringResource(Res.string.sign_out), color = palette.volt) }
                    }
                }
                if (sync != SyncUi.Hidden) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { SyncStatusText(sync) }
                        TextButton(onClick = { model.syncNow() }, enabled = sync != SyncUi.Running && !model.deletion.running) {
                            Text(stringResource(Res.string.sync_now), color = palette.volt)
                        }
                    }
                }
                val providers = signInButtons(model.authConfig)
                if (sync == SyncUi.SignedOut && providers.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LinkButtons(providers, model, onApple = { model.relinkApple() }, onGoogle = { model.relinkGoogle() }, note = false)
                }
                if (account != null && !account.isGuest) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { confirmDeleteAccount = true }, enabled = !model.deletion.running) {
                            Text(stringResource(Res.string.delete_account), color = palette.coral)
                        }
                    }
                    if (model.deletion.failed) {
                        Text(stringResource(Res.string.delete_account_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (account?.isGuest == true && providers.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    LinkButtons(providers, model, onApple = { model.linkApple() }, onGoogle = { model.linkGoogle() }, note = true)
                }
                if (!model.authConfig.googleEnabled && !model.authConfig.appleEnabled) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(Res.string.sign_in_not_configured_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionHeader(stringResource(Res.string.training_goal))
            GainsCard(Modifier.fillMaxWidth()) {
                val profile = state.profile
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (g in Goal.entries) Pill(g.label(), palette.volt, filled = profile?.goal == g, onClick = { model.setGoal(g) })
                }
                Spacer(Modifier.height(10.dp))
                ChipRow(Experience.entries, profile?.experience ?: Experience.BEGINNER, { it.label() }, { model.setExperience(it) })
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.days_a_week_label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    ChipRow((GoalProfile.MIN_DAYS..GoalProfile.MAX_DAYS).toList(), profile?.daysPerWeek ?: 3, { it.toString() }, { model.setDays(it) })
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KeyValueRow(stringResource(Res.string.active_program), state.activeProgramName ?: stringResource(Res.string.none), Modifier.weight(1f))
                    TextButton(onClick = onOpenPrograms) { Text(stringResource(Res.string.change), color = palette.volt) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onOpenOnboarding) { Text(stringResource(Res.string.redo_setup), color = palette.volt) }
                }
                Text(
                    if (profile == null) stringResource(Res.string.no_goal_set_note) else stringResource(Res.string.goal_sort_note, profile.goal.label().lowercase()),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionHeader(stringResource(Res.string.appearance))
            GainsCard(Modifier.fillMaxWidth()) {
                ChipRow(ThemeMode.entries, state.theme, { it.label() }, { model.setTheme(it) })
            }
            SectionHeader(stringResource(Res.string.language))
            GainsCard(Modifier.fillMaxWidth()) {
                ChipRow(AppLanguage.entries, state.language, { it.label() }, { model.setLanguage(it) })
                Spacer(Modifier.height(10.dp))
                Text(stringResource(Res.string.language_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionHeader(stringResource(Res.string.display_units))
            GainsCard(Modifier.fillMaxWidth()) {
                ChipRow(WeightUnit.entries, state.unit, { it.label() }, { model.setUnit(it) })
                Spacer(Modifier.height(10.dp))
                Text(stringResource(Res.string.units_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionHeader(stringResource(Res.string.streak_reminder))
            GainsCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.streak_reminder), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    ChipRow(listOf(true, false), state.streakReminder, { if (it) stringResource(Res.string.on) else stringResource(Res.string.off) }, { model.setStreakReminder(it) })
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (state.streakReminder) stringResource(Res.string.streak_reminder_note, clockHour(state.reminderHour)) else stringResource(Res.string.streak_reminder_note_off),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionHeader(stringResource(Res.string.warm_ups))
            GainsCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.prefill_warm_ups), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    ChipRow(listOf(true, false), state.autoWarmups, { if (it) stringResource(Res.string.on) else stringResource(Res.string.off) }, { model.setAutoWarmups(it) })
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.empty_bar), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    val options = if (state.unit == WeightUnit.KG) listOf(10.0, 15.0, 20.0) else listOf(25.0, 35.0, 45.0)
                    val current = Units.display(state.barWeightKg, state.unit)
                    val selected = options.minBy { kotlin.math.abs(it - current) }
                    ChipRow(options, selected, { "${Format.number(it, 0)} ${state.unit.label()}" }, { model.setBarWeightKg(Units.roundToQuarter(Units.fromDisplay(it, state.unit))) })
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(Res.string.warm_ups_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionHeader(stringResource(Res.string.custom_exercises))
            Text(
                if (state.customExercises.isEmpty()) stringResource(Res.string.all_exercises_matched) else stringResource(Res.string.custom_exercises_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        // Merged, removed or reset, a row fades and the list closes up behind it.
        items(state.customExercises, key = { it.id }) { custom ->
            Box(Modifier.animateItem()) { MergeRow(custom, state.catalogue, onMerge = { model.merge(custom, it) }) }
        }
        if (state.aliases.isNotEmpty()) {
            item { SectionHeader(stringResource(Res.string.aliases)) }
            items(state.aliases.entries.toList(), key = { it.key }) { (raw, id) ->
                GainsCard(Modifier.animateItem().fillMaxWidth().padding(bottom = 6.dp), contentPadding = Dp16.Tight) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(raw, style = MaterialTheme.typography.titleSmall)
                            Text("→ ${state.exercisesById[id]?.displayName() ?: id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { model.removeAlias(raw) }) { Text(stringResource(Res.string.remove), color = palette.coral) }
                    }
                }
            }
        }
        if (state.overrides.isNotEmpty()) {
            item { SectionHeader(stringResource(Res.string.working_set_overrides)) }
            items(state.overrides.entries.toList(), key = { "o" + it.key }) { (id, ratio) ->
                GainsCard(Modifier.animateItem().fillMaxWidth().padding(bottom = 6.dp), contentPadding = Dp16.Tight) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.exercisesById[id]?.displayName() ?: id}: ${(ratio * 100).toInt()}%", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { model.clearOverride(id) }) { Text(stringResource(Res.string.reset), color = palette.coral) }
                    }
                }
            }
        }
        item {
            SectionHeader(stringResource(Res.string.data))
            GainsCard(Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.data_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                SecondaryButton(stringResource(Res.string.delete_all_sessions), onClick = { confirmDelete = true })
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(Res.string.delete_all_sessions_title)) },
            text = { Text(stringResource(Res.string.delete_all_sessions_body)) },
            confirmButton = { PrimaryButton(stringResource(Res.string.delete), onClick = { model.deleteAllData(); confirmDelete = false }) },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
    if (confirmDeleteAccount) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAccount = false },
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(Res.string.delete_account_title)) },
            text = { Text(stringResource(Res.string.delete_account_body)) },
            confirmButton = {
                TextButton(onClick = { model.deleteAccount(); confirmDeleteAccount = false }) {
                    Text(stringResource(Res.string.delete), color = palette.coral)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAccount = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/**
 * The enabled providers' buttons in the account card, and under them what the last attempt came to.
 * A guest links with them ([note] explains the upload); a signed-in account whose token the server
 * turned down signs in again with the same ones, where the sync line above already says why.
 */
@Composable
private fun LinkButtons(providers: List<AccountKind>, model: SettingsModel, onApple: () -> Unit, onGoogle: () -> Unit, note: Boolean) {
    for (kind in providers) {
        when (kind) {
            AccountKind.APPLE -> AppleSignInButton(Modifier.fillMaxWidth(), label = stringResource(Res.string.link_with_apple), height = 40.dp, enabled = !model.linking, onClick = onApple)
            AccountKind.GOOGLE -> ProviderButton(stringResource(Res.string.link_with_google), enabled = !model.linking, Modifier.fillMaxWidth(), height = 40.dp, onClick = onGoogle)
            AccountKind.GUEST -> Unit
        }
        Spacer(Modifier.height(8.dp))
    }
    val error = model.link.error
    val message = when {
        error != null -> stringResource(Res.string.sign_in_not_configured, error.provider.label())
        model.link.failed -> stringResource(Res.string.sign_in_failed)
        note -> stringResource(Res.string.link_note)
        else -> null
    }
    if (message != null) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null || model.link.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
                    if (custom.muscleGroups.isEmpty()) stringResource(Res.string.no_muscle_groups_guessed) else custom.muscleGroups.map { it.group.label() }.joinToString(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { open = true }) { Text(stringResource(Res.string.merge_into), color = palette.volt) }
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
