package app.gains.program

import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.ProgramLink
import kotlinx.datetime.LocalDate

/** Which day comes next: the one after the most recently completed day of this program. Never looks at the weekday. */
object Rotation {
    fun nextDay(program: Program, links: List<ProgramLink>): ProgramDay? {
        if (program.days.isEmpty()) return null
        val last = links.filter { it.ref.programId == program.id }
            .maxWithOrNull(compareBy({ it.timestamp }, { it.sessionId })) ?: return program.days.first()
        val index = program.days.indexOfFirst { it.id == last.ref.dayId }
        return if (index < 0) program.days.first() else program.days[(index + 1) % program.days.size]
    }

    /** Most recent completion date per day id. */
    fun lastCompletedByDay(program: Program, links: List<ProgramLink>): Map<String, LocalDate> =
        links.filter { it.ref.programId == program.id }
            .groupBy { it.ref.dayId }
            .mapValues { (_, l) -> l.maxOf { it.timestamp }.date }

    /** Sessions of this program in the last [days] days, for "3 of 4 this week" style labels. */
    fun completedSince(program: Program, links: List<ProgramLink>, from: LocalDate): Int =
        links.count { it.ref.programId == program.id && it.timestamp.date >= from }
}
