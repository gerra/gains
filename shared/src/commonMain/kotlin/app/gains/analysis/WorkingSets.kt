package app.gains.analysis

import app.gains.domain.ExerciseEntry
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType

/**
 * Warm-up inference (parsing rule 11): within one exercise in one session, weighted
 * sets at or above [ratio] × the session's top weight are working sets. Bodyweight,
 * isometric and cardio sets are always working sets.
 */
object WorkingSets {
    const val DEFAULT_RATIO = 0.85

    fun classify(sets: List<SetEntry>, ratio: Double = DEFAULT_RATIO): List<SetEntry> {
        val top = sets.filter { it.type == SetType.WEIGHTED }.mapNotNull { it.weightKg }.maxOrNull()
            ?: return sets.map { it.copy(isWarmup = false) }
        val threshold = top * ratio
        return sets.map { set ->
            val warmup = set.type == SetType.WEIGHTED && (set.weightKg ?: 0.0) < threshold - 1e-9
            set.copy(isWarmup = warmup)
        }
    }

    fun apply(entry: ExerciseEntry, ratio: Double): ExerciseEntry = entry.copy(sets = classify(entry.sets, ratio))

    fun apply(session: Session, ratios: Map<String, Double>): Session = session.copy(
        exercises = session.exercises.map { apply(it, ratios[it.exerciseId] ?: DEFAULT_RATIO) }
    )

    fun apply(sessions: List<Session>, ratios: Map<String, Double>): List<Session> = sessions.map { apply(it, ratios) }
}
