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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import app.gains.ui.components.PickerSheet
import app.gains.ui.components.Pill
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.ScreenTitle
import app.gains.ui.components.SectionHeader
import app.gains.i18n.Strings
import app.gains.ui.i18n.strings
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
import kotlinx.datetime.Month

/** One calendar month of sessions, newest first. */
internal data class MonthGroup(val year: Int, val month: Month, val sessions: List<Session>)

/** One calendar year of sessions, split into months, newest first. */
internal data class YearGroup(val year: Int, val months: List<MonthGroup>) {
    val sessionCount: Int get() = months.sumOf { it.sessions.size }
}

/** Groups [sessions] (newest first) by year and month, preserving their order inside each month. */
internal fun groupByYearAndMonth(sessions: List<Session>): List<YearGroup> =
    sessions.groupBy { it.date.year }.entries.sortedByDescending { it.key }.map { (year, inYear) ->
        YearGroup(
            year,
            inYear.groupBy { it.date.month }.entries.sortedByDescending { it.key }.map { (month, inMonth) -> MonthGroup(year, month, inMonth) },
        )
    }

internal data class HistoryState(
    val loading: Boolean = true,
    val sessions: List<Session> = emptyList(),
    /** [sessions] grouped by year, then month, newest first. */
    val years: List<YearGroup> = emptyList(),
    val exercisesById: Map<String, Exercise> = emptyMap(),
    val perDay: Map<LocalDate, Int> = emptyMap(),
    /** Every day's sessions, newest first, for opening a day from the calendar. */
    val sessionsByDay: Map<LocalDate, List<Session>> = emptyMap(),
    val weeks: List<WeekCount> = emptyList(),
    val stats: ConsistencyStats? = null,
    val streakWeeks: Int = 0,
    /** program day id -> day name, for the badge on sessions started from a program. */
    val dayNames: Map<String, String> = emptyMap(),
)

internal class HistoryModel(strings: Strings, trainingData: TrainingData = inject(), programs: ProgramRepository = inject()) : ScreenModel() {
    val state: StateFlow<HistoryState> = combine(trainingData.snapshot, programs.observePrograms()) { snapshot, programList ->
        withContext(Dispatchers.Default) {
            val today = Dates.today()
            val sessions = snapshot.sessions.sortedByDescending { it.timestamp }
            HistoryState(
                loading = false,
                dayNames = programList.flatMap { p -> p.days.map { it.id to strings.programDayName(it) } }.toMap(),
                sessions = sessions,
                years = groupByYearAndMonth(sessions),
                exercisesById = snapshot.exercisesById,
                perDay = ConsistencyAnalyzer.perDay(snapshot.sessions),
                sessionsByDay = sessions.groupBy { it.date },
                weeks = ConsistencyAnalyzer.sessionsPerWeek(snapshot.sessions, today),
                stats = InsightEngine().consistencyStats(snapshot.sessions, today),
                streakWeeks = ConsistencyAnalyzer.currentStreakWeeks(snapshot.sessions, today),
            )
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), HistoryState())
}

/**
 * Every session, newest first, with the consistency picture on top. Tap a session, or its day on
 * the calendar, to edit it; plus to log.
 */
