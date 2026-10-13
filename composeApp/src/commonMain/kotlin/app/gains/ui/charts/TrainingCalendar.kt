package app.gains.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.analysis.Dates.plusDays
import app.gains.analysis.VolumeAnalyzer
import app.gains.domain.Exercise
import app.gains.domain.MuscleGroup
import app.gains.domain.Session
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.i18n.dayShort
import app.gains.ui.i18n.heatmapDayText
import app.gains.ui.theme.GainsColors
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.YearMonth
import org.jetbrains.compose.resources.stringResource

/** What the calendar shows for one day: how many sessions it had and the muscles they trained between them. */
internal data class CalendarDay(val sessions: Int, val muscles: Map<MuscleGroup, Double>)

/** Every day with a session, its sessions counted and their working sets per muscle group added up. */
internal fun calendarDays(sessions: List<Session>, exercisesById: Map<String, Exercise>): Map<LocalDate, CalendarDay> =
    sessions.groupBy { it.date }.mapValues { (_, onDay) ->
        val muscles = HashMap<MuscleGroup, Double>()
        for (session in onDay) {
            for ((group, sets) in VolumeAnalyzer.sessionSets(session, exercisesById)) muscles[group] = (muscles[group] ?: 0.0) + sets
        }
        CalendarDay(onDay.size, muscles)
    }

/**
 * The most recent [weeks] weeks as calendar rows, Monday first, this week at the bottom: each
 * trained day shows the muscles it worked on a small body, today is ringed, and a week a rest week
 * carried the streak through is tinted, with a line under the grid saying so.
 */
@Composable
internal fun RecentWeeksCalendar(
    days: Map<LocalDate, CalendarDay>,
    today: LocalDate,
    modifier: Modifier = Modifier,
    weeks: Int = 2,
    restWeeks: Set<LocalDate> = emptySet(),
    onDayClick: ((LocalDate) -> Unit)? = null,
) {
    val thisWeek = Dates.weekStart(today)
    val shown = (weeks - 1 downTo 0).map { thisWeek.plusDays(-7 * it) }
    Column(modifier.fillMaxWidth()) {
        WeekdayHeader()
        for (week in shown) {
            CalendarWeek(week, days, today, rested = week in restWeeks, onDayClick = onDayClick)
        }
        if (shown.any { it in restWeeks }) RestWeekLegend()
    }
}

/** One month as a calendar, Monday first, the days of the neighbouring months left blank. */
@Composable
internal fun MonthCalendar(
    month: YearMonth,
    days: Map<LocalDate, CalendarDay>,
    today: LocalDate,
    modifier: Modifier = Modifier,
    restWeeks: Set<LocalDate> = emptySet(),
    onDayClick: ((LocalDate) -> Unit)? = null,
) {
    val weeks = Dates.weeksBetween(month.firstDay, month.lastDay)
    Column(modifier.fillMaxWidth()) {
        WeekdayHeader()
        for (week in weeks) {
            CalendarWeek(week, days, today, rested = week in restWeeks, onDayClick = onDayClick, month = month.month)
        }
        if (weeks.any { it in restWeeks }) RestWeekLegend()
    }
}

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        for (day in DayOfWeek.entries) {
            Text(
                dayShort(day), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1,
            )
        }
    }
}

/** Seven days from [weekStart]; with [month] set, only that month's days are drawn. */
@Composable
private fun CalendarWeek(
    weekStart: LocalDate,
    days: Map<LocalDate, CalendarDay>,
    today: LocalDate,
    rested: Boolean,
    onDayClick: ((LocalDate) -> Unit)?,
    month: Month? = null,
) {
    Row(Modifier.fillMaxWidth()) {
        for (d in 0 until 7) {
            val date = weekStart.plusDays(d)
            if (month != null && date.month != month) Spacer(Modifier.weight(1f).cell())
            else DayCell(date, days[date], today, rested, onDayClick)
        }
    }
}

private fun Modifier.cell(): Modifier = this.aspectRatio(0.8f).padding(2.5.dp)

@Composable
private fun RowScope.DayCell(date: LocalDate, day: CalendarDay?, today: LocalDate, rested: Boolean, onDayClick: ((LocalDate) -> Unit)?) {
    val palette = GainsColors.palette
    val shape = RoundedCornerShape(10.dp)
    val sessions = day?.sessions ?: 0
    val isToday = date == today
    val background = when {
        rested -> palette.cyan.copy(alpha = 0.18f)
        // A trained day is cut out of the card, darker, with a light rim: the figures carry it.
        sessions > 0 -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val description = heatmapDayText(date, sessions, rested)
    Box(
        Modifier.weight(1f).cell()
            .clip(shape)
            .background(background)
            .then(
                when {
                    isToday -> Modifier.border(1.5.dp, palette.volt, shape)
                    sessions > 0 -> Modifier.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), shape)
                    else -> Modifier
                },
            )
            .then(if (onDayClick != null && sessions > 0) Modifier.clickable { onDayClick(date) } else Modifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val number = date.day.toString()
        val numberColor = when {
            isToday -> palette.volt
            date > today -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            sessions > 0 -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        // As in Liftoff, a trained day's figures fill the cell and its number, the same size as any
        // other day's, sits over them in the middle, a soft shadow lifting it off the light bodies.
        if (day != null && sessions > 0) {
            MiniBodyMap(day.muscles, Modifier.fillMaxWidth().padding(horizontal = 3.dp), gap = CALENDAR_FIGURE_GAP)
        }
        Text(
            number, maxLines = 1, color = numberColor,
            style = if (sessions > 0) MaterialTheme.typography.bodyLarge.copy(shadow = Shadow(MaterialTheme.colorScheme.surface, blurRadius = 8f))
            else MaterialTheme.typography.bodyLarge,
        )
    }
}

/** Figure units between a day's front and back figures: the number overlaps both, as in Liftoff. */
private const val CALENDAR_FIGURE_GAP = 180f

/** What the tinted weeks mean. */
@Composable
private fun RestWeekLegend() {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(GainsColors.palette.cyan.copy(alpha = 0.45f)))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(Res.string.calendar_rest_week_legend), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
