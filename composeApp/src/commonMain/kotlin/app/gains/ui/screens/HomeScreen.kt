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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.analysis.Format
import app.gains.analysis.GoalTuning
import app.gains.analysis.Insight
import app.gains.analysis.InsightEngine
import app.gains.analysis.InsightKind
import app.gains.analysis.Streak
import app.gains.analysis.StreakEngine
import app.gains.analysis.TrainingData
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.domain.GoalProfile
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.ProgramDayRef
import app.gains.domain.WeightUnit
import app.gains.program.Rotation
import app.gains.ui.ScreenModel
import app.gains.ui.components.DeltaBadge
import app.gains.ui.components.EmptyState
import app.gains.ui.components.GainsCard
import app.gains.ui.components.Pill
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.RoundedIconBox
import app.gains.ui.components.ScreenTitle
import app.gains.ui.components.SecondaryButton
import app.gains.ui.components.SectionHeader
import app.gains.ui.components.StreakCard
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.i18n.*
import org.jetbrains.compose.resources.stringResource
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

internal data class HomeState(
    val loading: Boolean = true,
    val sessionCount: Int = 0,
    val exerciseCount: Int = 0,
    val lastSession: LocalDate? = null,
    val lastSessionId: String? = null,
    val streak: Streak = Streak(),
    /** False while the lifter has never said whether the streak reminder may be sent. */
    val reminderAnswered: Boolean = true,
    val insights: List<Insight> = emptyList(),
    val unit: WeightUnit = WeightUnit.KG,
    val profile: GoalProfile? = null,
    val activeProgram: Program? = null,
    val upNext: ProgramDay? = null,
    /** Sessions of the active program this week. */
    val programSessionsThisWeek: Int = 0,
)

internal class HomeModel(
    trainingData: TrainingData = inject(),
    private val settings: SettingsRepository = inject(),
    programs: ProgramRepository = inject(),
    sessions: SessionRepository = inject(),
) : ScreenModel() {
    val state: StateFlow<HomeState> = combine(
        trainingData.snapshot,
        settings.observeUnit(),
        programs.observeState(),
        sessions.observeProgramLinks(),
        settings.observeStreakReminder(),
    ) { snapshot, unit, programState, links, reminder ->
        Inputs(snapshot, unit, programState, links, reminder)
    }
        .mapLatest { (snapshot, unit, programState, links, reminder) ->
            withContext(Dispatchers.Default) {
                val today = Dates.today()
                val weekStart = Dates.weekStart(today)
                val goal = programState.profile?.goal
                val active = programState.active
                HomeState(
                    loading = false,
                    sessionCount = snapshot.sessions.size,
                    exerciseCount = snapshot.trainedExercises.size,
                    lastSession = snapshot.sessions.maxOfOrNull { it.date },
                    lastSessionId = snapshot.sessions.maxByOrNull { it.timestamp }?.id,
                    streak = StreakEngine.compute(snapshot.sessions, today, programState.weeklyGoal),
                    reminderAnswered = reminder != null,
                    insights = GoalTuning.rank(InsightEngine(GoalTuning.thresholds(goal)).generate(snapshot.sessions, snapshot.exercises, today), goal),
                    unit = unit,
                    profile = programState.profile,
                    activeProgram = active,
                    upNext = active?.let { Rotation.nextDay(it, links) },
                    programSessionsThisWeek = active?.let { Rotation.completedSince(it, links, weekStart) } ?: 0,
                )
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), HomeState())

    /** The offer on the card taken: from here on the reminder may be sent. */
    fun enableReminder() { scope.launch { settings.setStreakReminder(true) } }

    private data class Inputs(
        val snapshot: app.gains.analysis.TrainingSnapshot,
        val unit: WeightUnit,
        val programs: app.gains.domain.ProgramState,
        val links: List<app.gains.domain.ProgramLink>,
        val reminder: Boolean?,
    )
}

