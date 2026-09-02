package app.gains.analysis

import app.gains.analysis.Dates.minusDays
import app.gains.domain.BodyweightEntry
import kotlinx.datetime.LocalDate

data class BodyweightPoint(val date: LocalDate, val weightKg: Double, val rollingAverageKg: Double)

object BodyweightAnalyzer {
    /** Each entry with the mean of all entries in the trailing [days]-day window (inclusive). */
    fun withRollingAverage(entries: List<BodyweightEntry>, days: Int = 7): List<BodyweightPoint> {
        val sorted = entries.sortedBy { it.date }
        return sorted.map { entry ->
            val from = entry.date.minusDays(days - 1)
            val window = sorted.filter { it.date >= from && it.date <= entry.date }
            BodyweightPoint(entry.date, entry.weightKg, window.sumOf { it.weightKg } / window.size)
        }
    }
}
