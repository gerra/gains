package app.gains.domain

import app.gains.analysis.Format
import kotlin.math.roundToInt

/**
 * A set as it is typed during a workout: text fields so partial input ("62.") is allowed, and a
 * [done] tick that drives the rest timer. Converted to a [SetEntry] when the session is stored.
 */
data class SetDraft(
    val weight: String = "",
    val reps: String = "",
    val seconds: String = "",
    val distanceKm: String = "",
    /** Planned or ticked as a warm-up: stored with the set, kept out of volume, records and progression. */
    val isWarmup: Boolean = false,
    /**
     * Ticked off during the workout, Liftoff-style: the check on the row toggles it. Ticking starts the
     * rest timer; saving offers to leave unticked work sets out. Sets loaded from a saved workout start ticked.
     */
    val done: Boolean = false,
) {
    /** Something was entered, so the set can be ticked off. */
    val hasValues: Boolean get() = listOf(weight, reps, seconds, distanceKm).any { it.isNotBlank() }

    /** Null when nothing usable was entered. */
    fun toSet(order: Int, unit: WeightUnit): SetEntry? {
        val w = weight.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { Units.roundToQuarter(Units.fromDisplay(it, unit)) }
        val r = reps.toIntOrNull()?.takeIf { it > 0 }
        val s = seconds.toIntOrNull()?.takeIf { it > 0 }
        val d = distanceKm.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
        if (w == null && r == null && s == null && d == null) return null
        val type = when {
            d != null -> SetType.CARDIO
            r != null && w != null -> SetType.WEIGHTED
            r != null -> SetType.BODYWEIGHT
            s != null -> SetType.ISOMETRIC
            else -> SetType.WEIGHTED
        }
        return SetEntry(order, type, w, r, s, d, isWarmup = isWarmup)
    }

    companion object {
        fun from(set: SetEntry, unit: WeightUnit) = SetDraft(
            weight = set.weightKg?.let { Format.weightValue(it, unit) } ?: "",
            reps = set.reps?.toString() ?: "",
            seconds = set.seconds?.toString() ?: "",
            distanceKm = set.distanceKm?.let { Format.number(it, 2) } ?: "",
            isWarmup = set.isWarmup,
            done = true,
        )
    }
}

/** A rest countdown started by ticking a set off. One at a time: the next tick replaces it. */
data class RestTimer(val exerciseId: String, val endsAtMs: Long, val totalSeconds: Int) {
    /** Whole seconds left, rounded up so the display never shows 0 while time remains. */
    fun remainingSeconds(nowMs: Long): Int = ((endsAtMs - nowMs + 999) / 1000).toInt().coerceIn(0, totalSeconds)
    fun isOver(nowMs: Long): Boolean = nowMs >= endsAtMs
}

/** One exercise of a workout in progress, with everything the editor holds for it. */
data class LiveExercise(
    val exerciseId: String,
    val sets: List<SetDraft>,
    val note: String = "",
    /** The weights were borrowed from outside the slot's scheme and the "start blank" offer is still showing. */
    val seeded: Boolean = false,
    val warmupsCollapsed: Boolean = false,
)

/**
 * A workout in progress: the clock started when the session did, and every field of the editor
 * as it stands. Persisted whole so a workout survives the app being killed, and cleared when the
 * session is ended or discarded. There is at most one at a time.
 */
data class LiveSession(
    /** Wall-clock start in epoch milliseconds: the session's timestamp, and what the total time counts from. */
    val startedAtMs: Long,
    val title: String,
    val program: ProgramDayRef? = null,
    val exercises: List<LiveExercise> = emptyList(),
    val rest: RestTimer? = null,
) {
    fun elapsedMs(nowMs: Long): Long = (nowMs - startedAtMs).coerceAtLeast(0L)

    /** True once the timer has run past [LONG_SESSION_MS], which is worth double-checking before it is stored. */
    fun hasRunLong(nowMs: Long): Boolean = isLong(elapsedMs(nowMs))

    companion object {
        /** Sessions longer than this were most likely left running: the lifter is asked before the duration is stored. */
        const val LONG_SESSION_MS: Long = 3L * 60 * 60 * 1000

        fun isLong(elapsedMs: Long): Boolean = elapsedMs > LONG_SESSION_MS

        /** The duration to store for a session that ran [elapsedMs]: whole minutes, never less than one. */
        fun durationMinutes(elapsedMs: Long): Int = (elapsedMs / 60_000.0).roundToInt().coerceAtLeast(1)
    }
}
