package app.gains.analysis

import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.Modality
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import kotlinx.datetime.LocalDate

/**
 * What a personal record is a record of. A weighted lift keeps four, as Hevy and Strong do; the
 * other modalities keep the one number that means something for them. Most reps at a given weight
 * is deliberately not one: on a double-progression program every session would set several, and a
 * record that is set every time is not a record.
 */
enum class RecordKind {
    /** Heaviest working set, whatever the reps. */
    WEIGHT,
    /** Best estimated one-rep max (Epley) over the working sets. */
    E1RM,
    /** Heaviest single set by weight × reps. */
    SET_VOLUME,
    /** Most weight × reps over the exercise's working sets in one session. */
    SESSION_VOLUME,
    /** Most reps in one set of a bodyweight movement. */
    REPS,
    /** Longest hold. */
    HOLD,
    /** Longest distance in one set. */
    DISTANCE,
}

/**
 * A record set in one session: [value] beat [previous], which stood since [previousDate]. There is
 * no record without a previous best to beat — the first session of an exercise sets a baseline,
 * not a record, so a freshly imported history never arrives as a wall of trophies.
 *
 * [set] is the set that did it, null for a [RecordKind.SESSION_VOLUME] record, which no single set did.
 */
data class Record(
    val exerciseId: String,
    val kind: RecordKind,
    val sessionId: String,
    val date: LocalDate,
    val value: Double,
    val set: SetEntry?,
    val previous: Double,
    val previousDate: LocalDate,
) {
    /** How far past the old record, as a fraction of it. */
    val gain: Double get() = if (previous > 0) (value - previous) / previous else 0.0
}

/** A top set of [liftedKg] that stopped [fraction] short of the standing [bestKg]. */
data class NearMiss(val exerciseId: String, val liftedKg: Double, val bestKg: Double, val fraction: Double) {
    val shortByKg: Double get() = bestKg - liftedKg
}

/** The standing best for one exercise and kind: what there is to beat. */
data class RecordHolder(
    val exerciseId: String,
    val kind: RecordKind,
    val sessionId: String,
    val date: LocalDate,
    val value: Double,
    val set: SetEntry?,
)

/**
 * Personal records, worked out from the session history alone. Nothing is stored: editing an old
 * workout, deleting one or importing years of history all give the records that history deserves.
 *
 * Only working sets count. A warm-up can never be a record, and since the warm-up rule is applied
 * before the sessions get here, changing it on a lift re-decides its records too.
 */
object Records {
    /**
     * A new best must clear the old one by this fraction. Weights are stored to a quarter kilo and
     * e1RM is an estimate, so a hair's breadth over the old number is the same number.
     */
    const val MIN_GAIN = 0.005
    /**
     * Epley is honest to about ten reps and flattering past them, so a set of fifteen sets no
     * e1RM record: it can still be a weight, set-volume or session-volume one.
     */
    const val MAX_E1RM_REPS = 10
    /** The rep counts a rep-max table is kept for: the best weight lifted for at least that many reps. */
    val REP_MAXES = listOf(1, 2, 3, 5, 8, 10, 12)

    /** The kinds a lift of [modality] keeps. */
    fun kinds(modality: Modality): List<RecordKind> = when (modality) {
        Modality.WEIGHTED -> listOf(RecordKind.WEIGHT, RecordKind.E1RM, RecordKind.SET_VOLUME, RecordKind.SESSION_VOLUME)
        Modality.BODYWEIGHT -> listOf(RecordKind.REPS, RecordKind.WEIGHT)
        Modality.ISOMETRIC -> listOf(RecordKind.HOLD)
        Modality.CARDIO -> listOf(RecordKind.DISTANCE)
    }

