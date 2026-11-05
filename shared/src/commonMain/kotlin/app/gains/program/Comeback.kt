package app.gains.program

import app.gains.analysis.Dates
import app.gains.analysis.TrainingSnapshot
import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.Modality
import app.gains.domain.Session
import app.gains.domain.Units
import app.gains.domain.WeightUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.math.floor

/**
 * Coming back after a break: two weeks off sick, a holiday. The progression rules only read the
 * last session, so without this the first workout back would ask for last time's weight plus a step.
 *
 * After [GAP_DAYS] without a workout the editor offers to ease back in. If the lifter takes it, each
 * loaded lift starts lighter, at [startFraction] of where it was before the break (90% after two
 * weeks), and climbs back over [RAMP_SESSIONS] sessions of that lift, the last one at the weight from
 * before the break. Then the slot's rule takes over as if nothing happened. The lighter sessions are
 * not read as missed reps: a stage ladder stays on the stage it was on before the break.
 *
 * Everything here is worked out from the history and the one stored [Answer], so nothing has to be
 * written per lift and another device that syncs the answer plans the same workout.
 */
object Comeback {
    /** Days since the last workout after which the editor asks. A week off is an ordinary week. */
    const val GAP_DAYS = 10

    /** Sessions of a lift from the first one back to the one at the weight from before the break. */
    const val RAMP_SESSIONS = 3

    /** Days after the answer that it still eases a lift not yet back to its weight; a lift left this long is a break of its own. */
    const val LASTS_DAYS = 42

    /**
     * The lifter's answer to the offer after the break that followed the workout at [after]: [easing]
     * when they chose to ease back in, false for "pick up where I left off", which is stored too so the
     * offer is not made again. [awayDays] is how long the break was and [on] the day they answered.
     */
    data class Answer(val after: LocalDateTime, val awayDays: Int, val on: LocalDate, val easing: Boolean) {
        fun encode(): String = "$after|$awayDays|$on|${if (easing) 1 else 0}"

        companion object {
            fun decode(raw: String?): Answer? {
                val parts = raw?.split('|')?.takeIf { it.size == 4 } ?: return null
                return runCatching {
                    Answer(LocalDateTime.parse(parts[0]), parts[1].toInt(), LocalDate.parse(parts[2]), parts[3] == "1")
                }.getOrNull()
            }
        }
    }

    /** Answers kept: one per break, and the oldest stop mattering once their lifts have moved on. */
    const val KEPT = 20

    /**
     * Every answer is kept, not just the latest: a stage ladder replays its whole history, and the
     * lighter sessions after any break eased back in from must stay out of that replay (see
     * [DayPlanner.plan]). One answer per line, oldest first.
     */
    fun encode(answers: List<Answer>): String = answers.joinToString("\n") { it.encode() }
    fun decode(raw: String?): List<Answer> = raw.orEmpty().lines().mapNotNull { Answer.decode(it) }.sortedBy { it.after }

    /** [answers] with [answer] in place of any earlier answer about the same break. */
    fun answering(answers: List<Answer>, answer: Answer): List<Answer> =
        (answers.filter { it.after != answer.after } + answer).sortedBy { it.after }.takeLast(KEPT)

    /** A break waiting for an answer: [days] since the workout at [after], the last one logged. */
    data class Gap(val after: LocalDateTime, val days: Int)

    /** The break to ask about on [today], or null when there is none or it was already answered. */
    fun gap(sessions: List<Session>, today: LocalDate, answers: List<Answer>): Gap? {
        val last = sessions.maxByOrNull { it.timestamp } ?: return null
        val days = Dates.daysBetween(last.date, today)
        if (days < GAP_DAYS) return null
        if (answers.any { it.after == last.timestamp }) return null
        return Gap(last.timestamp, days)
    }

    /** The answer that eases lifts on [today]: the latest one, when the lifter chose to ease back in not too long ago. */
    fun active(answers: List<Answer>, today: LocalDate): Answer? =
        answers.maxByOrNull { it.after }?.takeIf { it.easing && Dates.daysBetween(it.on, today) <= LASTS_DAYS }

