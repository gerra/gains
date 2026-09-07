package app.gains.program

import app.gains.analysis.TrainingSnapshot
import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.ExerciseSlot
import app.gains.domain.Modality
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.Session
import app.gains.domain.WeightUnit

data class PlannedSet(val weightKg: Double?, val reps: Int?, val seconds: Int?, val isWarmup: Boolean = false)

data class PlannedExercise(
    val exercise: Exercise,
    val slot: ExerciseSlot,
    /** Warm-up sets first (flagged), then the work sets. */
    val sets: List<PlannedSet>,
    /** "5 × 3+", or "6 × 2+" once a stage ladder has moved on: always the stage the sets were built for. */
    val targetLabel: String,
    val hint: String?,
    /**
     * The weights were borrowed from a session that did not attempt this slot's scheme (a free
     * workout, or another slot of the program) rather than produced by the progression rule. The
     * editor offers to clear them, since the lifter may want to start the slot elsewhere.
     */
    val seeded: Boolean = false,
    /** GZCLP tier of the slot's scheme, when it has one: drives warm-ups and rest guidance. */
    val tier: Gzclp.Tier? = null,
    /** Where the borrowed weights came from, when [seeded]. */
    val source: Progression.Source? = null,
) {
    val workSets: List<PlannedSet> get() = sets.filter { !it.isWarmup }
    val warmupSets: List<PlannedSet> get() = sets.filter { it.isWarmup }
}

data class DayPlan(val day: ProgramDay, val exercises: List<PlannedExercise>)

/** Lifter settings the planner needs: the empty bar's weight and whether to generate warm-ups at all. */
data class PlanOptions(val barKg: Double = Gzclp.DEFAULT_BAR_KG, val warmups: Boolean = true)

/**
 * Expands a program day into pre-filled sets using the lifter's history.
 *
 * Only sessions logged against this program on a day whose slot prescribes the same scheme drive
 * the progression rule: those are the sessions that actually attempted the slot's sets × reps, so
 * they can be read as a hit or a miss. Anything else (free sessions, the same exercise in another
 * slot such as a GZCLP T2 squat before a T1 day) only seeds the starting weight, and once the slot
 * has its own history that history wins outright.
 */
object DayPlanner {
    fun plan(program: Program, day: ProgramDay, snapshot: TrainingSnapshot, unit: WeightUnit, options: PlanOptions = PlanOptions()): DayPlan {
        val planned = day.slots.mapNotNull { slot ->
            val exercise = snapshot.exercisesById[slot.exerciseId] ?: return@mapNotNull null
            val own = lastSession(snapshot, slot.exerciseId) { onSameScheme(program, slot, it) }
            var source: Progression.Source? = null
            val s = if (own != null) Progression.suggest(slot, exercise, own.entry(slot.exerciseId), unit) else {
                val recent = recentSessions(snapshot, slot.exerciseId, Gzclp.RECENT_SESSIONS)
                source = recent.firstOrNull()?.let { if (it.program == null) Progression.Source.FREE_SESSION else Progression.Source.DIFFERENT_SCHEME }
                Progression.start(slot, exercise, recent.map { it.entry(slot.exerciseId) }, unit, source ?: Progression.Source.FREE_SESSION)
            }
            val tier = Gzclp.tierOf(slot)
            val isometric = exercise.modality == Modality.ISOMETRIC
            val work = List(s.sets) {
                PlannedSet(
                    weightKg = s.weightKg,
                    reps = if (isometric || exercise.modality == Modality.CARDIO) null else s.reps,
                    seconds = if (isometric) s.reps else null,
                )
            }
            val warmups = if (options.warmups && tier != null && s.weightKg != null && exercise.modality == Modality.WEIGHTED) {
                Gzclp.warmups(tier, exercise, s.weightKg, options.barKg, Gzclp.increment(slot, unit), unit)
                    .map { PlannedSet(it.weightKg, it.reps, null, isWarmup = true) }
            } else emptyList()
            val seeded = own == null && s.weightKg != null
            PlannedExercise(
                exercise, slot, warmups + work, s.target.targetLabel(slot.lastSetAmrap), s.hint,
                seeded = seeded, tier = tier, source = source.takeIf { seeded },
            )
        }
        return DayPlan(day, planned)
    }

    /** Most recent entry for the exercise from any session. */
    fun lastEntry(snapshot: TrainingSnapshot, exerciseId: String): ExerciseEntry? =
        lastSession(snapshot, exerciseId)?.entry(exerciseId)

    /** Most recent session with working sets of the exercise that passes [sessionFilter]. */
    private fun lastSession(snapshot: TrainingSnapshot, exerciseId: String, sessionFilter: (Session) -> Boolean = { true }): Session? =
        recentSessions(snapshot, exerciseId, 1, sessionFilter).firstOrNull()

    /** The [limit] most recent sessions with working sets of the exercise, newest first. */
    private fun recentSessions(snapshot: TrainingSnapshot, exerciseId: String, limit: Int, sessionFilter: (Session) -> Boolean = { true }): List<Session> =
        snapshot.sessions.asSequence()
            .filter { s -> sessionFilter(s) && s.exercises.any { it.exerciseId == exerciseId && it.workingSets.isNotEmpty() } }
            .sortedByDescending { it.timestamp }
            .take(limit)
            .toList()

    private fun Session.entry(exerciseId: String): ExerciseEntry = exercises.first { it.exerciseId == exerciseId }

    /** The session was started from a day of [program] whose slot for the exercise matches [slot]'s scheme. */
    private fun onSameScheme(program: Program, slot: ExerciseSlot, session: Session): Boolean {
        val ref = session.program ?: return false
        if (ref.programId != program.id) return false
        val logged = program.day(ref.dayId)?.slots?.firstOrNull { it.exerciseId == slot.exerciseId } ?: return false
        return logged.sameScheme(slot)
    }
}
