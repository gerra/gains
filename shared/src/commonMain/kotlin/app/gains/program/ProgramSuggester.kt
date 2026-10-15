package app.gains.program

import app.gains.domain.Caution
import app.gains.domain.Experience
import app.gains.domain.Goal
import app.gains.domain.GoalProfile
import app.gains.domain.Program
import kotlin.math.abs

/** Ranks built-in programs against the onboarding answers. Higher score first; ties by name. */
object ProgramSuggester {
    fun score(profile: GoalProfile, program: Program, cautions: Set<Caution> = emptySet()): Int {
        var score = 0
        if (program.level == profile.experience) score += 3
        else if (abs(program.level.ordinal - profile.experience.ordinal) == 1) score += 1
        val dayGap = abs(program.daysPerWeek - profile.daysPerWeek)
        if (dayGap == 0) score += 2 else if (dayGap == 1) score += 1
        if (profile.goal in program.goals) score += 2
        // A beginner who is not after strength alone starts easier without a barbell to learn first.
        if (profile.experience == Experience.BEGINNER && profile.goal != Goal.GET_STRONGER && !usesBarbell(program)) score += 1
        // Heavy sets are what pregnancy and high blood pressure ask to avoid: those programs go last.
        if (cautions.any { it.easesHardSets } && Tailor.isHeavy(program)) score -= 5
        return score
    }

    /** [programs] as written, not tailored: the heavy ones are recognised by their own sets. */
    fun suggest(profile: GoalProfile, programs: List<Program>, cautions: Set<Caution> = emptySet()): List<Program> =
        programs.filter { it.isBuiltIn }
            .sortedWith(compareByDescending<Program> { score(profile, it, cautions) }.thenBy { it.name })

    /** How many programs are put first; the rest fold away under "More programs". */
    const val SUGGESTED = 2

    /** The programs put first, best match first, and the rest, still in order of fit. */
    data class Shortlist(val suggested: List<Program>, val more: List<Program>)

    /**
     * The [SUGGESTED] best matches, plus [keep] (the active program, which is never folded away), and
     * the rest. A newcomer sees two programs instead of eight; an experienced lifter sees their own
     * kind first and the beginner programs under "More". [programs] as written, as for [suggest].
     */
    fun shortlist(profile: GoalProfile, programs: List<Program>, cautions: Set<Caution> = emptySet(), keep: String? = null): Shortlist {
        val ranked = suggest(profile, programs, cautions)
        val first = ranked.take(SUGGESTED).map { it.id }.toSet() + listOfNotNull(keep)
        return Shortlist(ranked.filter { it.id in first }, ranked.filter { it.id !in first })
    }

    /** The barbell lifts that take coaching to learn; a hip thrust or a Romanian deadlift works with dumbbells too. */
    private val barbellLifts = setOf("squat", "deadlift", "bench_press", "overhead_press", "barbell_row", "front_squat")

    private fun usesBarbell(program: Program): Boolean = program.days.any { day -> day.slots.any { it.exerciseId in barbellLifts } }
}
