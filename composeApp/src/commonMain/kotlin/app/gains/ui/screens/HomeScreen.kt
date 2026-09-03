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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.gains.analysis.ConsistencyAnalyzer
import app.gains.analysis.Dates
import app.gains.analysis.Format
import app.gains.analysis.GoalTuning
import app.gains.analysis.Insight
import app.gains.analysis.InsightEngine
import app.gains.analysis.InsightKind
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
import app.gains.ui.components.Dp16
import app.gains.ui.components.EmptyState
import app.gains.ui.components.GainsCard
import app.gains.ui.components.Pill
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.RoundedIconBox
import app.gains.ui.components.ScreenTitle
import app.gains.ui.components.SecondaryButton
import app.gains.ui.components.SectionHeader
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

data class HomeState(
    val loading: Boolean = true,
    val sessionCount: Int = 0,
    val exerciseCount: Int = 0,
    val lastSession: LocalDate? = null,
    val lastSessionId: String? = null,
    val thisWeekSessions: Int = 0,
    val streakWeeks: Int = 0,
    val insights: List<Insight> = emptyList(),
    val unit: WeightUnit = WeightUnit.KG,
    val profile: GoalProfile? = null,
    val activeProgram: Program? = null,
    val upNext: ProgramDay? = null,
    /** Sessions of the active program this week. */
    val programSessionsThisWeek: Int = 0,
)

class HomeModel(
    trainingData: TrainingData = inject(),
    settings: SettingsRepository = inject(),
    programs: ProgramRepository = inject(),
    sessions: SessionRepository = inject(),
) : ScreenModel() {
    val state: StateFlow<HomeState> = combine(trainingData.snapshot, settings.observeUnit(), programs.observeState(), sessions.observeProgramLinks()) { snapshot, unit, programState, links ->
        Inputs(snapshot, unit, programState, links)
    }
        .mapLatest { (snapshot, unit, programState, links) ->
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
                    thisWeekSessions = snapshot.sessions.count { it.date >= weekStart },
                    streakWeeks = ConsistencyAnalyzer.currentStreakWeeks(snapshot.sessions, today),
                    insights = GoalTuning.rank(InsightEngine(GoalTuning.thresholds(goal), unit).generate(snapshot.sessions, snapshot.exercises, today), goal),
                    unit = unit,
                    profile = programState.profile,
                    activeProgram = active,
                    upNext = active?.let { Rotation.nextDay(it, links) },
                    programSessionsThisWeek = active?.let { Rotation.completedSince(it, links, weekStart) } ?: 0,
                )
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), HomeState())

    private data class Inputs(val snapshot: app.gains.analysis.TrainingSnapshot, val unit: WeightUnit, val programs: app.gains.domain.ProgramState, val links: List<app.gains.domain.ProgramLink>)
}

@Composable
fun HomeScreen(
    onImport: () -> Unit,
    onLog: () -> Unit,
    onOpenExercise: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenVolume: () -> Unit,
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
                    title = "No workouts yet",
                    body = "Log your first session, or bring your history in from Liftoff, Strong, Hevy or any workout CSV.",
                    emoji = "↑",
                    action = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PrimaryButton("Log a workout", onLog)
                            Spacer(Modifier.height(10.dp))
                            SecondaryButton("Import history", onImport)
                        }
                    },
                )
            }
        }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                ScreenTitle(
                    "Progress",
                    subtitle = state.lastSession?.let { "Last session ${Dates.contextual(it, Dates.today())}" },
                    trailing = { TextButton(onClick = onLog) { Text("+ Log", color = palette.volt) } },
                    onSubtitleClick = state.lastSessionId?.let { id -> { onOpenSession(id) } },
                )
                ProgramCard(state, onOpenOnboarding, onOpenPrograms, onOpenProgram, onStartDay)
                HeroCard(state)
            }
            item {
                SectionHeader("What's moving", action = {
                    GoalTuning.headline(state.profile?.goal)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                })
                if (state.insights.isEmpty()) {
                    Text(
                        if (state.sessionCount == 1) "One session imported. Insights need a few weeks of history to compare against."
                        else "Nothing to flag yet. Keep importing and the trends will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.insights) { insight ->
                InsightCard(
                    insight,
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
                        Text("UP NEXT · ${program.name.uppercase()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        Text(day.name, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${Format.plural(day.slots.size, "exercise")} · ${state.programSessionsThisWeek} of ${program.daysPerWeek} this week",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    PrimaryButton("Start", onClick = { onStartDay(ProgramDayRef(program.id, day.id)) })
                }
            }
            state.profile != null -> {
                val profile = state.profile
                Text("PICK A PROGRAM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("${profile.goal.label} · ${profile.experience.label} · ${profile.daysPerWeek} days a week", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("Choose a routine and each day becomes a pre-filled workout with your last weights.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row { Pill("Choose a program ›", palette.volt, filled = true, onClick = onOpenPrograms) }
            }
            else -> {
                Text("SET YOUR GOAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("What are you training for?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("Three quick questions, then a program that fits your week and turns each day into a ready-made workout.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row { Pill("Get started ›", palette.volt, filled = true, onClick = onOpenOnboarding) }
            }
        }
    }
}

@Composable
private fun HeroCard(state: HomeState) {
    val palette = GainsColors.palette
    val regressions = state.insights.count { it.kind == InsightKind.REGRESSION }
    val progress = state.insights.count { it.kind == InsightKind.PROGRESS }
    GainsCard(Modifier.fillMaxWidth(), brush = palette.heroBrush(), contentPadding = Dp16.Loose) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("THIS WEEK", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(state.thisWeekSessions.toString(), style = MaterialTheme.typography.displayLarge, color = palette.volt)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.thisWeekSessions == 1) "session" else "sessions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Pill("${Format.plural(state.streakWeeks, "wk")} streak", palette.volt, filled = true)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            HeroStat("Sessions", state.sessionCount.toString())
            HeroStat("Lifts", state.exerciseCount.toString())
            HeroStat("Up", progress.toString(), palette.progress)
            HeroStat("Down", regressions.toString(), if (regressions > 0) palette.regression else null)
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, color: Color? = null) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = color ?: MaterialTheme.colorScheme.onSurface)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun InsightKind.color(): Color {
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
fun InsightCard(insight: Insight, onClick: () -> Unit, onOpenSession: (String) -> Unit = {}) {
    val color = insight.kind.color()
    val palette = GainsColors.palette
    val today = Dates.today()
    GainsCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            RoundedIconBox(color) { Text(insight.kind.glyph(), style = MaterialTheme.typography.titleLarge, color = color) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(insight.kind.label, color)
                    Spacer(Modifier.weight(1f))
                    insight.delta?.let { DeltaBadge(it) }
                }
                Spacer(Modifier.height(8.dp))
                Text(insight.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(insight.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (insight.sessions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (ref in insight.sessions.distinctBy { it.id }) {
                            Pill("Session ${Dates.contextual(ref.date, today)} ›", palette.volt, onClick = { onOpenSession(ref.id) })
                        }
                    }
                }
            }
        }
    }
}
