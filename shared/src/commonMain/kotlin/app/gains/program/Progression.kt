package app.gains.program

import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.ExerciseSlot
import app.gains.domain.Modality
import app.gains.domain.ProgressionRule
import app.gains.domain.SetsReps
import app.gains.domain.Units
import app.gains.domain.WeightUnit

/**
 * Turns "what you did last time" plus the slot's rule into "what to load today".
 * Pure: the caller supplies the most recent entry for the exercise (from any session).
 */
object Progression {
    /**
     * What the lifter did last time, as every hint opens: "Last: 60 kg × 5,5,5". [weightKg] is the
     * weight to show, or null when the lift is not loaded; [addedLoad] marks it as a plus over
     * bodyweight ("+10 kg"). [reps] are per set, in order.
     */
    data class LastTime(val weightKg: Double?, val reps: List<Int>, val addedLoad: Boolean = false)

    /** One line for the editor, worded by the UI: "Last: 60 kg × 5,5,5 → try 62.5 kg". */
    sealed interface Hint {
        val last: LastTime

        /** Nothing to suggest beyond what was done. */
        data class LastOnly(override val last: LastTime) : Hint
        data class Try(override val last: LastTime, val weightKg: Double) : Hint
        data class Repeat(override val last: LastTime, val weightKg: Double) : Hint
        /** Every set reached [maxReps] on a bodyweight double progression: on to the harder variation. */
        data class MoveOn(override val last: LastTime, val maxReps: Int) : Hint
        data class TryReps(override val last: LastTime, val weightKg: Double, val reps: Int) : Hint
        /** Aim for [reps] this time, at [weightKg] when loaded. */
        data class Target(override val last: LastTime, val weightKg: Double?, val reps: Int) : Hint
        data class TryStage(override val last: LastTime, val weightKg: Double, val stage: SetsReps) : Hint
        data class Missed(override val last: LastTime, val stage: SetsReps, val weightKg: Double) : Hint
        data class Reset(override val last: LastTime, val weightKg: Double, val stage: SetsReps) : Hint
        /** A tier's first session: [startKg] from the Epley estimate [e1rmKg], or null when nothing is worth loading; [cappedBelowKg] when a failed weight pulled it down. */
        data class Estimate(override val last: LastTime, val e1rmKg: Double, val addedLoad: Boolean, val tier: Gzclp.Tier, val startKg: Double?, val cappedBelowKg: Double?) : Hint
        /** A slot's first session outside a tier: start [target] at [weightKg], borrowed from [source]. */
        data class Start(override val last: LastTime, val source: Source, val target: SetsReps, val weightKg: Double?) : Hint
    }

    data class Suggestion(
        val weightKg: Double?,
        val sets: Int,
        val reps: Int,
        val hint: Hint?,
        /**
         * What today's sets are measured against. For a stage ladder this is the stage the lifter is
         * on, which the slot's own sets × reps (the first stage) stops describing after a missed session.
         */
        val target: SetsReps,
    )

