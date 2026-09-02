package app.gains.importer

import app.gains.analysis.WorkingSets
import app.gains.csv.ParsedCsv
import app.gains.csv.RawSession
import app.gains.csv.SkipReason
import app.gains.csv.SkippedRow
import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import kotlinx.datetime.LocalDate

/** A session already in the database, reduced to what duplicate detection needs. */
data class StoredSessionSummary(
    val id: String,
    val date: LocalDate,
    val fingerprint: String,
    /** Full content hash, used to tell "already imported" from "changed since last import". */
    val contentHash: String,
)

/** Everything the analyzer needs to know about the current database. */
data class ExistingData(
    val sessions: List<StoredSessionSummary> = emptyList(),
    val exercises: List<Exercise> = emptyList(),
    /** normalized raw name -> exercise id. */
    val userAliases: Map<String, String> = emptyMap(),
    /** exercise id -> working-set ratio override. */
    val workingSetRatios: Map<String, Double> = emptyMap(),
    /** exercise id -> historical isometric hold durations, for outlier medians. */
    val isometricHistory: Map<String, List<Int>> = emptyMap(),
)

data class DuplicateSession(
    val droppedSessionId: String,
    val keptSessionId: String,
    val date: LocalDate,
    val exerciseNames: List<String>,
    /** True when the kept copy is one that was imported previously. */
    val keptIsAlreadyStored: Boolean,
)

data class IsometricOutlier(
    val sessionId: String,
    val date: LocalDate,
    val exerciseId: String,
    val exerciseName: String,
    val setOrder: Int,
    val seconds: Int,
    val medianSeconds: Int,
) {
    val key: String get() = "$sessionId|$exerciseId|$setOrder"
}

enum class SessionDisposition { NEW, UNCHANGED, CHANGED, DUPLICATE }

data class CandidateSession(
    val session: Session,
    val disposition: SessionDisposition,
    val durationDiscarded: Boolean,
)

data class ImportPreview(
    val candidates: List<CandidateSession>,
    val skipped: List<SkippedRow>,
    val duplicates: List<DuplicateSession>,
    val outliers: List<IsometricOutlier>,
    val newExercises: List<Exercise>,
    val rowCount: Int,
) {
    val importable: List<CandidateSession> get() = candidates.filter { it.disposition != SessionDisposition.DUPLICATE }
    val newCount: Int get() = candidates.count { it.disposition == SessionDisposition.NEW }
    val changedCount: Int get() = candidates.count { it.disposition == SessionDisposition.CHANGED }
    val unchangedCount: Int get() = candidates.count { it.disposition == SessionDisposition.UNCHANGED }
    val corruptDurationCount: Int get() = candidates.count { it.durationDiscarded }
    val dateRange: ClosedRange<LocalDate>? get() {
        val dates = candidates.map { it.session.date }
        val min = dates.minOrNull() ?: return null
        return min..dates.max()
    }
    val skippedByReason: Map<SkipReason, Int> get() = skipped.groupingBy { it.reason }.eachCount()

    /** Sessions to write, with outliers removed according to [confirmedOutlierKeys]. */
    fun sessionsToCommit(confirmedOutlierKeys: Set<String>): List<Session> {
        val discard = outliers.filter { it.key !in confirmedOutlierKeys }
            .groupBy { it.sessionId }
        return candidates
            .filter { it.disposition == SessionDisposition.NEW || it.disposition == SessionDisposition.CHANGED }
            .map { candidate ->
                val toDrop = discard[candidate.session.id] ?: return@map candidate.session
                candidate.session.copy(
                    exercises = candidate.session.exercises.map { entry ->
                        val dropOrders = toDrop.filter { it.exerciseId == entry.exerciseId }.map { it.setOrder }.toSet()
                        entry.copy(sets = entry.sets.filter { it.order !in dropOrders })
                    }.filter { it.sets.isNotEmpty() }
                )
            }
            .filter { it.exercises.isNotEmpty() }
    }
}

/**
 * Turns a parsed file into an import plan: resolves exercise names, infers warm-ups,
 * detects near-duplicate sessions (rule 3) and isometric outliers (rule 7), and
 * classifies each session against what is already stored so re-imports are idempotent.
 */
