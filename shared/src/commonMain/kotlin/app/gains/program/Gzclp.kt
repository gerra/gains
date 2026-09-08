package app.gains.program

import app.gains.analysis.Epley
import app.gains.domain.Equipment
import app.gains.domain.Exercise
import app.gains.domain.ExerciseSlot
import app.gains.domain.ProgressionRule
import app.gains.domain.SetEntry
import app.gains.domain.Units
import app.gains.domain.WeightUnit
import kotlin.math.floor
import kotlin.math.round

/**
 * Everything tunable about GZCLP-style tiers in one place: what fraction of the lifter's estimated
 * 1RM each tier starts at, the loadable increments, the warm-up scheme, and the rest between sets.
 *
 * A tier is inferred from a slot's scheme rather than its name, so a custom copy of GZCLP (or any
 * program using the same rules) gets the same treatment: a stage ladder whose stages are AMRAP is a
 * T1, a stage ladder of fixed reps is a T2, and a high-rep double progression is a T3.
 */
object Gzclp {
    /** A warm-up set relative to the day's work weight. */
    data class WarmupStep(val fraction: Double, val reps: Int)

    enum class Tier(
        val label: String,
        /** Starting weight as a fraction of the estimated 1RM. Conservative on purpose: GZCLP adds weight every session. */
        val startFraction: Double,
        /** Recommended rest between work sets. */
        val restSeconds: IntRange,
        /** Warm-up sets after the empty bar, as fractions of the work weight. */
        val warmups: List<WarmupStep>,
    ) {
        T1("T1", 0.85, 180..300, listOf(WarmupStep(0.4, 5), WarmupStep(0.6, 3), WarmupStep(0.8, 2))),
        T2("T2", 0.65, 120..180, listOf(WarmupStep(0.6, 5))),
        T3("T3", 0.525, 60..90, emptyList());

        /** "3–5 min" / "60–90 s". */
        val restLabel: String get() = restLabel(restSeconds)

        /** What the rest timer counts down from: the short end of the range. */
        val restTimerSeconds: Int get() = restSeconds.first
    }

    /** Default barbell weight; a setting, see [app.gains.data.SettingsRepository.observeBarWeightKg]. */
    const val DEFAULT_BAR_KG = 20.0

    /** Reps on the empty bar before any loaded warm-up. */
    const val EMPTY_BAR_REPS = 10

    /** Free sessions of the exercise consulted when estimating the 1RM. */
    const val RECENT_SESSIONS = 5

    /** A double progression starting at this many reps or more is a T3. */
    const val T3_MIN_REPS = 12

    /** Smallest loadable step when the slot's rule has none: 2.5 kg / 5 lbs. Squat and deadlift rules carry 5 kg / 10 lbs. */
    const val DEFAULT_INCREMENT_KG = 2.5
    const val DEFAULT_INCREMENT_LBS = 5.0

    /** One light set for T3 cable and machine lifts, which have no bar to warm up with. */
    val T3_CABLE_MACHINE_WARMUP: List<WarmupStep> = listOf(WarmupStep(0.6, 8))

    /** What the rest timer counts down from on a slot that is not a tier. */
    const val DEFAULT_REST_SECONDS = 90

    /** Rest after a warm-up set. */
    val WARMUP_REST: IntRange = 30..60
    val WARMUP_REST_LABEL: String = restLabel(WARMUP_REST) + " or as needed"

    fun tierOf(slot: ExerciseSlot): Tier? = tierOf(slot.progression)

    fun tierOf(rule: ProgressionRule): Tier? = when (rule) {
        is ProgressionRule.StageLadder -> if (rule.stages.firstOrNull()?.reps is app.gains.domain.RepTarget.Amrap) Tier.T1 else Tier.T2
        is ProgressionRule.DoubleProgression -> if (rule.min >= T3_MIN_REPS) Tier.T3 else null
        else -> null
    }

    /** The loadable increment for a slot in the display unit: its rule's step, else the default. */
    fun increment(slot: ExerciseSlot, unit: WeightUnit): Double =
        slot.progression.step(unit)?.takeIf { it > 0.0 } ?: defaultIncrement(unit)

    fun defaultIncrement(unit: WeightUnit): Double = if (unit == WeightUnit.KG) DEFAULT_INCREMENT_KG else DEFAULT_INCREMENT_LBS