    /**
     * What to load today given [last], the most recent entry logged against this same slot (or one
     * with the [ExerciseSlot.sameScheme]). The rule reads it as a success or a failure of the slot's
     * prescription, so an entry from a free session or a different scheme must not be passed here:
     * use [start] for those.
     */
    fun suggest(slot: ExerciseSlot, exercise: Exercise, last: ExerciseEntry?, unit: WeightUnit): Suggestion {
        val fallback = Suggestion(null, slot.sets, slot.reps.prefillReps, null, slot.target)
        val sets = last?.workingSets?.ifEmpty { last.sets }.orEmpty()
        if (sets.isEmpty()) return fallback
        val loaded = exercise.modality == Modality.WEIGHTED
        val lastWeight = sets.mapNotNull { it.weightKg }.maxOrNull()
        val reps = sets.map { it.reps ?: it.seconds ?: 0 }
        val lastTime = lastTime(loaded, lastWeight, reps)
        if (loaded && lastWeight == null) return fallback.copy(hint = Hint.LastOnly(lastTime))

        fun bump(weight: Double?, rule: ProgressionRule): Double? {
            if (weight == null) return null
            val step = rule.step(unit) ?: return weight
            return Units.roundToQuarter(Units.fromDisplay(Units.display(weight, unit) + step, unit))
        }
        return when (val rule = slot.progression) {
            ProgressionRule.None -> Suggestion(lastWeight.takeIf { loaded }, slot.sets, slot.reps.prefillReps, Hint.LastOnly(lastTime), slot.target)

            is ProgressionRule.Linear -> {
                val hit = sets.size >= slot.sets && reps.all { it >= slot.reps.successReps }
                if (!loaded || lastWeight == null) {
                    Suggestion(null, slot.sets, slot.reps.prefillReps, Hint.LastOnly(lastTime), slot.target)
                } else if (hit) {
                    val next = bump(lastWeight, rule)!!
                    Suggestion(next, slot.sets, slot.reps.prefillReps, Hint.Try(lastTime, next), slot.target)
                } else {
                    Suggestion(lastWeight, slot.sets, slot.reps.prefillReps, Hint.Repeat(lastTime, lastWeight), slot.target)
                }
            }

            is ProgressionRule.DoubleProgression -> {
                val hit = sets.size >= slot.sets && reps.all { it >= rule.max }
                val moveUp = rule.stepKg <= 0.0
                when {
                    hit && moveUp -> Suggestion(lastWeight.takeIf { loaded }, slot.sets, rule.min, Hint.MoveOn(lastTime, rule.max), slot.target)
                    hit && loaded && lastWeight != null -> {
                        val next = bump(lastWeight, rule)!!
                        Suggestion(next, slot.sets, rule.min, Hint.TryReps(lastTime, next, rule.min), slot.target)
                    }
                    else -> {
                        val target = (reps.minOrNull()!! + 1).coerceIn(rule.min, rule.max)
                        Suggestion(lastWeight.takeIf { loaded }, slot.sets, target, Hint.Target(lastTime, lastWeight.takeIf { loaded }, target), slot.target)
                    }
                }
            }

            is ProgressionRule.StageLadder -> {
                val stages = rule.stages
                val stageIndex = inferStage(stages, sets.size, reps.firstOrNull() ?: 0)
                val stage = stages[stageIndex]
                val success = sets.size >= stage.sets && reps.all { it >= stage.reps.successReps }
                when {
                    !loaded || lastWeight == null -> Suggestion(null, stage.sets, stage.reps.prefillReps, Hint.LastOnly(lastTime), stage)
                    success -> {
                        val next = bump(lastWeight, rule)!!
                        Suggestion(next, stage.sets, stage.reps.prefillReps, Hint.TryStage(lastTime, next, stage), stage)
                    }
                    stageIndex + 1 < stages.size -> {
                        val nextStage = stages[stageIndex + 1]
                        Suggestion(lastWeight, nextStage.sets, nextStage.reps.prefillReps, Hint.Missed(lastTime, nextStage, lastWeight), nextStage)
                    }
                    else -> {
                        val reset = resetWeight(lastWeight, rule, unit)
                        val first = stages.first()
                        Suggestion(reset, first.sets, first.reps.prefillReps, Hint.Reset(lastTime, reset, first), first)
                    }
                }
            }
        }
    }

    /** Where a borrowed starting weight came from, for the hint. */
    enum class Source(val label: String) {
        /** A workout logged without a program day. */
        FREE_SESSION("free session"),
        /** A session of this program where the exercise sits in a slot with another scheme, e.g. a GZCLP T2 squat before a T1 day. */
        DIFFERENT_SCHEME("different scheme"),
    }

