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

    /** The barbell lifts that take coaching to learn; a hip thrust or a Romanian deadlift works with dumbbells too. */
    private val barbellLifts = setOf("squat", "deadlift", "bench_press", "overhead_press", "barbell_row", "front_squat")

    private fun usesBarbell(program: Program): Boolean = program.days.any { day -> day.slots.any { it.exerciseId in barbellLifts } }
}
