package app.gains.analysis

import app.gains.domain.Goal

/** How the chosen goal changes what the insight engine flags and which insights lead. */
object GoalTuning {
    fun thresholds(goal: Goal?): InsightThresholds = when (goal) {
        Goal.GET_STRONGER -> InsightThresholds(stallWeeks = 4, progressMinGainFraction = 0.02)
        Goal.BUILD_MUSCLE -> InsightThresholds(neglectMuscleMinWeeklySets = 6.0, neglectMuscleBaselineWeeklySets = 10.0, stallWeeks = 8)
        Goal.LOSE_FAT -> InsightThresholds(consistencyWeeks = 3, consistencyTrendFraction = 0.10, regressionMinDropFraction = 0.10)
        Goal.GENERAL_FITNESS, null -> InsightThresholds()
    }

    /** Severity bonus per kind so the insights that matter for the goal sort first. */
    fun emphasis(goal: Goal?): Map<InsightKind, Double> = when (goal) {
        Goal.GET_STRONGER -> mapOf(InsightKind.REGRESSION to 10.0, InsightKind.STALL to 10.0, InsightKind.PROGRESS to 10.0)
        Goal.BUILD_MUSCLE -> mapOf(InsightKind.NEGLECT to 15.0)
        Goal.LOSE_FAT -> mapOf(InsightKind.CONSISTENCY to 30.0)
        Goal.GENERAL_FITNESS, null -> emptyMap()
    }

    fun rank(insights: List<Insight>, goal: Goal?): List<Insight> {
        val bonus = emphasis(goal)
        if (bonus.isEmpty()) return insights
        return insights.sortedByDescending { it.severity + (bonus[it.kind] ?: 0.0) }
    }

    /** Subtitle under "What's moving". */
    fun headline(goal: Goal?): String? = when (goal) {
        Goal.GET_STRONGER -> "Strength signals first"
        Goal.BUILD_MUSCLE -> "Volume and neglected muscles first"
        Goal.LOSE_FAT -> "Consistency first"
        Goal.GENERAL_FITNESS, null -> null
    }
}