    /**
     * The first session of a slot: nothing has been logged against this scheme yet, so there is no
     * success or failure for the rule to act on. Prefill the slot exactly as written (a stage ladder
     * starts on its first stage) and pick a starting weight from [recent], the most recent entries for
     * the exercise from anywhere (free sessions, or another slot of the program), newest first.
     *
     * For a GZCLP tier the weight is estimated rather than copied: the best set across those entries
     * gives an Epley 1RM, the tier takes its fraction of that, rounded down to the loadable increment
     * and never at or above a weight the lifter already failed for the tier's reps (see
     * [Gzclp.estimate]). 50 kg × 6, 8, 9 becomes "T2 start 40 kg", not "3 × 10 at 50 kg". Slots
     * with no tier keep the last weight as before. The hint spells out the reasoning.
     */
    fun start(slot: ExerciseSlot, exercise: Exercise, recent: List<ExerciseEntry>, unit: WeightUnit, source: Source = Source.FREE_SESSION): Suggestion {
        val fallback = Suggestion(null, slot.sets, slot.reps.prefillReps, null, slot.target)
        val last = recent.firstOrNull() ?: return fallback
        val sets = last.workingSets.ifEmpty { last.sets }
        if (sets.isEmpty()) return fallback
        val loaded = exercise.modality == Modality.WEIGHTED
        val bodyweight = exercise.modality == Modality.BODYWEIGHT
        val lastWeight = sets.mapNotNull { it.weightKg }.maxOrNull()
        val reps = sets.map { it.reps ?: it.seconds ?: 0 }
        val lastTime = lastTime(loaded, lastWeight, reps, addedLoad = bodyweight)

        val tier = Gzclp.tierOf(slot)
        if (tier != null && (loaded || bodyweight)) {
            val pool = recent.flatMap { it.workingSets.ifEmpty { it.sets } }
            val estimate = Gzclp.estimate(tier, pool, slot.reps.prefillReps, Gzclp.increment(slot, unit), unit)
            if (estimate != null) {
                val hint = Hint.Estimate(lastTime, estimate.e1rmKg, bodyweight, tier, estimate.startKg, estimate.cappedBelowKg)
                return Suggestion(estimate.startKg, slot.sets, slot.reps.prefillReps, hint, slot.target)
            }
        }

        val weight = lastWeight.takeIf { loaded }
        return Suggestion(weight, slot.sets, slot.reps.prefillReps, Hint.Start(lastTime, source, slot.target, weight), slot.target)
    }

    /** "60 kg × 5,5,5" for a loaded lift, "+10 kg × 8,8,8" for a bodyweight lift with added load, "8,8,8" otherwise. */
    private fun lastTime(loaded: Boolean, lastWeight: Double?, reps: List<Int>, addedLoad: Boolean = false): LastTime = when {
        lastWeight == null -> LastTime(null, reps)
        loaded -> LastTime(lastWeight, reps)
        addedLoad && lastWeight > 0.0 -> LastTime(lastWeight, reps, addedLoad = true)
        else -> LastTime(null, reps)
    }

    /** The stage whose set count matches; among several, the one whose reps are closest to the first (non-AMRAP) set. */
    internal fun inferStage(stages: List<SetsReps>, setCount: Int, firstSetReps: Int): Int {
        val bySets = stages.indices.filter { stages[it].sets == setCount }
        return when (bySets.size) {
            0 -> 0
            1 -> bySets.first()
            else -> bySets.minBy { kotlin.math.abs(stages[it].reps.prefillReps - firstSetReps) }
        }
    }

    /** ~90% of the last weight, rounded down to the rule's step in the display unit. */
    private fun resetWeight(lastKg: Double, rule: ProgressionRule.StageLadder, unit: WeightUnit): Double {
        val step = rule.step(unit) ?: return lastKg
        val display = Units.display(lastKg, unit) * 0.9
        val rounded = kotlin.math.floor(display / step) * step
        return Units.roundToQuarter(Units.fromDisplay(rounded, unit))
    }
}
