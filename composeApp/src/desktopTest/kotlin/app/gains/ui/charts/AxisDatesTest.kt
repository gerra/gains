package app.gains.ui.charts

import app.gains.ui.charts.ChartMath.x
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** The dates along a chart's x axis say which year they are in whenever that is not this year. */
class AxisDatesTest {
    private val today = LocalDate(2026, 9, 21)

    private fun forms(from: LocalDate, to: LocalDate) = ChartMath.axisDates(ChartMath.xTicks(from.x(), to.x()), today).map { it.form }

    @Test
    fun insideThisYearTheYearGoesWithoutSaying() {
        assertEquals(List(5) { DateForm.DAY_MONTH }, forms(LocalDate(2026, 3, 1), today))
    }

    @Test
    fun aWindowReachingIntoLastYearNamesTheYearWhereItStartsAndWhereItChanges() {
        // 12 Oct 2025 · 3 Nov · 25 Nov · 16 Dec · 7 Jan 2026
        assertEquals(
            listOf(DateForm.DAY_MONTH_YEAR, DateForm.DAY_MONTH, DateForm.DAY_MONTH, DateForm.DAY_MONTH, DateForm.DAY_MONTH_YEAR),
            forms(LocalDate(2025, 10, 12), LocalDate(2026, 1, 7)),
        )
    }

    @Test
    fun aRangeWhollyInAnotherYearStillSaysWhichOne() {
        assertEquals(DateForm.DAY_MONTH_YEAR, forms(LocalDate(2025, 2, 1), LocalDate(2025, 5, 1)).first())
    }

    @Test
    fun allTimeOverSeveralYearsIsLabelledByMonthAndYear() {
        assertEquals(List(5) { DateForm.MONTH_YEAR }, forms(LocalDate(2024, 2, 12), today))
        val dates = ChartMath.axisDates(ChartMath.xTicks(LocalDate(2024, 2, 12).x(), today.x()), today).map { it.date }
        assertEquals(LocalDate(2024, 2, 12), dates.first())
        assertEquals(today, dates.last())
    }

    @Test
    fun aSingleSessionIsOneTick() {
        assertEquals(listOf(LocalDate(2025, 6, 1).x()), ChartMath.xTicks(LocalDate(2025, 6, 1).x(), LocalDate(2025, 6, 1).x()))
        assertEquals(listOf(DateForm.DAY_MONTH_YEAR), forms(LocalDate(2025, 6, 1), LocalDate(2025, 6, 1)))
    }
}
