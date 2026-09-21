package app.gains

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.charts.CalendarHeatmap
import app.gains.ui.theme.GainsTheme
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import kotlin.test.Test
import kotlin.test.assertEquals

/** Days on the history calendar open when tapped, and the ones that cannot be opened are not buttons. */
@OptIn(ExperimentalTestApi::class)
class CalendarHeatmapTest {
    private val today = LocalDate(2026, 9, 19)
    private fun daysAgo(n: Int) = today.minus(n, DateTimeUnit.DAY)
    /** The day's content description, as the heat-map words it: "Wed 16 Sep 2026, 1 session". */
    private fun label(date: LocalDate, sessions: String) = runBlocking {
        val day = getStringArray(Res.array.days_short)[date.dayOfWeek.isoDayNumber - 1]
        val month = getStringArray(Res.array.months_short)[date.month.ordinal]
        getString(Res.string.heatmap_day, day, getString(Res.string.date_with_year, date.dayOfMonth, month, date.year), sessions)
    }

    @Test
    fun tappingADayWithASessionReportsThatDay() = runDesktopComposeUiTest(400, 800) {
        val opened = mutableListOf<LocalDate>()
        setContent {
            GainsTheme { CalendarHeatmap(mapOf(daysAgo(3) to 1, daysAgo(4) to 2), today, weeks = 26, onDayClick = { opened += it }) }
        }
        onNodeWithContentDescription(label(daysAgo(3), "1 session")).performClick()
        onNodeWithContentDescription(label(daysAgo(4), "2 sessions")).performClick()
        waitForIdle()
        assertEquals(listOf(daysAgo(3), daysAgo(4)), opened)
    }

    @Test
    fun emptyDaysAndTheFutureCannotBeOpened() = runDesktopComposeUiTest(400, 800) {
        setContent {
            GainsTheme { CalendarHeatmap(mapOf(daysAgo(3) to 1), today, weeks = 26, onDayClick = {}) }
        }
        onNodeWithContentDescription(label(daysAgo(3), "1 session")).assertHasClickAction()
        onNodeWithContentDescription(label(daysAgo(2), "no sessions")).assertHasNoClickAction()
        onNodeWithContentDescription(label(today, "no sessions")).assertHasNoClickAction()
        onNodeWithContentDescription(label(today.plus(1, DateTimeUnit.DAY), "no sessions")).assertDoesNotExist()
    }

    @Test
    fun withoutAHandlerNothingIsAButton() = runDesktopComposeUiTest(400, 800) {
        setContent { GainsTheme { CalendarHeatmap(mapOf(daysAgo(3) to 1), today, weeks = 26) } }
        onNodeWithContentDescription(label(daysAgo(3), "1 session")).assertHasNoClickAction()
    }

    @Test
    fun olderWeeksScrollIntoReachOnANarrowScreen() = runDesktopComposeUiTest(400, 800) {
        val opened = mutableListOf<LocalDate>()
        // 26 weeks at the minimum cell size is far wider than 400px, so this day starts off-screen to the left.
        val old = daysAgo(25 * 7)
        setContent {
            GainsTheme { CalendarHeatmap(mapOf(old to 1), today, weeks = 26, onDayClick = { opened += it }) }
        }
        onNodeWithContentDescription(label(old, "1 session")).performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf(old), opened)
    }
}
