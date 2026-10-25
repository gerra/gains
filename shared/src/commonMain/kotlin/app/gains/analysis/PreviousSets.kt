package app.gains.analysis

import app.gains.domain.ExerciseEntry
import app.gains.domain.Modality
import app.gains.domain.Session
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
        lastSession(snapshot, exerciseId, before, excludeSessionId)?.exercises?.first { it.exerciseId == exerciseId }

    /**
     * The session [lastEntry] reads, narrowed by [filter]: a GZCLP card passes one that keeps only the
     * sessions of its own slot's scheme, so a T1 squat compares with the last T1 squat rather than
     * with Monday's T2.
     */
    fun lastSession(
        snapshot: TrainingSnapshot, exerciseId: String, before: LocalDateTime? = null, excludeSessionId: String? = null,
        filter: (Session) -> Boolean = { true },
    ): Session? =
        snapshot.sessions.asSequence()
            .filter { s -> s.id != excludeSessionId && (before == null || s.timestamp < before) }
            .filter { s -> s.exercises.any { it.exerciseId == exerciseId && it.workingSets.isNotEmpty() } }
            .filter(filter)
            .maxByOrNull { it.timestamp }

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

    /** How a set compares with the same set last time: the dot beside the PREV column once it is ticked. */
    enum class Trend { UP, SAME, DOWN }

    /**
     * Whether [now] did more than [then], the same, or less. A loaded set compares by its Epley
     * estimate, so 60×8 beats 55×10; a bodyweight set or a hold by its reps or seconds and its added
     * load, and only when one did not go up while the other went down (10 kg × 5 against × 10 has no
     * verdict). Null too for cardio, a warm-up, or when either set lacks what the comparison needs.
     */
    fun trend(now: SetEntry, then: SetEntry, modality: Modality): Trend? {
        if (now.isWarmup || then.isWarmup) return null
        fun Double?.load() = this?.takeIf { it > 0 } ?: 0.0
        return when (modality) {
            Modality.WEIGHTED -> {
                val nowReps = now.reps?.takeIf { it > 0 } ?: return null
                val thenReps = then.reps?.takeIf { it > 0 } ?: return null
                if (now.weightKg.load() > 0 && then.weightKg.load() > 0) {
                    sign(Epley.e1rm(now.weightKg.load(), nowReps) - Epley.e1rm(then.weightKg.load(), thenReps))
                } else dominance(now.weightKg.load() - then.weightKg.load(), (nowReps - thenReps).toDouble())
            }
            Modality.BODYWEIGHT -> {
                val nowReps = now.reps?.takeIf { it > 0 } ?: return null
                val thenReps = then.reps?.takeIf { it > 0 } ?: return null
                dominance(now.weightKg.load() - then.weightKg.load(), (nowReps - thenReps).toDouble())
            }
            Modality.ISOMETRIC -> {
                val nowSeconds = now.seconds?.takeIf { it > 0 } ?: return null
                val thenSeconds = then.seconds?.takeIf { it > 0 } ?: return null
                dominance(now.weightKg.load() - then.weightKg.load(), (nowSeconds - thenSeconds).toDouble())
            }
            Modality.CARDIO -> null
        }
    }

    private const val EPSILON = 0.01

    private fun sign(delta: Double): Trend = when {
        delta > EPSILON -> Trend.UP
        delta < -EPSILON -> Trend.DOWN
        else -> Trend.SAME
    }

    /** Up when neither part went down and one went up, down the other way round, null when they disagree. */
    private fun dominance(load: Double, amount: Double): Trend? {
        val a = sign(load)
        val b = sign(amount)
        return when {
            a == b -> a
            a == Trend.SAME -> b
            b == Trend.SAME -> a
            else -> null
        }
    }

    private fun repsLabel(weight: String?, reps: Int?): String? = when {
        weight != null && reps != null -> "$weight×$reps"
        reps != null -> "×$reps"
        weight != null -> weight
        else -> null
    }
}