@Composable
internal fun HomeScreen(
    onImport: () -> Unit,
    onLog: () -> Unit,
    onOpenExercise: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenVolume: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenOnboarding: () -> Unit = {},
    onOpenPrograms: () -> Unit = {},
    onOpenProgram: (String) -> Unit = {},
    onStartDay: (ProgramDayRef) -> Unit = {},
) {
    val model = rememberScreenModel { HomeModel() }
    val state by model.state.collectAsState()
    val palette = GainsColors.palette

    when {
        state.loading -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator(color = palette.volt)
        }
        state.sessionCount == 0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                ProgramCard(state, onOpenOnboarding, onOpenPrograms, onOpenProgram, onStartDay)
                EmptyState(
                    title = stringResource(Res.string.no_workouts_yet),
                    body = stringResource(Res.string.no_workouts_yet_body),
                    emoji = "↑",
                    action = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PrimaryButton(stringResource(Res.string.log_a_workout), onLog)
                            Spacer(Modifier.height(10.dp))
                            SecondaryButton(stringResource(Res.string.import_history), onImport)
                        }
                    },
                )
            }
        }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                ScreenTitle(
                    stringResource(Res.string.home_title),
                    subtitle = state.lastSession?.let { stringResource(Res.string.last_session, dateContextual(it, Dates.today())) },
                    trailing = { TextButton(onClick = onLog) { Text(stringResource(Res.string.plus_log), color = palette.volt) } },
                    onSubtitleClick = state.lastSessionId?.let { id -> { onOpenSession(id) } },
                )
                ProgramCard(state, onOpenOnboarding, onOpenPrograms, onOpenProgram, onStartDay)
                StreakCard(
                    state.streak,
                    Modifier.fillMaxWidth(),
                    onClick = onOpenHistory,
                    // The offer is made once there is a run worth protecting, and never again after an answer.
                    onRemindMe = if (!state.reminderAnswered && state.streak.weeks >= REMIND_FROM_WEEKS) model::enableReminder else null,
                ) {
                    HeroStats(state)
                }
            }
            item {
                SectionHeader(stringResource(Res.string.whats_moving), action = {
                    goalHeadline(state.profile?.goal)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                })
                if (state.insights.isEmpty()) {
                    Text(
                        if (state.sessionCount == 1) stringResource(Res.string.one_session_imported) else stringResource(Res.string.nothing_to_flag),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.insights) { insight ->
                InsightCard(
                    insight,
                    state.unit,
                    onClick = {
                        when {
                            insight.exerciseId != null -> onOpenExercise(insight.exerciseId!!)
                            insight.muscleGroup != null -> onOpenVolume()
                        }
                    },
                    onOpenSession = onOpenSession,
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/**
 * The daily entry point above everything else: what to do today. Three states, in order of
 * how far the user has got: no goal yet, goal but no program, program active with the next day.
 */
@Composable
private fun ProgramCard(
    state: HomeState,
    onOpenOnboarding: () -> Unit,
    onOpenPrograms: () -> Unit,
    onOpenProgram: (String) -> Unit,
    onStartDay: (ProgramDayRef) -> Unit,
) {
    val palette = GainsColors.palette
    val program = state.activeProgram
    val day = state.upNext
    GainsCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), onClick = when {
        program != null -> ({ onOpenProgram(program.id) })
        state.profile != null -> onOpenPrograms
        else -> onOpenOnboarding
    }) {
        when {
            program != null && day != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.up_next, program.displayName().uppercase()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        Text(day.displayName(), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(Res.string.exercises_and_week_count, exercisesText(day.slots.size), state.programSessionsThisWeek, program.daysPerWeek),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    PrimaryButton(stringResource(Res.string.start), onClick = { onStartDay(ProgramDayRef(program.id, day.id)) })
                }
                Spacer(Modifier.height(10.dp))
                // The whole rotation with today's day picked out, and a visible way into the full program.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val order = buildAnnotatedString {
                        program.days.forEachIndexed { i, d ->
                            if (i > 0) append(" · ")
                            val name = d.displayName()
                            if (d.id == day.id) withStyle(SpanStyle(color = palette.volt, fontWeight = FontWeight.SemiBold)) { append(name) } else append(name)
                        }
                    }
                    Text(order, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(Res.string.whole_program), style = MaterialTheme.typography.labelSmall, color = palette.volt)
                }
            }
            state.profile != null -> {
                val profile = state.profile
                Text(stringResource(Res.string.pick_a_program), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(Res.string.profile_summary, profile.goal.label(), profile.experience.label(), daysAWeekText(profile.daysPerWeek)), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(Res.string.choose_routine_blurb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row { Pill(stringResource(Res.string.choose_a_program), palette.volt, filled = true, onClick = onOpenPrograms) }
            }
            else -> {
                Text(stringResource(Res.string.set_your_goal), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(Res.string.what_are_you_training_for), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(Res.string.three_quick_questions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row { Pill(stringResource(Res.string.get_started), palette.volt, filled = true, onClick = onOpenOnboarding) }
            }
        }
    }
}

/** The four numbers under the streak: how much is on record, and what is moving which way. */
@Composable
private fun HeroStats(state: HomeState) {
    val palette = GainsColors.palette
    val regressions = state.insights.count { it.kind == InsightKind.REGRESSION }
    val progress = state.insights.count { it.kind == InsightKind.PROGRESS }
    Spacer(Modifier.height(18.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        HeroStat(stringResource(Res.string.hero_sessions), state.sessionCount.toString())
        HeroStat(stringResource(Res.string.hero_lifts), state.exerciseCount.toString())
        HeroStat(stringResource(Res.string.hero_up), progress.toString(), palette.progress)
        HeroStat(stringResource(Res.string.hero_down), regressions.toString(), if (regressions > 0) palette.regression else null)
    }
}

/** Weeks of streak before the reminder is worth offering: below that there is nothing to protect. */
private const val REMIND_FROM_WEEKS = 2

@Composable
private fun HeroStat(label: String, value: String, color: Color? = null) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = color ?: MaterialTheme.colorScheme.onSurface)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun InsightKind.color(): Color {
    val p = GainsColors.palette
    return when (this) {
        InsightKind.REGRESSION -> p.regression
        InsightKind.STALL -> p.stall
        InsightKind.NEGLECT -> p.neglect
        InsightKind.CONSISTENCY -> p.consistency
        InsightKind.PROGRESS -> p.progress
    }
}

private fun InsightKind.glyph(): String = when (this) {
    InsightKind.REGRESSION -> "↓"
    InsightKind.STALL -> "→"
    InsightKind.NEGLECT -> "⏸"
    InsightKind.CONSISTENCY -> "◷"
    InsightKind.PROGRESS -> "↑"
}

/** Tapping the card opens the exercise (or volume); each session the text mentions gets its own link below it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InsightCard(insight: Insight, unit: WeightUnit, onClick: () -> Unit, onOpenSession: (String) -> Unit = {}) {
    val color = insight.kind.color()
    val palette = GainsColors.palette
    val today = Dates.today()
    GainsCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            RoundedIconBox(color) { Text(insight.kind.glyph(), style = MaterialTheme.typography.titleLarge, color = color) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(insight.kind.label(), color)
                    Spacer(Modifier.weight(1f))
                    insight.delta?.let { DeltaBadge(it) }
                }
                Spacer(Modifier.height(8.dp))
                Text(insightTitle(insight), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(insightDetail(insight, unit, today), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (insight.sessions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (ref in insight.sessions.distinctBy { it.id }) {
                            Pill(stringResource(Res.string.session_link, dateContextual(ref.date, today)), palette.volt, onClick = { onOpenSession(ref.id) })
                        }
                    }
                }
            }
        }
    }
}
