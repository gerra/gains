package app.gains.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import app.gains.ui.components.ScreenTitle
import app.gains.ui.components.SectionHeader
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

data class ConsistencyState(
    val loading: Boolean = true,
    val perDay: Map<LocalDate, Int> = emptyMap(),
    val weeks: List<WeekCount> = emptyList(),
    val stats: ConsistencyStats? = null,
    val streakWeeks: Int = 0,
    val totalSessions: Int = 0,
)

class ConsistencyModel(trainingData: TrainingData = inject()) : ScreenModel() {
    val state: StateFlow<ConsistencyState> = trainingData.snapshot.map { snapshot ->
        withContext(Dispatchers.Default) {
            val today = Dates.today()
            ConsistencyState(
                loading = false,
                perDay = ConsistencyAnalyzer.perDay(snapshot.sessions),
                weeks = ConsistencyAnalyzer.sessionsPerWeek(snapshot.sessions, today),
                stats = InsightEngine().consistencyStats(snapshot.sessions, today),
                streakWeeks = ConsistencyAnalyzer.currentStreakWeeks(snapshot.sessions, today),
                totalSessions = snapshot.sessions.size,
            )
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ConsistencyState())
}

@Composable
fun ConsistencyScreen() {
    val model = rememberScreenModel { ConsistencyModel() }
    val state by model.state.collectAsState()
    if (state.loading) return
    if (state.totalSessions == 0) {
        EmptyState("No sessions", "Your training calendar and sessions-per-week trend appear here after an import.", emoji = "▦")
        return
    }
    val today = Dates.today()
    val palette = GainsColors.palette
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle("Consistency", subtitle = "How often you actually show up")
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
            GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
                CalendarHeatmap(state.perDay, today, weeks = 26)
            }
        }
        item {
            SectionHeader("Sessions per week")
            GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
                if (state.weeks.size < 2) {
                    Text("Only one week of history so far.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val rolling = LineSeries(state.weeks.map { ChartPoint(it.weekStart.x(), it.rollingAverage) }, palette.volt, "4-week average", showDots = false, fill = true)
                    val weekly = LineSeries(state.weeks.map { ChartPoint(it.weekStart.x(), it.sessions.toDouble()) }, palette.muted, "Sessions", showDots = true, dashed = true, smooth = false)
                    LineChart(listOf(rolling, weekly), yMinZero = true, yLabel = { Format.number(it, 0) })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
