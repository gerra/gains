package app.gains.analysis

import app.gains.domain.Goal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class GoalTuningTest {
    private val regression = Insight(
        InsightKind.REGRESSION, 105.0, InsightSubject.Lift(TestData.bench),
        InsightDetail.Regression(Performance(60.0, TestData.weighted(60.0, 8)), Performance(63.0, TestData.weighted(63.0, 8)), kotlinx.datetime.LocalDate(2026, 1, 1), 0.05),
    )
    private val consistency = Insight(InsightKind.CONSISTENCY, 90.0, InsightSubject.Frequency(Trend.FLAT), InsightDetail.Consistency(3.0, 3.0, Trend.FLAT, 4))

    @Test
    fun fatLossRanksConsistencyFirst() {
        assertEquals(listOf(consistency, regression), GoalTuning.rank(listOf(regression, consistency), Goal.LOSE_FAT))
    }

    @Test
    fun defaultIsIdentity() {
        val input = listOf(regression, consistency)
        assertSame(input, GoalTuning.rank(input, null))
        assertEquals(InsightThresholds(), GoalTuning.thresholds(null))
        assertEquals(4, GoalTuning.thresholds(Goal.GET_STRONGER).stallWeeks)
    }
}