    /**
     * The sessions of [history] (one lift's, newest first) that eased back in after [answer] below
     * the weight from before the break: the first [RAMP_SESSIONS] − 1 back, which are lighter on
     * purpose. Their ids, for a replay to skip.
     */
    internal fun lightSessions(history: List<Session>, exerciseId: String, answer: Answer): Set<String> {
        if (!answer.easing) return emptySet()
        val (post, pre) = history.partition { it.timestamp > answer.after }
        val beforeKg = pre.firstOrNull()?.let { s -> topKg(s.exercises.first { it.exerciseId == exerciseId }) } ?: return emptySet()
        return post.asReversed().take(RAMP_SESSIONS - 1)
            .filter { s -> (topKg(s.exercises.first { it.exerciseId == exerciseId }) ?: 0.0) < beforeKg }
            .map { it.id }.toSet()
    }

    /** The share of the weight from before the break that the first session back starts at: less the longer the break. */
    fun startFraction(awayDays: Int): Double = when {
        awayDays < 21 -> 0.9
        awayDays < 35 -> 0.85
        else -> 0.8
    }

    /**
     * The share of the weight from before the break for the lift's session number [done] + 1 since the
     * break: [startFraction] for the first, halfway back for the second, all of it for the third. Null
     * once the ramp is over.
     */
    fun fraction(awayDays: Int, done: Int): Double? {
        if (done >= RAMP_SESSIONS) return null
        val start = startFraction(awayDays)
        return start + (1.0 - start) * done / (RAMP_SESSIONS - 1)
    }

    /**
     * [fraction] of [referenceKg], rounded down to [increment] in the display unit so it can be
     * loaded, and never below one increment. The whole reference when [fraction] is 1.
     */
    fun weight(referenceKg: Double, fraction: Double, increment: Double, unit: WeightUnit): Double {
        if (fraction >= 1.0 || increment <= 0.0) return referenceKg
        val display = Units.display(referenceKg, unit)
        val rounded = (floor(display * fraction / increment) * increment).coerceAtLeast(increment).coerceAtMost(display)
        return Units.roundToQuarter(Units.fromDisplay(rounded, unit))
    }

    /** The heaviest working set of [entry], or null when nothing in it was loaded. */
    internal fun topKg(entry: ExerciseEntry): Double? = entry.workingSets.ifEmpty { entry.sets }.mapNotNull { it.weightKg }.maxOrNull()

    /**
     * What a lift added to a free workout starts at while easing back in: the sets of its last session
     * before the break, lighter. [weightKg] goes in every set, one per [reps] entry; [hint] says why.
     */
    data class FreeEase(val weightKg: Double, val reps: List<Int>, val hint: Progression.Hint.EaseBack)

    /**
     * How a free workout's [exercise] eases back in after [easing], or null when it does not: not a
     * loaded lift, never done before the break, already back to its weight, or past the ramp. Only
     * sessions before [before] count, for a workout logged after the fact.
     */
    fun freeCard(snapshot: TrainingSnapshot, exercise: Exercise, easing: Answer, unit: WeightUnit, before: LocalDateTime? = null): FreeEase? {
        if (exercise.modality != Modality.WEIGHTED) return null
        val history = snapshot.sessions
            .filter { s -> (before == null || s.timestamp < before) && s.exercises.any { it.exerciseId == exercise.id && it.workingSets.isNotEmpty() } }
            .sortedByDescending { it.timestamp }
            .map { s -> s.timestamp to s.exercises.first { it.exerciseId == exercise.id } }
        val (post, pre) = history.partition { it.first > easing.after }
        val reference = pre.firstOrNull()?.second ?: return null
        val referenceKg = topKg(reference)?.takeIf { it > 0.0 } ?: return null
        if (post.any { (topKg(it.second) ?: 0.0) >= referenceKg }) return null
        val fraction = fraction(easing.awayDays, post.size) ?: return null
        val weight = weight(referenceKg, fraction, Gzclp.defaultIncrement(unit), unit)
        val reps = reference.workingSets.ifEmpty { reference.sets }.map { it.reps ?: it.seconds ?: 0 }
        val last = (post.firstOrNull() ?: pre.first()).second
        return FreeEase(weight, reps, Progression.Hint.EaseBack(Progression.lastTimeOf(last, exercise), weight, referenceKg, easing.awayDays))
    }
}
