package app.gains.analysis

import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.Modality
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import app.gains.domain.WeightUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

object Epley {
    /** Estimated one-rep max. A single is its own 1RM. */
    fun e1rm(weightKg: Double, reps: Int): Double = if (reps <= 1) weightKg else weightKg * (1 + reps / 30.0)
}

/** A performance number comparable across sessions; what "better" means depends on the modality. */
data class Performance(
    val value: Double,
    val set: SetEntry,
) {
    /** "60 kg × 8" / "12 reps" / "1:30 min" / "6.44 km". */
    fun describe(modality: Modality, unit: WeightUnit): String = when (modality) {
        Modality.WEIGHTED -> if (set.weightKg != null && set.reps != null) Format.set(set.weightKg, set.reps, unit) else describeAny(unit)
        Modality.BODYWEIGHT -> if (set.weightKg != null && set.reps != null) "+" + Format.set(set.weightKg, set.reps, unit) else "${set.reps ?: 0} reps"
        Modality.ISOMETRIC -> Format.seconds(set.seconds ?: 0)
        Modality.CARDIO -> Format.km(set.distanceKm ?: 0.0)
    }

    private fun describeAny(unit: WeightUnit): String = when {
        set.reps != null && set.weightKg != null -> Format.set(set.weightKg, set.reps, unit)
        set.reps != null -> "${set.reps} reps"
        set.seconds != null -> Format.seconds(set.seconds)
        set.distanceKm != null -> Format.km(set.distanceKm)
        else -> "-"
    }
}

data class ExerciseSessionPoint(
    val sessionId: String,
    val timestamp: LocalDateTime,
    val date: LocalDate,
    /** Heaviest working set weight (weighted sets only). */
    val topSetWeightKg: Double?,
    /** Working set with the highest estimated 1RM. */
    val bestE1rm: Performance?,
    /** Highest single-set weight × reps. */
    val bestSetVolumeKg: Double?,
    /** Σ weight × reps over working sets. */
    val totalVolumeKg: Double,
    val workingSetCount: Int,
    val setCount: Int,
    /** Modality-specific best performance for the session. */
    val best: Performance?,
    val note: String?,
)

object ExerciseAnalysis {
    fun metricLabel(modality: Modality): String = when (modality) {
        Modality.WEIGHTED -> "e1RM"
        Modality.BODYWEIGHT -> "reps"
        Modality.ISOMETRIC -> "hold"
        Modality.CARDIO -> "distance"
    }

    fun history(sessions: List<Session>, exercise: Exercise): List<ExerciseSessionPoint> =
        sessions.sortedBy { it.timestamp }.mapNotNull { session ->
            val entry = session.exercises.firstOrNull { it.exerciseId == exercise.id } ?: return@mapNotNull null
            point(session, entry, exercise.modality)
        }

    fun point(session: Session, entry: ExerciseEntry, modality: Modality): ExerciseSessionPoint {
        val working = entry.workingSets.ifEmpty { entry.sets }
        val weighted = working.filter { it.type == SetType.WEIGHTED && it.weightKg != null && it.reps != null }
        val bestE1rm = weighted.map { Performance(Epley.e1rm(it.weightKg!!, it.reps!!), it) }.maxByOrNull { it.value }
        return ExerciseSessionPoint(
            sessionId = session.id,
            timestamp = session.timestamp,
            date = session.date,
            topSetWeightKg = weighted.maxOfOrNull { it.weightKg!! },
            bestE1rm = bestE1rm,
            bestSetVolumeKg = weighted.maxOfOrNull { it.volumeKg },
            totalVolumeKg = weighted.sumOf { it.volumeKg },
            workingSetCount = working.size,
            setCount = entry.sets.size,
            best = best(working, modality),
            note = entry.note,
        )
    }

    /** The modality-appropriate best set: e1RM, reps, seconds or distance. */
    fun best(sets: List<SetEntry>, modality: Modality): Performance? = when (modality) {
        Modality.WEIGHTED -> sets.filter { it.weightKg != null && it.reps != null }
            .map { Performance(Epley.e1rm(it.weightKg!!, it.reps!!), it) }.maxByOrNull { it.value }
            ?: sets.filter { it.reps != null }.map { Performance(it.reps!!.toDouble(), it) }.maxByOrNull { it.value }
        Modality.BODYWEIGHT -> sets.filter { it.reps != null }
            // Added load counts for a little: 10 reps with +10 kg beats 10 reps clean.
            .map { Performance(it.reps!! * (1 + (it.weightKg ?: 0.0) / 100.0), it) }.maxByOrNull { it.value }
        Modality.ISOMETRIC -> sets.filter { it.seconds != null }.map { Performance(it.seconds!!.toDouble(), it) }.maxByOrNull { it.value }
        Modality.CARDIO -> sets.filter { it.distanceKm != null }.map { Performance(it.distanceKm!!, it) }.maxByOrNull { it.value }
    }

    /** Value of a modality's metric for chart labelling. */
    fun formatMetric(value: Double, modality: Modality, unit: WeightUnit): String = when (modality) {
        Modality.WEIGHTED -> Format.weight(value, unit, 1)
        Modality.BODYWEIGHT -> Format.number(value, 0) + " reps"
        Modality.ISOMETRIC -> Format.seconds(value.toInt())
        Modality.CARDIO -> Format.km(value)
    }
}

/** Summary shown at the top of the exercise detail screen. */
data class ExerciseSummary(
    val allTimeBest: ExerciseSessionPoint?,
    val currentBest: ExerciseSessionPoint?,
    /** Fraction below the all-time best (0 when at or above it). */
    val gapFraction: Double?,
    val sessionCount: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
) {
    companion object {
        fun of(history: List<ExerciseSessionPoint>, today: LocalDate, currentWindowDays: Int = 30): ExerciseSummary {
            val withBest = history.filter { it.best != null }
            val allTime = withBest.maxByOrNull { it.best!!.value }
            val cutoff = Dates.run { today.minusDays(currentWindowDays) }
            val current = withBest.filter { it.date >= cutoff }.maxByOrNull { it.best!!.value }
                ?: withBest.lastOrNull()
            val gap = if (allTime != null && current != null && allTime.best!!.value > 0) {
                ((allTime.best.value - current.best!!.value) / allTime.best.value).coerceAtLeast(0.0)
            } else null
            return ExerciseSummary(allTime, current, gap, history.size, history.firstOrNull()?.date, history.lastOrNull()?.date)
        }
    }
}