    /** The exercise's numbers in one session, per kind, with the set that made each. */
    fun measure(entry: ExerciseEntry, modality: Modality): Map<RecordKind, Pair<Double, SetEntry?>> {
        val working = entry.workingSets
        val result = HashMap<RecordKind, Pair<Double, SetEntry?>>()
        fun best(kind: RecordKind, sets: List<SetEntry>, value: (SetEntry) -> Double) {
            val top = sets.maxByOrNull(value) ?: return
            val v = value(top)
            if (v > 0) result[kind] = v to top
        }
        when (modality) {
            Modality.WEIGHTED -> {
                val loaded = working.filter { it.type == SetType.WEIGHTED && it.weightKg != null && it.reps != null && it.reps > 0 }
                best(RecordKind.WEIGHT, loaded) { it.weightKg!! }
                best(RecordKind.E1RM, loaded.filter { it.reps!! <= MAX_E1RM_REPS }) { Epley.e1rm(it.weightKg!!, it.reps!!) }
                best(RecordKind.SET_VOLUME, loaded) { it.volumeKg }
                val volume = loaded.sumOf { it.volumeKg }
                if (volume > 0) result[RecordKind.SESSION_VOLUME] = volume to null
            }
            Modality.BODYWEIGHT -> {
                val reps = working.filter { it.reps != null && it.reps > 0 }
                best(RecordKind.REPS, reps) { it.reps!!.toDouble() }
                // Added load is its own record: +20 kg × 5 is not beaten by 12 clean.
                best(RecordKind.WEIGHT, reps.filter { (it.weightKg ?: 0.0) > 0 }) { it.weightKg!! }
            }
            Modality.ISOMETRIC -> best(RecordKind.HOLD, working.filter { it.seconds != null }) { it.seconds!!.toDouble() }
            Modality.CARDIO -> best(RecordKind.DISTANCE, working.filter { it.distanceKm != null }) { it.distanceKm!! }
        }
        return result
    }

    /**
     * Every record ever set, in the order they were set: one forward pass over [sessions] carrying
     * the standing bests. Two sessions at the same moment are taken in id order, so the answer is
     * the same however the list arrived.
     */
    fun timeline(sessions: List<Session>, exercisesById: Map<String, Exercise>): List<Record> {
        val standing = HashMap<Pair<String, RecordKind>, RecordHolder>()
        val records = ArrayList<Record>()
        for (session in ordered(sessions)) {
            records += setBy(session, exercisesById, standing)
            hold(session, exercisesById, standing)
        }
        return records
    }

    /** The standing bests after every session in [sessions]: what there is to beat, by exercise and kind. */
    fun standing(sessions: List<Session>, exercisesById: Map<String, Exercise>): Map<String, Map<RecordKind, RecordHolder>> {
        val standing = HashMap<Pair<String, RecordKind>, RecordHolder>()
        for (session in ordered(sessions)) hold(session, exercisesById, standing)
        return standing.values.groupBy { it.exerciseId }.mapValues { (_, holders) -> holders.associateBy { it.kind } }
    }

    /**
     * The records [session] set against everything before it in [sessions]. What came before is
     * decided by time, not by list order, so editing an old workout judges it against its own past
     * and never against what was lifted since.
     */
    fun forSession(session: Session, sessions: List<Session>, exercisesById: Map<String, Exercise>): List<Record> {
        val standing = HashMap<Pair<String, RecordKind>, RecordHolder>()
        for (earlier in ordered(sessions.filter { it.id != session.id && before(it, session) })) hold(earlier, exercisesById, standing)
        return setBy(session, exercisesById, standing)
    }

    /**
     * The closest [session] came to a weight record without setting one: the lift whose top set was
     * nearest its standing best, within [NEAR_MISS] of it. One line on a summary with no records,
     * so the next target is named rather than the miss dwelt on. Null when nothing came close.
     */
    fun nearMiss(session: Session, sessions: List<Session>, exercisesById: Map<String, Exercise>): NearMiss? {
        val standing = HashMap<Pair<String, RecordKind>, RecordHolder>()
        for (earlier in ordered(sessions.filter { it.id != session.id && before(it, session) })) hold(earlier, exercisesById, standing)
        return session.exercises.groupBy { it.exerciseId }.mapNotNull { (exerciseId, entries) ->
            val exercise = exercisesById[exerciseId] ?: return@mapNotNull null
            if (exercise.modality != Modality.WEIGHTED) return@mapNotNull null
            val best = standing[exerciseId to RecordKind.WEIGHT] ?: return@mapNotNull null
            val top = merge(entries.map { measure(it, exercise.modality) })[RecordKind.WEIGHT]?.first ?: return@mapNotNull null
            val short = best.value - top
            if (short <= 0 || short > best.value * NEAR_MISS) return@mapNotNull null
            NearMiss(exerciseId, top, best.value, short / best.value)
        }.minByOrNull { it.fraction }
    }

    /** Within this fraction of the standing weight record counts as close. */
    const val NEAR_MISS = 0.05

