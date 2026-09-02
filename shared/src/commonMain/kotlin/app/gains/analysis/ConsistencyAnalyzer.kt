package app.gains.analysis

import app.gains.analysis.Dates.minusDays
import app.gains.domain.Session
import kotlinx.datetime.LocalDate

data class WeekCount(val weekStart: LocalDate, val sessions: Int, val rollingAverage: Double)

object ConsistencyAnalyzer {
    /** Sessions per ISO week from the first session to [today], with a trailing [window]-week average. */
    fun sessionsPerWeek(sessions: List<Session>, today: LocalDate, window: Int = 4): List<WeekCount> {
        if (sessions.isEmpty()) return emptyList()
        val first = sessions.minOf { it.date }
        val counts = sessions.groupingBy { Dates.weekStart(it.date) }.eachCount()
        val weeks = Dates.weeksBetween(first, today)
        val result = ArrayList<WeekCount>(weeks.size)
        for ((i, week) in weeks.withIndex()) {
            val lo = maxOf(0, i - window + 1)
            val span = i - lo + 1
            val sum = (lo..i).sumOf { counts[weeks[it]] ?: 0 }
            result.add(WeekCount(week, counts[week] ?: 0, sum.toDouble() / span))
        }
        return result
    }

    /** Sessions per calendar day, for the heat-map. */
    fun perDay(sessions: List<Session>): Map<LocalDate, Int> = sessions.groupingBy { it.date }.eachCount()

    /** Number of consecutive weeks up to [today] with at least one session. */
    fun currentStreakWeeks(sessions: List<Session>, today: LocalDate): Int {
        val weeks = sessions.map { Dates.weekStart(it.date) }.toSet()
        var streak = 0
        var week = Dates.weekStart(today)
        // The current week counts only if it already has a session; otherwise start from last week.
        if (week !in weeks) week = week.minusDays(7)
        while (week in weeks) {
            streak++
            week = week.minusDays(7)
        }
        return streak
    }
}
