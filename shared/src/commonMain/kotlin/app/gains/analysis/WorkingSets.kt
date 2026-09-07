package app.gains.analysis

import app.gains.domain.ExerciseEntry
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType

/**
 * Warm-up inference (parsing rule 11): within one exercise in one session, weighted
 * sets at or above [ratio] × the session's top weight are working sets. Bodyweight,
 * isometric and cardio sets are always working sets.
 *
 * A set already flagged as a warm-up when it arrives here was marked so explicitly (planned
 * by a program day, or ticked in the editor) and stays one whatever the ratio says; the rule
 * only decides about the rest. Explicit flags are the only ones stored; inferred ones are
 * recomputed on every read so a changed ratio takes effect on old sessions too.
 */
object WorkingSets {
    const val DEFAULT_RATIO = 0.85

    fun classify(sets: List<SetEntry>, ratio: Double = DEFAULT_RATIO): List<SetEntry> {
        val top = sets.filter { it.type == SetType.WEIGHTED && !it.isWarmup }.mapNotNull { it.weightKg }.maxOrNull()
            ?: return sets
        val threshold = top * ratio
        return sets.map { set ->
            if (set.isWarmup) set
            else set.copy(isWarmup = set.type == SetType.WEIGHTED && (set.weightKg ?: 0.0) < threshold - 1e-9)
        }
    }

    fun apply(entry: ExerciseEntry, ratio: Double): ExerciseEntry = entry.copy(sets = classify(entry.sets, ratio))

    fun apply(session: Session, ratios: Map<String, Double>): Session = session.copy(
        exercises = session.exercises.map { apply(it, ratios[it.exerciseId] ?: DEFAULT_RATIO) }
    )

    fun apply(sessions: List<Session>, ratios: Map<String, Double>): List<Session> = sessions.map { apply(it, ratios) }

    /** [session] with every warm-up flag cleared: what an import stores, since its flags were inferred. */
    fun strip(session: Session): Session = session.copy(
        exercises = session.exercises.map { e -> e.copy(sets = e.sets.map { if (it.isWarmup) it.copy(isWarmup = false) else it }) }
    )
}