    /**
     * Whether [set], on its own, beats one of the standing records a single set can hold: the star
     * on a row as it is ticked off, before the workout is stored. Session volume needs the whole
     * workout and is left to the summary.
     */
    fun beats(set: SetEntry, modality: Modality, standing: Map<RecordKind, RecordHolder>): Boolean =
        measure(ExerciseEntry("", listOf(set)), modality).any { (kind, measured) ->
            kind != RecordKind.SESSION_VOLUME && standing[kind]?.let { measured.first >= it.value * (1 + MIN_GAIN) } == true
        }

    /** Records by session id, for a badge on each session in a list. */
    fun bySession(sessions: List<Session>, exercisesById: Map<String, Exercise>): Map<String, List<Record>> =
        timeline(sessions, exercisesById).groupBy { it.sessionId }

    /**
     * The heaviest working set for at least each of [REP_MAXES] reps, with when it was done: the
     * 5RM is the most ever lifted for five or more. Tracked for the lift's page, never celebrated —
     * a program that adds a rep a week would set one every session.
     */
    fun repMaxes(sessions: List<Session>, exerciseId: String): Map<Int, RecordHolder> {
        val result = HashMap<Int, RecordHolder>()
        for (session in ordered(sessions)) {
            for (entry in session.exercises) {
                if (entry.exerciseId != exerciseId) continue
                for (set in entry.workingSets) {
                    val weight = set.weightKg ?: continue
                    val reps = set.reps ?: continue
                    if (set.type != SetType.WEIGHTED || reps <= 0 || weight <= 0) continue
                    for (n in REP_MAXES) {
                        if (reps < n) break
                        val old = result[n]
                        if (old == null || weight > old.value) result[n] = RecordHolder(exerciseId, RecordKind.WEIGHT, session.id, session.date, weight, set)
                    }
                }
            }
        }
        return result
    }

    private fun ordered(sessions: List<Session>) = sessions.sortedWith(compareBy({ it.timestamp }, { it.id }))

    private fun before(a: Session, b: Session) = a.timestamp < b.timestamp || (a.timestamp == b.timestamp && a.id < b.id)

    private fun setBy(session: Session, exercisesById: Map<String, Exercise>, standing: Map<Pair<String, RecordKind>, RecordHolder>): List<Record> {
        val records = ArrayList<Record>()
        // An exercise done twice in one workout is one exercise: its best across both entries.
        for ((exerciseId, entries) in session.exercises.groupBy { it.exerciseId }) {
            val exercise = exercisesById[exerciseId] ?: continue
            val measured = merge(entries.map { measure(it, exercise.modality) })
            for (kind in kinds(exercise.modality)) {
                val (value, set) = measured[kind] ?: continue
                val old = standing[exerciseId to kind] ?: continue
                if (value < old.value * (1 + MIN_GAIN)) continue
                records += Record(exerciseId, kind, session.id, session.date, value, set, old.value, old.date)
            }
        }
        return records
    }

    private fun hold(session: Session, exercisesById: Map<String, Exercise>, standing: MutableMap<Pair<String, RecordKind>, RecordHolder>) {
        for ((exerciseId, entries) in session.exercises.groupBy { it.exerciseId }) {
            val exercise = exercisesById[exerciseId] ?: continue
            val measured = merge(entries.map { measure(it, exercise.modality) })
            for (kind in kinds(exercise.modality)) {
                val (value, set) = measured[kind] ?: continue
                val old = standing[exerciseId to kind]
                // A tie leaves the older holder standing: the first to lift it holds it.
                if (old == null || value > old.value) standing[exerciseId to kind] = RecordHolder(exerciseId, kind, session.id, session.date, value, set)
            }
        }
    }

    /** The best of several entries' measurements, kind by kind; session volume adds up across them. */
    private fun merge(all: List<Map<RecordKind, Pair<Double, SetEntry?>>>): Map<RecordKind, Pair<Double, SetEntry?>> {
        if (all.size == 1) return all.single()
        val result = HashMap<RecordKind, Pair<Double, SetEntry?>>()
        for (measured in all) for ((kind, pair) in measured) {
            val current = result[kind]
            result[kind] = when {
                kind == RecordKind.SESSION_VOLUME && current != null -> (current.first + pair.first) to null
                current == null || pair.first > current.first -> pair
                else -> current
            }
        }
        return result
    }
}
