package app.gains.analysis

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

object Dates {
    fun today(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    /** Monday of the ISO week containing [date]. */
    fun weekStart(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

    fun daysBetween(from: LocalDate, to: LocalDate): Int = from.daysUntil(to)

    fun LocalDate.plusDays(days: Int): LocalDate = this.plus(days, DateTimeUnit.DAY)
    fun LocalDate.minusDays(days: Int): LocalDate = this.minus(days, DateTimeUnit.DAY)

    /** Consecutive Mondays from [from]'s week to [to]'s week inclusive. */
    fun weeksBetween(from: LocalDate, to: LocalDate): List<LocalDate> {
        val result = ArrayList<LocalDate>()
        var w = weekStart(from)
        val last = weekStart(to)
        while (w <= last) {
            result.add(w)
            w = w.plus(7, DateTimeUnit.DAY)
        }
        return result
    }

}
