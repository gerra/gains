package app.gains.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.charts.MonthCalendar
import app.gains.ui.components.Dp16
import app.gains.ui.components.GainsCard
import app.gains.ui.components.ScreenTitle
import app.gains.ui.i18n.*
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.yearMonth
import org.jetbrains.compose.resources.stringResource

/** The months from [first]'s to [last]'s, newest first. */
internal fun monthsBetween(first: LocalDate, last: LocalDate): List<YearMonth> {
    val result = ArrayList<YearMonth>()
    var month = last.yearMonth
    while (month >= first.yearMonth) {
        result += month
        month = month.minusMonth()
    }
    return result
}

/**
 * The whole history as a calendar, one month to a card, in date order down the page from the month
 * of the first session to this one. It opens at the bottom, on this month, and scrolls up into the
 * past. Each trained day shows the muscles it worked; tap one to open it.
 */
@Composable
internal fun CalendarScreen(onOpen: (String) -> Unit) {
    val texts = rememberTexts()
    val model = rememberScreenModel { HistoryModel(texts, inject(), inject(), inject()) }
    val state by model.state.collectAsState()
    if (state.loading) return
    val today = Dates.today()
    var pickedDay by remember { mutableStateOf<LocalDate?>(null) }
    val first = state.sessions.minOfOrNull { it.date } ?: today
    val months = remember(first, today) { monthsBetween(minOf(first, today), today) }
    val perMonth = remember(state.sessions) { state.sessions.groupingBy { it.date.yearMonth }.eachCount() }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(Res.string.calendar_title), modifier = Modifier.padding(horizontal = 20.dp), subtitle = stringResource(Res.string.on_record, sessionsText(state.sessions.size)))
        // Reversed, with the newest month first in the list: it sits at the bottom and is where the list opens.
        LazyColumn(Modifier.weight(1f), reverseLayout = true, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            items(months, key = { "${it.year}-${it.month.ordinal}" }) { month ->
                val inMonth = perMonth[month] ?: 0
                GainsCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentPadding = Dp16.Tight) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${monthName(month.firstDay)} ${month.year}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (inMonth > 0) Text(sessionsText(inMonth), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MonthCalendar(month, state.calendar, today, restWeeks = state.streak.restWeeksUsed, onDayClick = { day ->
                        openDay(state.sessionsByDay[day].orEmpty(), onOpen) { pickedDay = day }
                    })
                }
            }
        }
    }
    pickedDay?.let { day ->
        DaySessionsSheet(
            day, state.sessionsByDay[day].orEmpty(), state.exercisesById, state.dayNames, today,
            onOpen = { pickedDay = null; onOpen(it) },
            onDismiss = { pickedDay = null },
            recordCounts = state.recordCounts,
        )
    }
}
