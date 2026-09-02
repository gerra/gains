package app.gains.analysis

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
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

    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val fullMonths = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    fun short(date: LocalDate): String = "${date.day} ${months[date.month.ordinal]}"
    fun shortWithYear(date: LocalDate): String = "${date.day} ${months[date.month.ordinal]} ${date.year}"
    fun monthName(date: LocalDate): String = fullMonths[date.month.ordinal]
    fun monthShort(date: LocalDate): String = months[date.month.ordinal]

    /** "12 Feb" when in the current year, otherwise "12 Feb 2025". */
    fun contextual(date: LocalDate, today: LocalDate): String =
        if (date.year == today.year) short(date) else shortWithYear(date)

    fun dayLabel(day: DayOfWeek): String = day.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
}