class ImportAnalyzer(
    private val outlierFactor: Double = 5.0,
    /** Minimum number of holds needed before outlier detection is attempted. */
    private val minSamplesForOutliers: Int = 3,
) {
    fun analyze(parsed: ParsedCsv, existing: ExistingData): ImportPreview {
        val resolver = ExerciseResolver(existing.exercises, existing.userAliases)

        // 1. Resolve names and build domain sessions.
        val resolved = parsed.sessions.map { raw -> raw to toSession(raw, resolver, existing.workingSetRatios) }

        // 2. Near-duplicate detection: same date, same exercise list, same set count.
        val duplicates = ArrayList<DuplicateSession>()
        val storedByFingerprint = existing.sessions.associateBy { it.fingerprint }
        val storedById = existing.sessions.associateBy { it.id }
        val seenInFile = HashMap<String, Session>()
        val candidates = ArrayList<CandidateSession>()
        for ((raw, session) in resolved) {
            val fingerprint = fingerprint(session)
            val names = session.exercises.map { resolver.exercise(it.exerciseId)?.name ?: it.exerciseId }
            val stored = storedById[session.id]
            val earlierInFile = seenInFile[fingerprint]
            when {
                earlierInFile != null -> {
                    duplicates.add(DuplicateSession(session.id, earlierInFile.id, session.date, names, keptIsAlreadyStored = false))
                    candidates.add(CandidateSession(session, SessionDisposition.DUPLICATE, raw.durationDiscarded))
                }
                stored == null && storedByFingerprint[fingerprint] != null -> {
                    val keeper = storedByFingerprint.getValue(fingerprint)
                    duplicates.add(DuplicateSession(session.id, keeper.id, session.date, names, keptIsAlreadyStored = true))
                    candidates.add(CandidateSession(session, SessionDisposition.DUPLICATE, raw.durationDiscarded))
                }
                else -> {
                    seenInFile[fingerprint] = session
                    val disposition = when {
                        stored == null -> SessionDisposition.NEW
                        stored.contentHash == contentHash(session) -> SessionDisposition.UNCHANGED
                        else -> SessionDisposition.CHANGED
                    }
                    candidates.add(CandidateSession(session, disposition, raw.durationDiscarded))
                }
            }
        }

        // 3. Isometric outliers: holds more than outlierFactor × the (lower) median for that exercise.
        val outliers = detectOutliers(candidates.filter { it.disposition != SessionDisposition.DUPLICATE }, existing.isometricHistory, resolver)

        return ImportPreview(
            candidates = candidates,
            skipped = parsed.skipped,
            duplicates = duplicates,
            outliers = outliers,
            newExercises = resolver.newExercises,
            rowCount = parsed.rowCount,
        )
    }

    private fun toSession(raw: RawSession, resolver: ExerciseResolver, ratios: Map<String, Double>): Session {
        val entries = LinkedHashMap<String, ExerciseEntry>()
        for (rawExercise in raw.exercises) {
            val exercise = resolver.resolve(rawExercise.name, rawExercise.sets.map { it.type })
            val sets = rawExercise.sets.map { s ->
                SetEntry(s.order, s.type, s.weightKg, s.reps, s.seconds, s.distanceKm, s.rpe)
            }
            val previous = entries[exercise.id]
            // Two raw names can map onto one exercise; merge their sets, keeping notes distinct.
            val merged = if (previous == null) ExerciseEntry(exercise.id, sets, rawExercise.note) else {
                val allSets = (previous.sets + sets).mapIndexed { i, s -> s.copy(order = i) }
                val note = listOfNotNull(previous.note, rawExercise.note).distinct().joinToString("\n").ifEmpty { null }
                ExerciseEntry(exercise.id, allSets, note)
            }
            entries[exercise.id] = WorkingSets.apply(merged, ratios[exercise.id] ?: WorkingSets.DEFAULT_RATIO)
        }
        return Session(raw.id, raw.timestamp, raw.durationMinutes, entries.values.toList())
    }

    private fun detectOutliers(
        candidates: List<CandidateSession>,
        history: Map<String, List<Int>>,
        resolver: ExerciseResolver,
    ): List<IsometricOutlier> {
        data class Hold(val session: Session, val exerciseId: String, val set: SetEntry)
        val holds = candidates.flatMap { c ->
            c.session.exercises.flatMap { e ->
                e.sets.filter { it.type == SetType.ISOMETRIC && it.seconds != null }.map { Hold(c.session, e.exerciseId, it) }
            }
        }
        val result = ArrayList<IsometricOutlier>()
        for ((exerciseId, exerciseHolds) in holds.groupBy { it.exerciseId }) {
            val samples = exerciseHolds.map { it.set.seconds!! } + (history[exerciseId] ?: emptyList())
            if (samples.size < minSamplesForOutliers) continue
            val median = lowerMedian(samples)
            if (median <= 0) continue
            for (hold in exerciseHolds) {
                val seconds = hold.set.seconds!!
                if (seconds > median * outlierFactor) {
                    val name = resolver.exercise(exerciseId)?.name ?: exerciseId
                    result.add(IsometricOutlier(hold.session.id, hold.session.date, exerciseId, name, hold.set.order, seconds, median))
                }
            }
        }
        return result.sortedWith(compareBy({ it.date }, { it.exerciseName }, { it.setOrder }))
    }

    companion object {
        /** Lower median: robust when half of the samples are the same bad value. */
        fun lowerMedian(values: List<Int>): Int {
            val sorted = values.sorted()
            return sorted[(sorted.size - 1) / 2]
        }

        /** Same date + same exercise list + same set count: the near-duplicate signature. */
        fun fingerprint(session: Session): String =
            session.date.toString() + "|" + session.exercises.joinToString(",") { it.exerciseId } + "|" + session.setCount

        fun contentHash(session: Session): String {
            val sb = StringBuilder()
            sb.append(session.timestamp).append('|').append(session.durationMinutes ?: "-")
            for (e in session.exercises) {
                sb.append('#').append(e.exerciseId).append('~').append(e.note ?: "")
                for (s in e.sets) {
                    sb.append(';').append(s.type.name[0]).append(s.weightKg ?: "").append('x').append(s.reps ?: "")
                        .append('t').append(s.seconds ?: "").append('d').append(s.distanceKm ?: "")
                }
            }
            return sb.toString().hashCode().toString(16)
        }
    }
}
