package app.gains.program

import app.gains.analysis.TrainingSnapshot
import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.ExerciseSlot
import app.gains.domain.Modality
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.Session
import app.gains.domain.SetsReps
import app.gains.domain.WeightUnit

data class PlannedSet(val weightKg: Double?, val reps: Int?, val seconds: Int?, val isWarmup: Boolean = false)

data class PlannedExercise(
    val exercise: Exercise,
    val slot: ExerciseSlot,
    /** Warm-up sets first (flagged), then the work sets. */
    val sets: List<PlannedSet>,
    /** "5 × 3+", or "6 × 2+" once a stage ladder has moved on: always the stage the sets were built for. */
    val targetLabel: String,
    /** The same stage as numbers, for the screen to put in words. */
    val target: SetsReps,
    val hint: Progression.Hint?,
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
    /**
     * [comebacks] are the lifter's answers after breaks ([Comeback.decode]) and [easing] the one in
     * force today ([Comeback.active]), if any: the loaded lifts then start lighter and climb back (see
     * [suggestion]).
     */
    fun plan(
        program: Program, day: ProgramDay, snapshot: TrainingSnapshot, unit: WeightUnit, options: PlanOptions = PlanOptions(),
        easing: Comeback.Answer? = null, comebacks: List<Comeback.Answer> = listOfNotNull(easing),
    ): DayPlan {
        val planned = day.slots.mapNotNull { slot ->
            val exercise = snapshot.exercisesById[slot.exerciseId] ?: return@mapNotNull null
            val ownHistory = recentSessions(snapshot, slot.exerciseId, Int.MAX_VALUE) { onSameScheme(program, slot, it) }
            val own = ownHistory.firstOrNull()
            var source: Progression.Source? = null
            val s = if (own != null) {
                suggestion(slot, exercise, ownHistory, unit, easing.takeIf { exercise.modality == Modality.WEIGHTED }, comebacks)
            } else {
                val recent = recentSessions(snapshot, slot.exerciseId, Gzclp.RECENT_SESSIONS)
                source = recent.firstOrNull()?.let { if (it.program == null) Progression.Source.FREE_SESSION else Progression.Source.DIFFERENT_SCHEME }
                val start = Progression.start(slot, exercise, recent.map { it.entry(slot.exerciseId) }, unit, source ?: Progression.Source.FREE_SESSION)
                // A slot's first session, borrowing a weight from before the break: that weight eases too.
                val weight = start.weightKg
                val last = recent.firstOrNull()
                if (easing != null && exercise.modality == Modality.WEIGHTED && weight != null && last != null && last.timestamp <= easing.after) {
                    val light = Comeback.weight(weight, Comeback.startFraction(easing.awayDays), Gzclp.increment(slot, unit), unit)
                    start.copy(weightKg = light, hint = Progression.Hint.EaseBack(Progression.lastTimeOf(last.entry(slot.exerciseId), exercise), light, weight, easing.awayDays))
                } else start
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
                exercise, slot, warmups + work, s.target.targetLabel(slot.lastSetAmrap), s.target, s.hint,
                seeded = seeded, tier = tier, source = source.takeIf { seeded },
            )
        }
        return DayPlan(day, planned)
    }

    /**
     * The suggestion for a slot with history of its own, [ownHistory] (newest first), eased after a
     * break when [easing] is given.
     *
     * The weight to come back to is what the rule would have asked for the first session after the
     * break, capped at what was lifted last before it: back to 60 kg, not to the 62.5 kg the rule
     * would have tried next. Its sets and reps (a stage ladder's stage) stay as the rule had them. The
     * first [Comeback.RAMP_SESSIONS] sessions back climb to that weight; then the rule reads the last
     * session as usual.
     *
     * The lighter sessions after every break in [comebacks], other than the very last session, are
     * left out of what the rule reads: they were light on purpose, so a missed rep at 90% does not
     * move a stage ladder on a stage, now or in any later replay.
     */
    private fun suggestion(
        slot: ExerciseSlot, exercise: Exercise, ownHistory: List<Session>, unit: WeightUnit,
        easing: Comeback.Answer?, comebacks: List<Comeback.Answer>,
    ): Progression.Suggestion {
        fun suggest(history: List<Session>) =
            Progression.suggest(slot, exercise, history.first().entry(slot.exerciseId), unit, history.drop(1).asReversed().map { it.entry(slot.exerciseId) })
        val light = comebacks.flatMap { Comeback.lightSessions(ownHistory, slot.exerciseId, it) }.toSet() - ownHistory.first().id
        val history = ownHistory.filter { it.id !in light }
        if (easing == null) return suggest(history)
        val pre = history.filter { it.timestamp <= easing.after }
        if (pre.isEmpty()) return suggest(history)
        val before = suggest(pre)
        val referenceKg = listOfNotNull(Comeback.topKg(pre.first().entry(slot.exerciseId)), before.weightKg).minOrNull()
            ?.takeIf { it > 0.0 } ?: return suggest(history)
        // Counted from the whole history: a light session is still a session back.
        val back = ownHistory.filter { it.timestamp > easing.after }
        val reached = back.any { (Comeback.topKg(it.entry(slot.exerciseId)) ?: 0.0) >= referenceKg }
        val fraction = Comeback.fraction(easing.awayDays, back.size)
        if (fraction == null || reached) return suggest(history)
        val weight = Comeback.weight(referenceKg, fraction, Gzclp.increment(slot, unit), unit)
        val last = Progression.lastTimeOf(ownHistory.first().entry(slot.exerciseId), exercise)
        return before.copy(weightKg = weight, hint = Progression.Hint.EaseBack(last, weight, referenceKg, easing.awayDays))
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
    fun onSameScheme(program: Program, slot: ExerciseSlot, session: Session): Boolean {
        if (session.program?.programId != program.id) return false
        return loggedSlot(listOf(program), session, slot.exerciseId)?.sameScheme(slot) == true
    }

    /** The slot [exerciseId] filled on the program day [session] was logged against, or null for a free session or a day that is gone. */
    fun loggedSlot(programs: List<Program>, session: Session, exerciseId: String): ExerciseSlot? {
        val ref = session.program ?: return null
        val program = programs.firstOrNull { it.id == ref.programId } ?: return null
        return program.day(ref.dayId)?.slots?.firstOrNull { it.exerciseId == exerciseId }
    }
}
