package app.gains.program

import app.gains.domain.GoalProfile
import app.gains.domain.Program
import kotlin.math.abs

/** Ranks built-in programs against the onboarding answers. Higher score first; ties by name. */
object ProgramSuggester {
    fun score(profile: GoalProfile, program: Program): Int {
        var score = 0
        if (program.level == profile.experience) score += 3
        else if (abs(program.level.ordinal - profile.experience.ordinal) == 1) score += 1
        val dayGap = abs(program.daysPerWeek - profile.daysPerWeek)
        if (dayGap == 0) score += 2 else if (dayGap == 1) score += 1
        if (profile.goal in program.goals) score += 2
        return score
    }

    fun suggest(profile: GoalProfile, programs: List<Program>): List<Program> =
        programs.filter { it.isBuiltIn }
            .sortedWith(compareByDescending<Program> { score(profile, it) }.thenBy { it.name })
}
