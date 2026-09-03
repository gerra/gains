package app.gains.analysis

import app.gains.domain.Goal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class GoalTuningTest {
    private val regression = Insight(InsightKind.REGRESSION, 105.0, "Bench Press", "down 5%")
    private val consistency = Insight(InsightKind.CONSISTENCY, 90.0, "Consistency", "steady")

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
