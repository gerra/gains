package app.gains.program

import app.gains.analysis.Format
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
    data class Suggestion(
        val weightKg: Double?,
        val sets: Int,
        val reps: Int,
        /** One line for the editor: "Last: 60 kg × 5,5,5 → try 62.5 kg". */
        val hint: String?,
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
        val lastLabel = lastLabel(loaded, lastWeight, reps, unit)
        if (loaded && lastWeight == null) return fallback.copy(hint = lastLabel)

        fun bump(weight: Double?, rule: ProgressionRule): Double? {
            if (weight == null) return null
            val step = rule.step(unit) ?: return weight
            return Units.roundToQuarter(Units.fromDisplay(Units.display(weight, unit) + step, unit))
        }
        fun w(kg: Double) = Format.weight(kg, unit)

        return when (val rule = slot.progression) {
            ProgressionRule.None -> Suggestion(lastWeight.takeIf { loaded }, slot.sets, slot.reps.prefillReps, lastLabel, slot.target)

            is ProgressionRule.Linear -> {
                val hit = sets.size >= slot.sets && reps.all { it >= slot.reps.successReps }
                if (!loaded || lastWeight == null) {
                    Suggestion(null, slot.sets, slot.reps.prefillReps, lastLabel, slot.target)
                } else if (hit) {
                    val next = bump(lastWeight, rule)!!
                    Suggestion(next, slot.sets, slot.reps.prefillReps, "$lastLabel → try ${w(next)}", slot.target)
                } else {
                    Suggestion(lastWeight, slot.sets, slot.reps.prefillReps, "$lastLabel → repeat ${w(lastWeight)}", slot.target)
                }
            }

            is ProgressionRule.DoubleProgression -> {
                val hit = sets.size >= slot.sets && reps.all { it >= rule.max }
                val moveUp = rule.stepKg <= 0.0
                when {
                    hit && moveUp -> Suggestion(lastWeight.takeIf { loaded }, slot.sets, rule.min, "$lastLabel → all sets at ${rule.max}: move to the next progression", slot.target)
                    hit && loaded && lastWeight != null -> {
                        val next = bump(lastWeight, rule)!!
                        Suggestion(next, slot.sets, rule.min, "$lastLabel → try ${w(next)} × ${rule.min}", slot.target)
                    }
                    else -> {
                        val target = (reps.minOrNull()!! + 1).coerceIn(rule.min, rule.max)
                        val weightText = if (loaded && lastWeight != null) "${w(lastWeight)} × " else ""
                        Suggestion(lastWeight.takeIf { loaded }, slot.sets, target, "$lastLabel → $weightText$target", slot.target)
                    }
                }
            }

            is ProgressionRule.StageLadder -> {
                val stages = rule.stages
                val stageIndex = inferStage(stages, sets.size, reps.firstOrNull() ?: 0)
                val stage = stages[stageIndex]
                val success = sets.size >= stage.sets && reps.all { it >= stage.reps.successReps }
                when {
                    !loaded || lastWeight == null -> Suggestion(null, stage.sets, stage.reps.prefillReps, lastLabel, stage)
                    success -> {
                        val next = bump(lastWeight, rule)!!
                        Suggestion(next, stage.sets, stage.reps.prefillReps, "$lastLabel → try ${w(next)}, ${stage.label}", stage)
                    }
                    stageIndex + 1 < stages.size -> {
                        val nextStage = stages[stageIndex + 1]
                        Suggestion(lastWeight, nextStage.sets, nextStage.reps.prefillReps, "$lastLabel → missed reps: ${nextStage.label} at ${w(lastWeight)}", nextStage)
                    }
                    else -> {
                        val reset = resetWeight(lastWeight, rule, unit)
                        val first = stages.first()
                        Suggestion(reset, first.sets, first.reps.prefillReps, "$lastLabel → cycle done: reset to ${w(reset)} and restart ${first.label}", first)
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
        val lastLabel = lastLabel(loaded, lastWeight, reps, unit, addedLoad = bodyweight)

        val tier = Gzclp.tierOf(slot)
        if (tier != null && (loaded || bodyweight)) {
            val pool = recent.flatMap { it.workingSets.ifEmpty { it.sets } }
            val estimate = Gzclp.estimate(tier, pool, slot.reps.prefillReps, Gzclp.increment(slot, unit), unit)
            if (estimate != null) {
                val plus = if (bodyweight) "+" else ""
                val startText = estimate.startKg?.let { plus + Format.weight(it, unit) }
                    ?: if (bodyweight) "with no added load" else "as light as you can load"
                val cap = estimate.cappedBelowKg?.let { " (kept under $plus${Format.weight(it, unit)})" } ?: ""
                val hint = "$lastLabel → est. 1RM ~$plus${Format.weight(estimate.e1rmKg, unit, 0)} → ${tier.label} start $startText$cap"
                return Suggestion(estimate.startKg, slot.sets, slot.reps.prefillReps, hint, slot.target)
            }
        }

        val weight = lastWeight.takeIf { loaded }
        val at = weight?.let { " at ${Format.weight(it, unit)}" } ?: ""
        val hint = "$lastLabel (${source.label}) → start ${slot.target.label}$at"
        return Suggestion(weight, slot.sets, slot.reps.prefillReps, hint, slot.target)
    }

    /** "Last: 60 kg × 5,5,5", "Last: +10 kg × 8,8,8" for a bodyweight lift with added load, or "Last: 8,8,8". */
    private fun lastLabel(loaded: Boolean, lastWeight: Double?, reps: List<Int>, unit: WeightUnit, addedLoad: Boolean = false): String {
        val weight = when {
            lastWeight == null -> ""
            loaded -> Format.weight(lastWeight, unit) + " × "
            addedLoad && lastWeight > 0.0 -> "+" + Format.weight(lastWeight, unit) + " × "
            else -> ""
        }
        return "Last: $weight" + reps.joinToString(",")
    }

    /**
     * The rule in plain words for the program overview: what happens after a good session, a missed
     * one, and where the ladder ends. Null for [ProgressionRule.None], which has nothing to say.
     */
    fun describe(rule: ProgressionRule, unit: WeightUnit): String? {
        val step = rule.step(unit)?.takeIf { it > 0.0 }?.let { "${Format.number(it, 2)} ${unit.label}" }
        return when (rule) {
            ProgressionRule.None -> null
            is ProgressionRule.Linear ->
                "Add $step every session you hit every set. Miss the reps and the weight repeats."
            is ProgressionRule.DoubleProgression -> {
                val then = if (step == null) "move on to the harder variation" else "add $step and drop back to ${rule.min}"
                "Reps climb from ${rule.min} to ${rule.max} at one weight. Once every set reaches ${rule.max}, $then."
            }
            is ProgressionRule.StageLadder -> {
                val stages = rule.stages.joinToString(" → ") { it.label }
                val first = rule.stages.first().label
                "Stages: $stages. Hit the reps: add $step and stay on the stage. Miss: next stage at the same weight. Miss the last stage: drop about 10% and start over at $first."
            }
        }
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
