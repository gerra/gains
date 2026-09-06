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

data class PlannedSet(val weightKg: Double?, val reps: Int?, val seconds: Int?)

data class PlannedExercise(
    val exercise: Exercise,
    val slot: ExerciseSlot,
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
)

data class DayPlan(val day: ProgramDay, val exercises: List<PlannedExercise>)

/**
 * Expands a program day into pre-filled sets using the lifter's history.
 *
 * Only sessions logged against this program on a day whose slot prescribes the same scheme drive
 * the progression rule: those are the sessions that actually attempted the slot's sets × reps, so
 * they can be read as a hit or a miss. Anything else (free sessions, the same exercise in another
 * slot such as a GZCLP T2 squat before a T1 day) only seeds the starting weight.
 */
object DayPlanner {
    fun plan(program: Program, day: ProgramDay, snapshot: TrainingSnapshot, unit: WeightUnit): DayPlan {
        val planned = day.slots.mapNotNull { slot ->
            val exercise = snapshot.exercisesById[slot.exerciseId] ?: return@mapNotNull null
            val own = lastSession(snapshot, slot.exerciseId) { onSameScheme(program, slot, it) }
            val s = if (own != null) Progression.suggest(slot, exercise, own.entry(slot.exerciseId), unit) else {
                val any = lastSession(snapshot, slot.exerciseId)
                val source = if (any?.program == null) Progression.Source.FREE_SESSION else Progression.Source.DIFFERENT_SCHEME
                Progression.start(slot, exercise, any?.entry(slot.exerciseId), unit, source)
            }
            val isometric = exercise.modality == Modality.ISOMETRIC
            val sets = List(s.sets) {
                PlannedSet(
                    weightKg = s.weightKg,
                    reps = if (isometric || exercise.modality == Modality.CARDIO) null else s.reps,
                    seconds = if (isometric) s.reps else null,
                )
            }
            PlannedExercise(exercise, slot, sets, s.target.targetLabel(slot.lastSetAmrap), s.hint, seeded = own == null && s.weightKg != null)
        }
        return DayPlan(day, planned)
    }

    /** Most recent entry for the exercise from any session. */
    fun lastEntry(snapshot: TrainingSnapshot, exerciseId: String): ExerciseEntry? =
        lastSession(snapshot, exerciseId)?.entry(exerciseId)

    /** Most recent session containing the exercise that passes [sessionFilter]. */
    private fun lastSession(snapshot: TrainingSnapshot, exerciseId: String, sessionFilter: (Session) -> Boolean = { true }): Session? =
        snapshot.sessions.asSequence()
            .filter { s -> sessionFilter(s) && s.exercises.any { it.exerciseId == exerciseId } }
            .maxByOrNull { it.timestamp }

    private fun Session.entry(exerciseId: String): ExerciseEntry = exercises.first { it.exerciseId == exerciseId }

    /** The session was started from a day of [program] whose slot for the exercise matches [slot]'s scheme. */
    private fun onSameScheme(program: Program, slot: ExerciseSlot, session: Session): Boolean {
        val ref = session.program ?: return false
        if (ref.programId != program.id) return false
        val logged = program.day(ref.dayId)?.slots?.firstOrNull { it.exerciseId == slot.exerciseId } ?: return false
        return logged.sameScheme(slot)
    }
}