@Composable
internal fun HistoryScreen(onOpen: (String) -> Unit, onLog: () -> Unit) {
    val strings = strings
    val model = rememberScreenModel(strings) { HistoryModel(strings) }
    val state by model.state.collectAsState()
    if (state.loading) return
    val today = Dates.today()
    val palette = GainsColors.palette
    // A calendar day with more than one session: which of them to open is asked in a sheet.
    var pickedDay by remember { mutableStateOf<LocalDate?>(null) }
    if (state.sessions.isEmpty()) {
        EmptyState(strings.noSessionsYet, strings.noSessionsYetBody, emoji = "▦", action = { PrimaryButton(strings.logAWorkout, onLog) })
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle(strings.historyTitle, subtitle = strings.onRecord(state.sessions.size), trailing = {
                TextButton(onClick = onLog) { Text(strings.plusLog, color = palette.volt) }
            })
            val stats = state.stats
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(strings.perWeek, stats?.let { Format.number(it.recentSessionsPerWeek, 1) } ?: "-", Modifier.weight(1f), caption = strings.lastNWeeks(stats?.weeks ?: 4), accent = palette.volt)
                MetricTile(
                    strings.trend,
                    when (stats?.trend) { Trend.UP -> strings.trendUp; Trend.DOWN -> strings.trendDown; else -> strings.trendSteady },
                    Modifier.weight(1f),
                    caption = stats?.previousSessionsPerWeek?.let { strings.wasPerWeek(Format.number(it, 1)) },
                    accent = when (stats?.trend) { Trend.UP -> palette.progress; Trend.DOWN -> palette.regression; else -> null },
                )
                MetricTile(strings.streak, state.streakWeeks.toString(), Modifier.weight(1f), caption = strings.weekWord(state.streakWeeks))
            }
        }
        item {
            SectionHeader(strings.last26Weeks) {
                Text(strings.tapADayToOpen, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
                CalendarHeatmap(state.perDay, today, weeks = 26, onDayClick = { day ->
                    val onDay = state.sessionsByDay[day].orEmpty()
                    when (onDay.size) {
                        0 -> {}
                        1 -> onOpen(onDay.single().id)
                        else -> { pickedDay = day }
                    }
                })
            }
        }
        if (state.weeks.size >= 2) {
            item {
                SectionHeader(strings.sessionsPerWeek)
                GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
                    val rolling = LineSeries(state.weeks.map { ChartPoint(it.weekStart.x(), it.rollingAverage) }, palette.volt, strings.fourWeekAverage, showDots = false, fill = true)
                    val weekly = LineSeries(state.weeks.map { ChartPoint(it.weekStart.x(), it.sessions.toDouble()) }, palette.muted, strings.sessionsLegend, showDots = true, dashed = true, smooth = false)
                    LineChart(listOf(rolling, weekly), height = 160.dp, yMinZero = true, yLabel = { Format.number(it, 0) })
                }
            }
        }
        for (year in state.years) {
            item(key = "year-${year.year}", contentType = "year") { YearDivider(year) }
            for (month in year.months) {
                item(key = "month-${year.year}-${month.month.ordinal}", contentType = "month") { MonthHeader(month) }
                items(month.sessions, key = { it.id }, contentType = { "session" }) { session ->
                    SessionRow(session, state.exercisesById, today, state.dayNames[session.program?.dayId], onClick = { onOpen(session.id) })
                }
            }
        }
    }
    pickedDay?.let { day ->
        DaySessionsSheet(
            day, state.sessionsByDay[day].orEmpty(), state.exercisesById, state.dayNames, today,
            onOpen = { pickedDay = null; onOpen(it) },
            onDismiss = { pickedDay = null },
        )
    }
}

/** The sessions of one calendar day, newest first, each a tap away from its editor. */
@Composable
private fun DaySessionsSheet(
    day: LocalDate,
    sessions: List<Session>,
    exercisesById: Map<String, Exercise>,
    dayNames: Map<String, String>,
    today: LocalDate,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = strings
    PickerSheet(strings.dateWithWeekday(day, today), subtitle = strings.sessions(sessions.size), onDismiss = onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            for (session in sessions) {
                SessionRow(session, exercisesById, today, dayNames[session.program?.dayId], onClick = { onOpen(session.id) })
            }
        }
    }
}

/** Full-width rule with the year in the middle and that year's session count beneath it. */
@Composable
private fun YearDivider(year: YearGroup) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = hairline)
        Column(Modifier.padding(horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(year.year.toString(), style = MaterialTheme.typography.titleLarge)
            Text(strings.sessions(year.sessionCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.weight(1f), color = hairline)
    }
}

/** "SEPTEMBER" on the left, "8 sessions" on the right. */
@Composable
private fun MonthHeader(month: MonthGroup) {
    val strings = strings
    SectionHeader(strings.monthName(month.sessions.first().date)) {
        Text(strings.sessions(month.sessions.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SessionRow(session: Session, exercisesById: Map<String, Exercise>, today: LocalDate, dayName: String?, onClick: () -> Unit) {
    val palette = GainsColors.palette
    val strings = strings
    GainsCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = onClick, contentPadding = Dp16.Tight) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.dateContextual(session.date, today), style = MaterialTheme.typography.titleMedium)
                    if (dayName != null) {
                        Spacer(Modifier.width(8.dp))
                        Pill(dayName, palette.cyan)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${session.timestamp.hour.toString().padStart(2, '0')}:${session.timestamp.minute.toString().padStart(2, '0')}" +
                            (session.durationMinutes?.let { strings.durationSuffix(it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    session.exercises.joinToString(" · ") { exercisesById[it.exerciseId]?.let(strings::exerciseName) ?: it.exerciseId },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(strings.sets(session.workingSetCount), style = MaterialTheme.typography.titleSmall)
                Pill(if (session.isManual) strings.logged else session.source.replaceFirstChar { it.uppercase() }, if (session.isManual) palette.volt else palette.muted)
            }
        }
    }
}
