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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gains.analysis.ConsistencyAnalyzer
import app.gains.analysis.ConsistencyStats
import app.gains.analysis.Dates
import app.gains.analysis.Format
import app.gains.analysis.InsightEngine
import app.gains.analysis.TrainingData
import app.gains.analysis.Trend
import app.gains.analysis.WeekCount
import app.gains.data.ProgramRepository
import app.gains.domain.Exercise
import app.gains.domain.Session
import app.gains.ui.ScreenModel
import app.gains.ui.charts.CalendarHeatmap
import app.gains.ui.charts.ChartMath.x
import app.gains.ui.charts.ChartPoint
import app.gains.ui.charts.LineChart
import app.gains.ui.charts.LineSeries
import app.gains.ui.components.Dp16
import app.gains.ui.components.EmptyState
import app.gains.ui.components.GainsCard
import app.gains.ui.components.MetricTile
import app.gains.ui.components.Pill
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.ScreenTitle
import app.gains.ui.components.SectionHeader
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

data class HistoryState(
    val loading: Boolean = true,
    val sessions: List<Session> = emptyList(),
    val exercisesById: Map<String, Exercise> = emptyMap(),
    val perDay: Map<LocalDate, Int> = emptyMap(),
    val weeks: List<WeekCount> = emptyList(),
    val stats: ConsistencyStats? = null,
    val streakWeeks: Int = 0,
    /** program day id -> day name, for the badge on sessions started from a program. */
    val dayNames: Map<String, String> = emptyMap(),
)

class HistoryModel(trainingData: TrainingData = inject(), programs: ProgramRepository = inject()) : ScreenModel() {
    val state: StateFlow<HistoryState> = combine(trainingData.snapshot, programs.observePrograms()) { snapshot, programList ->
        withContext(Dispatchers.Default) {
            val today = Dates.today()
            HistoryState(
                loading = false,
                dayNames = programList.flatMap { p -> p.days.map { it.id to it.name } }.toMap(),
                sessions = snapshot.sessions.sortedByDescending { it.timestamp },
                exercisesById = snapshot.exercisesById,
                perDay = ConsistencyAnalyzer.perDay(snapshot.sessions),
                weeks = ConsistencyAnalyzer.sessionsPerWeek(snapshot.sessions, today),
                stats = InsightEngine().consistencyStats(snapshot.sessions, today),
                streakWeeks = ConsistencyAnalyzer.currentStreakWeeks(snapshot.sessions, today),
            )
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), HistoryState())
}

/** Every session, newest first, with the consistency picture on top. Tap to edit, plus to log. */
@Composable
fun HistoryScreen(onOpen: (String) -> Unit, onLog: () -> Unit) {
    val model = rememberScreenModel { HistoryModel() }
    val state by model.state.collectAsState()
    if (state.loading) return
    val today = Dates.today()
    val palette = GainsColors.palette
    if (state.sessions.isEmpty()) {
        EmptyState("No sessions yet", "Log a workout here or import your history. Your calendar and sessions-per-week trend will build up as you go.", emoji = "▦", action = { PrimaryButton("Log a workout", onLog) })
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle("History", subtitle = "${Format.plural(state.sessions.size, "session")} on record", trailing = {
                TextButton(onClick = onLog) { Text("+ Log", color = palette.volt) }
            })
            val stats = state.stats
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Per week", stats?.let { Format.number(it.recentSessionsPerWeek, 1) } ?: "-", Modifier.weight(1f), caption = "last ${stats?.weeks ?: 4} weeks", accent = palette.volt)
                MetricTile(
                    "Trend",
                    when (stats?.trend) { Trend.UP -> "Up"; Trend.DOWN -> "Down"; else -> "Steady" },
                    Modifier.weight(1f),
                    caption = stats?.previousSessionsPerWeek?.let { "was ${Format.number(it, 1)}/wk" },
                    accent = when (stats?.trend) { Trend.UP -> palette.progress; Trend.DOWN -> palette.regression; else -> null },
                )
                MetricTile("Streak", state.streakWeeks.toString(), Modifier.weight(1f), caption = if (state.streakWeeks == 1) "week" else "weeks")
            }
        }
        item {
            SectionHeader("Last 26 weeks")
            GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) { CalendarHeatmap(state.perDay, today, weeks = 26) }
        }
        if (state.weeks.size >= 2) {
            item {
                SectionHeader("Sessions per week")
                GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
                    val rolling = LineSeries(state.weeks.map { ChartPoint(it.weekStart.x(), it.rollingAverage) }, palette.volt, "4-week average", showDots = false, fill = true)
                    val weekly = LineSeries(state.weeks.map { ChartPoint(it.weekStart.x(), it.sessions.toDouble()) }, palette.muted, "Sessions", showDots = true, dashed = true, smooth = false)
                    LineChart(listOf(rolling, weekly), height = 160.dp, yMinZero = true, yLabel = { Format.number(it, 0) })
                }
            }
        }
        item { SectionHeader("Sessions") }
        items(state.sessions, key = { it.id }) { session ->
            SessionRow(session, state.exercisesById, today, state.dayNames[session.program?.dayId], onClick = { onOpen(session.id) })
        }
    }
}

@Composable
private fun SessionRow(session: Session, exercisesById: Map<String, Exercise>, today: LocalDate, dayName: String?, onClick: () -> Unit) {
    val palette = GainsColors.palette
    GainsCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = onClick, contentPadding = Dp16.Tight) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Dates.contextual(session.date, today), style = MaterialTheme.typography.titleMedium)
                    if (dayName != null) {
                        Spacer(Modifier.width(8.dp))
                        Pill(dayName, palette.cyan)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${session.timestamp.hour.toString().padStart(2, '0')}:${session.timestamp.minute.toString().padStart(2, '0')}" +
                            (session.durationMinutes?.let { " · $it min" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    session.exercises.joinToString(" · ") { exercisesById[it.exerciseId]?.name ?: it.exerciseId },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.plural(session.setCount, "set"), style = MaterialTheme.typography.titleSmall)
                Pill(if (session.isManual) "logged" else session.source.replaceFirstChar { it.uppercase() }, if (session.isManual) palette.volt else palette.muted)
            }
        }
    }
}
