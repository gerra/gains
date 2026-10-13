package app.gains.program

import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.ProgramLink
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** Which day comes next: the one after the most recently completed day of this program. Never looks at the weekday. */
object Rotation {
    fun nextDay(program: Program, links: List<ProgramLink>): ProgramDay? {
        if (program.days.isEmpty()) return null
        val last = links.filter { it.ref.programId == program.id }
            .maxWithOrNull(compareBy({ it.timestamp }, { it.sessionId })) ?: return program.days.first()
        val index = program.days.indexOfFirst { it.id == last.ref.dayId }
        return if (index < 0) program.days.first() else program.days[(index + 1) % program.days.size]
    }

    /**
     * This program's sessions since [start], when it was last started over, or all of them when it
     * never was. What [nextDay], [position] and [cycle] are given, so starting over begins at the
     * first day of week 1; "last done" and "this week" go on reading every session.
     */
    fun run(program: Program, links: List<ProgramLink>, start: LocalDateTime?): List<ProgramLink> =
        links.filter { it.ref.programId == program.id && (start == null || it.timestamp >= start) }

    /**
     * Where the lifter is in the program, counted in sessions rather than calendar weeks: a week off
     * leaves them where they were. [index] is the next session's place in the cycle, from 0.
     */
    data class Position(val done: Int, val cycle: Int, val week: Int, val weeks: Int, val index: Int)

    /** The [Position] after the sessions in [links]; null for a program with no days. */
    fun position(program: Program, links: List<ProgramLink>): Position? {
        if (program.days.isEmpty()) return null
        val perWeek = program.daysPerWeek.coerceAtLeast(1)
        val length = cycleLength(program)
        val done = links.count { it.ref.programId == program.id }
        val index = done % length
        return Position(done = done, cycle = done / length + 1, week = index / perWeek + 1, weeks = length / perWeek, index = index)
    }

    /**
     * The current cycle of the rotation, laid out as weeks of [Program.daysPerWeek] sessions: long
     * enough for the first day to come round to the first slot of a week again. GZCLP's four days at
     * three a week take four weeks; a 6-day PPL at six a week takes one. The next day sits at
     * [Position.index], with the days before it the ones done this cycle. Empty for a program with no days.
     */
    fun cycle(program: Program, links: List<ProgramLink>): List<List<ProgramDay>> {
        val next = nextDay(program, links) ?: return emptyList()
        val position = position(program, links) ?: return emptyList()
        val size = program.days.size
        val start = program.days.indexOf(next) - position.index
        return List(cycleLength(program)) { program.days[((start + it) % size + size) % size] }
            .chunked(program.daysPerWeek.coerceAtLeast(1))
    }

    private fun cycleLength(program: Program): Int = lcm(program.days.size, program.daysPerWeek.coerceAtLeast(1))
    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    private fun lcm(a: Int, b: Int): Int = a / gcd(a, b) * b

    /** Most recent completion date per day id. */
    fun lastCompletedByDay(program: Program, links: List<ProgramLink>): Map<String, LocalDate> =
        links.filter { it.ref.programId == program.id }
            .groupBy { it.ref.dayId }
            .mapValues { (_, l) -> l.maxOf { it.timestamp }.date }

    /** Sessions of this program in the last [days] days, for "3 of 4 this week" style labels. */
    fun completedSince(program: Program, links: List<ProgramLink>, from: LocalDate): Int =
        links.count { it.ref.programId == program.id && it.timestamp.date >= from }
}
