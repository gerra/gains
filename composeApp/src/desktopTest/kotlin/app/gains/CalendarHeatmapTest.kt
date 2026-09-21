package app.gains

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.gains.ui.charts.CalendarHeatmap
import app.gains.ui.i18n.heatmapDayText
import app.gains.ui.theme.GainsTheme
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals

/** Days on the history calendar open when tapped, and the ones that cannot be opened are not buttons. */
@OptIn(ExperimentalTestApi::class)
class CalendarHeatmapTest {
    private val today = LocalDate(2026, 9, 19)
    private fun daysAgo(n: Int) = today.minus(n, DateTimeUnit.DAY)

    /**
     * The days' content descriptions, as the heat-map words them ("Wed 16 Sep 2026, 1 session").
     * Read in the composition, where the resources know their language.
     */
    private class Labels {
        private val texts = mutableMapOf<Pair<LocalDate, Int>, String>()
        @Composable
        fun read(vararg days: Pair<LocalDate, Int>) { for ((date, sessions) in days) texts[date to sessions] = heatmapDayText(date, sessions) }
        operator fun get(date: LocalDate, sessions: Int): String = texts.getValue(date to sessions)
    }

    @Test
    fun tappingADayWithASessionReportsThatDay() = runDesktopComposeUiTest(400, 800) {
        val opened = mutableListOf<LocalDate>()
        val label = Labels()
        setContent {
            label.read(daysAgo(3) to 1, daysAgo(4) to 2)
            GainsTheme { CalendarHeatmap(mapOf(daysAgo(3) to 1, daysAgo(4) to 2), today, weeks = 26, onDayClick = { opened += it }) }
        }
        onNodeWithContentDescription(label[daysAgo(3), 1]).performClick()
        onNodeWithContentDescription(label[daysAgo(4), 2]).performClick()
        waitForIdle()
        assertEquals(listOf(daysAgo(3), daysAgo(4)), opened)
    }

    @Test
    fun emptyDaysAndTheFutureCannotBeOpened() = runDesktopComposeUiTest(400, 800) {
        val label = Labels()
        setContent {
            label.read(daysAgo(3) to 1, daysAgo(2) to 0, today to 0, today.plus(1, DateTimeUnit.DAY) to 0)
            GainsTheme { CalendarHeatmap(mapOf(daysAgo(3) to 1), today, weeks = 26, onDayClick = {}) }
        }
        onNodeWithContentDescription(label[daysAgo(3), 1]).assertHasClickAction()
        onNodeWithContentDescription(label[daysAgo(2), 0]).assertHasNoClickAction()
        onNodeWithContentDescription(label[today, 0]).assertHasNoClickAction()
        onNodeWithContentDescription(label[today.plus(1, DateTimeUnit.DAY), 0]).assertDoesNotExist()
    }

    @Test
    fun withoutAHandlerNothingIsAButton() = runDesktopComposeUiTest(400, 800) {
        val label = Labels()
        setContent {
            label.read(daysAgo(3) to 1)
            GainsTheme { CalendarHeatmap(mapOf(daysAgo(3) to 1), today, weeks = 26) }
        }
        onNodeWithContentDescription(label[daysAgo(3), 1]).assertHasNoClickAction()
    }

    @Test
    fun olderWeeksScrollIntoReachOnANarrowScreen() = runDesktopComposeUiTest(400, 800) {
        val opened = mutableListOf<LocalDate>()
        // 26 weeks at the minimum cell size is far wider than 400px, so this day starts off-screen to the left.
        val old = daysAgo(25 * 7)
        val label = Labels()
        setContent {
            label.read(old to 1)
            GainsTheme { CalendarHeatmap(mapOf(old to 1), today, weeks = 26, onDayClick = { opened += it }) }
        }
        onNodeWithContentDescription(label[old, 1]).performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf(old), opened)
    }
}
