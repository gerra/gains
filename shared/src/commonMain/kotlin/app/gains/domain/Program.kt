package app.gains.domain

/** What the user says they train for. Drives program suggestions and which insights lead. */
enum class Goal(val label: String, val blurb: String) {
    BUILD_MUSCLE("Build muscle", "Higher reps, more sets, the volume dashboard leads."),
    GET_STRONGER("Get stronger", "Heavy compounds, linear progression, estimated 1RM leads."),
    LOSE_FAT("Lose fat", "Keep strength while training consistently; consistency leads."),
    GENERAL_FITNESS("General fitness", "A balanced routine you can keep up for years."),
}

enum class Experience(val label: String, val blurb: String) {
    BEGINNER("Beginner", "Under a year of consistent lifting, or coming back after a long break."),
    INTERMEDIATE("Intermediate", "One to three years; adding weight every session no longer works."),
    ADVANCED("Advanced", "Several years; progress comes in blocks, not sessions."),
}

/** The onboarding answers. Stored as one settings value. */
data class GoalProfile(val goal: Goal, val experience: Experience, val daysPerWeek: Int) {
    fun encode(): String = "${goal.name}|${experience.name}|$daysPerWeek"

    companion object {
        const val MIN_DAYS = 2
        const val MAX_DAYS = 6

        /** Null for a blank or unparseable value, so a corrupt setting behaves like "not set". */
        fun decode(raw: String?): GoalProfile? {
            val parts = raw?.split('|') ?: return null
            if (parts.size != 3) return null
            val goal = Goal.entries.firstOrNull { it.name == parts[0] } ?: return null
            val experience = Experience.entries.firstOrNull { it.name == parts[1] } ?: return null
            val days = parts[2].toIntOrNull()?.takeIf { it in MIN_DAYS..MAX_DAYS } ?: return null
            return GoalProfile(goal, experience, days)
        }
    }
}

/** Rep prescription for one set. */
sealed interface RepTarget {
    /** Exactly [reps]. */
    data class Fixed(val reps: Int) : RepTarget
    /** Anywhere from [min] to [max]; move up when every set reaches [max]. */
    data class Range(val min: Int, val max: Int) : RepTarget
    /** At least [min], as many as possible. */
    data class Amrap(val min: Int) : RepTarget

    /** Reps to pre-fill in the editor. */
    val prefillReps: Int get() = when (this) {
        is Fixed -> reps
        is Range -> min
        is Amrap -> min
    }

    /** Reps that count as "hit" for progression. */
    val successReps: Int get() = when (this) {
        is Fixed -> reps
        is Range -> max
        is Amrap -> min
    }

    val label: String get() = when (this) {
        is Fixed -> reps.toString()
        is Range -> "$min-$max"
        is Amrap -> "$min+"
    }
}

data class SetsReps(val sets: Int, val reps: RepTarget) {
    val label: String get() = "$sets×${reps.label}"
}

/** How the next session's load is chosen from the last one. Steps are per unit so lbs users get round numbers. */
sealed interface ProgressionRule {
    data object None : ProgressionRule

    /** Add the step when every set reached its target reps. */
    data class Linear(val stepKg: Double, val stepLbs: Double = stepKg * 2) : ProgressionRule

    /**
     * Reps climb from [min] to [max] at one weight; once every set reaches [max], add the step and
     * drop back to [min]. A zero step means "move to the harder variation" (bodyweight work).
     */
    data class DoubleProgression(val min: Int, val max: Int, val stepKg: Double, val stepLbs: Double = stepKg * 2) : ProgressionRule

    /**
     * GZCL-style ladder: succeed → same stage, add the step; fail → next stage at the same weight;
     * fail on the last stage → drop ~10% and restart at the first stage.
     */
    data class StageLadder(val stages: List<SetsReps>, val stepKg: Double, val stepLbs: Double = stepKg * 2) : ProgressionRule

    fun step(unit: WeightUnit): Double? = when (this) {
        None -> null
        is Linear -> if (unit == WeightUnit.KG) stepKg else stepLbs
        is DoubleProgression -> if (unit == WeightUnit.KG) stepKg else stepLbs
        is StageLadder -> if (unit == WeightUnit.KG) stepKg else stepLbs
    }
}

/** One exercise in a program day. */
data class ExerciseSlot(
    val exerciseId: String,
    val sets: Int,
    val reps: RepTarget,
    /** "4×5, 1×5+": only the final set is open-ended. */
    val lastSetAmrap: Boolean = false,
    val progression: ProgressionRule = ProgressionRule.None,
    val note: String? = null,
) {
    /** "5 × 3+", "3 × 8-12", "4 × 5, 1 × 5+". */
    val targetLabel: String get() = when {
        lastSetAmrap && sets > 1 -> "${sets - 1} × ${reps.label}, 1 × ${reps.prefillReps}+"
        lastSetAmrap -> "1 × ${reps.prefillReps}+"
        else -> "$sets × ${reps.label}"
    }
}

data class ProgramDay(val id: String, val name: String, val slots: List<ExerciseSlot>)

data class Program(
    val id: String,
    val name: String,
    val description: String,
    val goals: Set<Goal>,
    val level: Experience,
    val daysPerWeek: Int,
    val days: List<ProgramDay>,
    val isBuiltIn: Boolean,
) {
    fun day(id: String): ProgramDay? = days.firstOrNull { it.id == id }
}

/** Which program day a session was started from. */
data class ProgramDayRef(val programId: String, val dayId: String)

/** A session's link to a program, without its sets. Enough for rotation and "last done" labels. */
data class ProgramLink(val sessionId: String, val timestamp: kotlinx.datetime.LocalDateTime, val ref: ProgramDayRef)

/** Everything the program screens observe. */
data class ProgramState(
    val profile: GoalProfile? = null,
    val programs: List<Program> = emptyList(),
    val activeProgramId: String? = null,
) {
    val active: Program? get() = programs.firstOrNull { it.id == activeProgramId }
}
