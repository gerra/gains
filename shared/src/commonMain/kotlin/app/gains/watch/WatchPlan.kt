package app.gains.watch

import app.gains.domain.Exercise
import app.gains.domain.LiveSession
import app.gains.domain.Modality
import app.gains.domain.RestTimer
import app.gains.domain.SetDraft
import app.gains.domain.WeightUnit
import app.gains.program.Gzclp

/**
 * The workout in progress turned into what the watch shows ([workout]), and the watch's commands
 * carried out on it ([apply]). Pure, so the phone's editor and the database path (a command that
 * arrives with no editor open) do exactly the same thing, and both are tested here.
 */
object WatchPlan {
    /**
     * [session] as the watch draws it. [exercises] gives each lift's modality and dumbbell flag and
     * [names] its name in the phone's language; a lift missing from them still shows, by its id.
     * [restSeconds] is the rest after a set of a lift, as [apply] takes it.
     */
    fun workout(
        session: LiveSession,
        unit: WeightUnit,
        exercises: Map<String, Exercise>,
        names: Map<String, String>,
        restSeconds: (exerciseId: String, warmup: Boolean) -> Int = { _, warmup -> restSeconds(null, warmup) },
    ): WatchWorkout {
        val list = session.exercises.map { e ->
            val exercise = exercises[e.exerciseId]
            WatchExercise(
                id = e.exerciseId,
                name = names[e.exerciseId] ?: exercise?.name ?: e.exerciseId,
                kind = kindOf(exercise?.modality, e.sets),
                perDumbbell = exercise?.isDumbbell == true,
                sets = e.sets.withLabels().map { (label, s) -> WatchSet(label, s.weight, s.reps, s.seconds, s.distanceKm, s.isWarmup, s.done, restSeconds(e.exerciseId, s.isWarmup)) },
            )
        }
        val order = session.exercises.map { e -> e.sets.map { it.done } }
        val current = current(order, session.exercises.indexOfFirst { it.exerciseId == session.rest?.exerciseId }.takeIf { it >= 0 })
        return WatchWorkout(
            startedAtMs = session.startedAtMs,
            title = session.title,
            unit = unit.label,
            rest = session.rest?.let { WatchRest(it.exerciseId, it.endsAtMs, it.totalSeconds) },
            exercises = list,
            current = current,
            next = current?.let { after(order, it) },
            done = order.sumOf { sets -> sets.count { it } },
            total = order.sumOf { it.size },
        )
    }

    /**
     * The set to do now: the first unticked one of the lift the last tick was on (the one the rest
     * belongs to), then of the lifts after it, then of those before; without a rest, the first
     * unticked set of the workout. So a lifter who skipped ahead is not sent back to the lift they
     * skipped until the ones after it are done.
     */
    internal fun current(done: List<List<Boolean>>, anchor: Int?): WatchSetRef? {
        if (done.isEmpty()) return null
        val start = anchor ?: 0
        for (step in done.indices) {
            val e = (start + step) % done.size
            val s = done[e].indexOfFirst { !it }
            if (s >= 0) return WatchSetRef(e, s)
        }
        return null
    }

    /** The unticked set after [from], in the same order as [current]; null when there is none. */
    internal fun after(done: List<List<Boolean>>, from: WatchSetRef): WatchSetRef? {
        val laterInSame = done[from.exercise].withIndex().firstOrNull { (i, d) -> i > from.set && !d }
        if (laterInSame != null) return WatchSetRef(from.exercise, laterInSame.index)
        for (step in 1 until done.size) {
            val e = (from.exercise + step) % done.size
            val s = done[e].indexOfFirst { !it }
            if (s >= 0) return WatchSetRef(e, s)
        }
        // Only earlier sets of the same lift are left.
        return done[from.exercise].withIndex().firstOrNull { (i, d) -> i < from.set && !d }?.let { WatchSetRef(from.exercise, it.index) }
    }

