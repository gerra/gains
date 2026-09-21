package app.gains.analysis

import app.gains.domain.ExerciseEntry
import app.gains.domain.Modality
import app.gains.domain.SetEntry
import app.gains.domain.WeightUnit
import kotlinx.datetime.LocalDateTime

/**
 * What the lifter did last time, set by set: the editor's PREV column. Each row of the set table
 * lines up with the same-numbered set of the most recent session of the exercise, so "60×5" next
 * to set 3 is what set 3 was last time.
 */
object PreviousSets {
    /**
     * The most recent session of [exerciseId] with working sets, or null when there is none. Editing an
     * old workout compares with what came before it: pass its [before] timestamp and [excludeSessionId]
     * so neither the workout itself nor anything logged after it counts as "previous".
     */
    fun lastEntry(snapshot: TrainingSnapshot, exerciseId: String, before: LocalDateTime? = null, excludeSessionId: String? = null): ExerciseEntry? =
        snapshot.sessions.asSequence()
            .filter { s -> s.id != excludeSessionId && (before == null || s.timestamp < before) }
            .filter { s -> s.exercises.any { it.exerciseId == exerciseId && it.workingSets.isNotEmpty() } }
            .maxByOrNull { it.timestamp }
            ?.exercises?.first { it.exerciseId == exerciseId }

    /**
     * The set of [previous] that the [ordinal]-th (1-based) warm-up or work set lines up with: work
     * sets pair with work sets by number and warm-ups with warm-ups, so a row past what was done last
     * time gets nothing.
     */
    fun matching(previous: ExerciseEntry?, isWarmup: Boolean, ordinal: Int): SetEntry? {
        val pool = previous?.sets?.filter { it.isWarmup == isWarmup } ?: return null
        return pool.getOrNull(ordinal - 1)
    }

    /**
     * The set in a few characters, for a narrow column: "60×5" for a loaded set, "×8" for a bodyweight
     * one, "30 s" or "1:30" for a hold (with its load in front when there is one), "5 km · 12:00" for
     * cardio. Null when the set recorded nothing worth showing.
     */
    fun label(set: SetEntry, modality: Modality, unit: WeightUnit, labels: UnitLabels): String? {
        val weight = set.weightKg?.takeIf { it > 0 }?.let { Format.weightValue(it, unit) }
        val reps = set.reps?.takeIf { it > 0 }
        val seconds = set.seconds?.takeIf { it > 0 }?.let { Format.seconds(it, labels) }
        val km = set.distanceKm?.takeIf { it > 0 }?.let { Format.km(it, labels) }
        return when (modality) {
            Modality.CARDIO -> listOfNotNull(km, seconds).takeIf { it.isNotEmpty() }?.joinToString(" · ")
                ?: repsLabel(weight, reps)
            Modality.ISOMETRIC -> when {
                seconds != null -> if (weight != null) "$weight×$seconds" else seconds
                else -> repsLabel(weight, reps)
            }
            Modality.WEIGHTED, Modality.BODYWEIGHT -> repsLabel(weight, reps)
                ?: seconds
        }
    }

    private fun repsLabel(weight: String?, reps: Int?): String? = when {
        weight != null && reps != null -> "$weight×$reps"
        reps != null -> "×$reps"
        weight != null -> weight
        else -> null
    }
}
