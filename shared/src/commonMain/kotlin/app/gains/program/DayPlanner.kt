package app.gains.program

import app.gains.analysis.TrainingSnapshot
import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.ExerciseSlot
import app.gains.domain.Modality
import app.gains.domain.ProgramDay
import app.gains.domain.WeightUnit

data class PlannedSet(val weightKg: Double?, val reps: Int?, val seconds: Int?)

data class PlannedExercise(
    val exercise: Exercise,
    val slot: ExerciseSlot,
    val sets: List<PlannedSet>,
    /** "5 × 3+" */
    val targetLabel: String,
    val hint: String?,
)

data class DayPlan(val day: ProgramDay, val exercises: List<PlannedExercise>)

/** Expands a program day into pre-filled sets using the lifter's history. */
object DayPlanner {
    fun plan(day: ProgramDay, snapshot: TrainingSnapshot, unit: WeightUnit): DayPlan {
        val planned = day.slots.mapNotNull { slot ->
            val exercise = snapshot.exercisesById[slot.exerciseId] ?: return@mapNotNull null
            val last = lastEntry(snapshot, slot.exerciseId)
            val s = Progression.suggest(slot, exercise, last, unit)
            val isometric = exercise.modality == Modality.ISOMETRIC
            val sets = List(s.sets) {
                PlannedSet(
                    weightKg = s.weightKg,
                    reps = if (isometric || exercise.modality == Modality.CARDIO) null else s.reps,
                    seconds = if (isometric) s.reps else null,
                )
            }
            PlannedExercise(exercise, slot, sets, slot.targetLabel, s.hint)
        }
        return DayPlan(day, planned)
    }

    /** Most recent entry for the exercise from any session. */
    fun lastEntry(snapshot: TrainingSnapshot, exerciseId: String): ExerciseEntry? =
        snapshot.sessions.asSequence()
            .filter { s -> s.exercises.any { it.exerciseId == exerciseId } }
            .maxByOrNull { it.timestamp }
            ?.exercises?.first { it.exerciseId == exerciseId }
}