    /** Where a tier's first session should start, and why. */
    data class Estimate(
        /** Best Epley estimate across the sets considered. */
        val e1rmKg: Double,
        /** Starting weight, or null when it rounds to nothing (a bodyweight lift with no added load worth suggesting). */
        val startKg: Double?,
        /** A weight the lifter already failed for the tier's reps, when it pulled the start down. */
        val cappedBelowKg: Double?,
    )

    /**
     * Starting weight for [tier] from what the lifter has actually done. [sets] are the working sets
     * of recent sessions of the exercise; for a bodyweight lift they carry the added load, so the
     * estimate is on added load only. The best set's Epley 1RM is taken across all of them, the tier's
     * fraction applied, and the result rounded down to the loadable [incrementDisplay] (in [unit]).
     *
     * Never at or above a weight the lifter did for fewer than [targetReps] in those sessions:
     * 50 kg × 6, 8, 9 cannot become "3 × 10 at 50 kg", whatever the estimate says.
     */
    fun estimate(tier: Tier, sets: List<SetEntry>, targetReps: Int, incrementDisplay: Double, unit: WeightUnit): Estimate? {
        val usable = sets.filter { (it.weightKg ?: 0.0) > 0.0 && (it.reps ?: 0) > 0 }
        if (usable.isEmpty()) return null
        val e1rm = usable.maxOf { Epley.e1rm(it.weightKg!!, it.reps!!) }
        var start = roundDown(Units.display(e1rm, unit) * tier.startFraction, incrementDisplay)
        val failed = usable.filter { it.reps!! < targetReps }.minOfOrNull { it.weightKg!! }
        var capped: Double? = null
        if (failed != null) {
            val ceiling = roundDown(Units.display(failed, unit) - incrementDisplay, incrementDisplay)
            if (ceiling < start) { start = ceiling; capped = failed }
        }
        start = start.coerceAtLeast(0.0)
        val startKg = if (start <= 0.0) null else Units.roundToQuarter(Units.fromDisplay(start, unit))
        return Estimate(e1rm, startKg, capped)
    }

    data class WarmupSet(val weightKg: Double, val reps: Int)

    /**
     * Warm-up sets before work at [workKg]. Barbell lifts open with the empty bar ([barKg]); every
     * later set is a fraction of the work weight rounded to the increment, skipping any that lands on
     * the bar, repeats the previous set or reaches the work weight. T3s get nothing unless the lift is
     * on a cable or machine, which gets one light set.
     */
    fun warmups(tier: Tier, exercise: Exercise, workKg: Double, barKg: Double, incrementDisplay: Double, unit: WeightUnit): List<WarmupSet> {
        if (workKg <= 0.0) return emptyList()
        val barbell = Equipment.BARBELL in exercise.equipment && !exercise.isDumbbell
        val steps = when {
            tier != Tier.T3 -> tier.warmups
            exercise.equipment.any { it == Equipment.CABLE || it == Equipment.MACHINE } -> T3_CABLE_MACHINE_WARMUP
            else -> emptyList()
        }
        val out = ArrayList<WarmupSet>()
        val floorKg = if (barbell) barKg else 0.0
        if (barbell && tier != Tier.T3 && barKg < workKg) out += WarmupSet(barKg, EMPTY_BAR_REPS)
        for (step in steps) {
            val display = roundNearest(Units.display(workKg, unit) * step.fraction, incrementDisplay)
            val kg = Units.roundToQuarter(Units.fromDisplay(display, unit))
            if (kg <= floorKg || kg >= workKg) continue
            if (out.lastOrNull()?.weightKg == kg) continue
            out += WarmupSet(kg, step.reps)
        }
        return out
    }

    /** "3–5 min" when the range sits on whole minutes, else "60–90 s". */
    fun restLabel(range: IntRange): String =
        if (range.first % 60 == 0 && range.last % 60 == 0 && range.first >= 60) "${range.first / 60}–${range.last / 60} min"
        else "${range.first}–${range.last} s"

    /** Round down to a multiple of [step], tolerating floating-point noise just under a boundary. */
    fun roundDown(value: Double, step: Double): Double = floor(value / step + 1e-9) * step

    fun roundNearest(value: Double, step: Double): Double = round(value / step) * step
}