    /** How the lift is logged; an unknown lift is judged by what was typed into it. */
    private fun kindOf(modality: Modality?, sets: List<SetDraft>): WatchKind = when (modality) {
        Modality.WEIGHTED -> WatchKind.WEIGHTED
        Modality.BODYWEIGHT -> WatchKind.BODYWEIGHT
        Modality.ISOMETRIC -> WatchKind.TIMED
        Modality.CARDIO -> WatchKind.CARDIO
        null -> when {
            sets.any { it.distanceKm.isNotBlank() } -> WatchKind.CARDIO
            sets.any { it.weight.isNotBlank() } -> WatchKind.WEIGHTED
            sets.any { it.seconds.isNotBlank() } && sets.none { it.reps.isNotBlank() } -> WatchKind.TIMED
            else -> WatchKind.BODYWEIGHT
        }
    }

    /** "W1, W2…" for the warm-ups and "1, 2…" for the work sets, as the editor numbers its rows. */
    private fun List<SetDraft>.withLabels(): List<Pair<String, SetDraft>> {
        var warmup = 0
        var work = 0
        return map { (if (it.isWarmup) "W${++warmup}" else (++work).toString()) to it }
    }

    /** What the rest counts down from after a set: the tier's short end, the warm-up rest, or the default. */
    fun restSeconds(tier: Gzclp.Tier?, warmup: Boolean): Int = when {
        warmup -> Gzclp.WARMUP_REST.first
        else -> tier?.restTimerSeconds ?: Gzclp.DEFAULT_REST_SECONDS
    }

    /**
     * [session] with [command] carried out at [nowMs], or null when it does not apply: meant for
     * another workout, a lift or set that is no longer there, a set already in the state asked
     * for, nothing to tick, or no rest to skip. [restSeconds] says how long the rest after a set
     * of the given lift is (see [restSeconds] above). Ticking starts the rest as the phone's tick
     * does; un-ticking leaves the rest alone, as the phone's does.
     */
    fun apply(
        session: LiveSession,
        command: WatchCommand,
        nowMs: Long,
        restSeconds: (exerciseId: String, warmup: Boolean) -> Int,
    ): LiveSession? {
        if (command.startedAtMs != session.startedAtMs) return null
        return when (command) {
            is WatchCommand.Complete -> session.changingSet(command.exerciseId, command.set) { set ->
                if (set.done) return null
                val next = set.copy(
                    weight = command.weight ?: set.weight,
                    reps = command.reps ?: set.reps,
                    seconds = command.seconds ?: set.seconds,
                    done = true,
                )
                if (!next.hasValues) return null
                next
            }?.let { ticked ->
                val warmup = ticked.exercises.first { it.exerciseId == command.exerciseId }.sets[command.set].isWarmup
                val seconds = restSeconds(command.exerciseId, warmup)
                ticked.copy(rest = RestTimer(command.exerciseId, nowMs + seconds * 1000L, seconds))
            }
            is WatchCommand.Undo -> session.changingSet(command.exerciseId, command.set) { set ->
                if (!set.done) return null
                set.copy(done = false)
            }
            is WatchCommand.SkipRest -> if (session.rest == null) null else session.copy(rest = null)
            is WatchCommand.ExtendRest -> {
                if (command.seconds <= 0) return null
                val rest = session.rest
                session.copy(
                    rest = when {
                        rest != null && !rest.isOver(nowMs) -> rest.copy(endsAtMs = rest.endsAtMs + command.seconds * 1000L, totalSeconds = rest.totalSeconds + command.seconds)
                        else -> {
                            val exerciseId = rest?.exerciseId ?: session.exercises.firstOrNull()?.exerciseId ?: return null
                            RestTimer(exerciseId, nowMs + command.seconds * 1000L, command.seconds)
                        }
                    },
                )
            }
        }
    }

    /** [session] with one set replaced by [change]; null when the set is not there. */
    private inline fun LiveSession.changingSet(exerciseId: String, set: Int, change: (SetDraft) -> SetDraft): LiveSession? {
        val index = exercises.indexOfFirst { it.exerciseId == exerciseId }
        if (index < 0) return null
        val exercise = exercises[index]
        val old = exercise.sets.getOrNull(set) ?: return null
        val new = change(old)
        return copy(exercises = exercises.mapIndexed { i, e -> if (i != index) e else e.copy(sets = e.sets.mapIndexed { j, s -> if (j == set) new else s }) })
    }
}
