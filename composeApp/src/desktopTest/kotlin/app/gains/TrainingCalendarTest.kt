package app.gains

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.gains.domain.MuscleGroup
import app.gains.ui.charts.CalendarDay
import app.gains.ui.charts.MonthCalendar
import app.gains.ui.charts.RecentWeeksCalendar
import app.gains.ui.i18n.heatmapDayText
import app.gains.ui.screens.monthsBetween
import app.gains.ui.theme.GainsTheme
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals

/** Days on the history calendar open when tapped, and the ones that cannot be opened are not buttons. */
@OptIn(ExperimentalTestApi::class)
class TrainingCalendarTest {
    // A Saturday: the two weeks shown run from Monday 7 Sep to Sunday 20 Sep.
    private val today = LocalDate(2026, 9, 19)
    private fun daysAgo(n: Int) = today.minus(n, DateTimeUnit.DAY)
    private fun trained(sessions: Int = 1) = CalendarDay(sessions, mapOf(MuscleGroup.CHEST to 3.0))

    /** The days' content descriptions, read in the composition, where the resources know their language. */
    private class Labels {
        private val texts = mutableMapOf<Triple<LocalDate, Int, Boolean>, String>()
        @Composable
        fun read(date: LocalDate, sessions: Int, rested: Boolean = false) { texts[Triple(date, sessions, rested)] = heatmapDayText(date, sessions, rested) }
        operator fun get(date: LocalDate, sessions: Int, rested: Boolean = false): String = texts.getValue(Triple(date, sessions, rested))
    }

    @Test
    fun tappingADayWithASessionReportsThatDay() = runDesktopComposeUiTest(400, 800) {
        val opened = mutableListOf<LocalDate>()
        val label = Labels()
        setContent {
            label.read(daysAgo(3), 1); label.read(daysAgo(8), 2)
            GainsTheme { RecentWeeksCalendar(mapOf(daysAgo(3) to trained(), daysAgo(8) to trained(2)), today, onDayClick = { opened += it }) }
        }
        onNodeWithContentDescription(label[daysAgo(3), 1]).performClick()
        onNodeWithContentDescription(label[daysAgo(8), 2]).performClick()
        waitForIdle()
        assertEquals(listOf(daysAgo(3), daysAgo(8)), opened)
    }

    @Test
    fun emptyDaysTodayAndTheFutureCannotBeOpened() = runDesktopComposeUiTest(400, 800) {
        val label = Labels()
        val tomorrow = today.plus(1, DateTimeUnit.DAY)
        setContent {
            label.read(daysAgo(3), 1); label.read(daysAgo(2), 0); label.read(today, 0); label.read(tomorrow, 0)
            GainsTheme { RecentWeeksCalendar(mapOf(daysAgo(3) to trained()), today, onDayClick = {}) }
        }
        onNodeWithContentDescription(label[daysAgo(3), 1]).assertHasClickAction()
        onNodeWithContentDescription(label[daysAgo(2), 0]).assertHasNoClickAction()
        onNodeWithContentDescription(label[today, 0]).assertHasNoClickAction()
        // The rest of this week is on the calendar, but nothing can have happened there yet.
        onNodeWithContentDescription(label[tomorrow, 0]).assertHasNoClickAction()
    }

    @Test
    fun withoutAHandlerNothingIsAButton() = runDesktopComposeUiTest(400, 800) {
        val label = Labels()
        setContent {
            label.read(daysAgo(3), 1)
            GainsTheme { RecentWeeksCalendar(mapOf(daysAgo(3) to trained()), today) }
        }
        onNodeWithContentDescription(label[daysAgo(3), 1]).assertHasNoClickAction()
    }

    @Test
    fun aRestWeekIsNamedOnItsDaysAndExplainedUnderTheGrid() = runDesktopComposeUiTest(400, 800) {
        val label = Labels()
        val lastMonday = LocalDate(2026, 9, 7)
        setContent {
            label.read(LocalDate(2026, 9, 9), 0, rested = true)
            GainsTheme { RecentWeeksCalendar(emptyMap(), today, restWeeks = setOf(lastMonday)) }
        }
        onNodeWithContentDescription(label[LocalDate(2026, 9, 9), 0, true]).assertExists()
        onNodeWithText("Rest week: missed, but the streak held").assertExists()
    }

    @Test
    fun aMonthDrawsOnlyItsOwnDays() = runDesktopComposeUiTest(400, 800) {
        val label = Labels()
        setContent {
            label.read(LocalDate(2026, 9, 1), 0); label.read(LocalDate(2026, 9, 30), 0); label.read(LocalDate(2026, 8, 31), 0)
            GainsTheme { MonthCalendar(YearMonth(2026, 9), emptyMap(), LocalDate(2026, 10, 13)) }
        }
        onNodeWithContentDescription(label[LocalDate(2026, 9, 1), 0]).assertExists()
        onNodeWithContentDescription(label[LocalDate(2026, 9, 30), 0]).assertExists()
        // Monday 31 August shares the first row with 1 September but belongs to the month before.
        onNodeWithContentDescription(label[LocalDate(2026, 8, 31), 0]).assertDoesNotExist()
    }

    @Test
    fun monthsRunFromThisOneBackToTheFirstSession() {
        assertEquals(
            listOf(YearMonth(2026, 10), YearMonth(2026, 9), YearMonth(2026, 8)),
            monthsBetween(LocalDate(2026, 8, 30), LocalDate(2026, 10, 13)),
        )
        assertEquals(listOf(YearMonth(2026, 1), YearMonth(2025, 12)), monthsBetween(LocalDate(2025, 12, 31), LocalDate(2026, 1, 1)))
    }
}
