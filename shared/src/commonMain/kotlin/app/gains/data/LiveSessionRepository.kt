package app.gains.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.gains.db.GainsDatabase
import app.gains.domain.LiveExercise
import app.gains.domain.LiveSession
import app.gains.domain.ProgramDayRef
import app.gains.domain.RestTimer
import app.gains.domain.SetDraft
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * The single workout in progress. [save] replaces it whole; [clear] removes it. See the threading
 * note in Repositories.kt for why every flow ends in flowOn(io).
 */
class LiveSessionRepository(
    private val db: GainsDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val q get() = db.liveSessionQueries

    /** Null while no workout is in progress. */
    fun observe(): Flow<LiveSession?> = combine(
        q.selectLiveSession().asFlow().mapToList(io),
        q.selectLiveExercises().asFlow().mapToList(io),
        q.selectLiveSets().asFlow().mapToList(io),
    ) { sessions, exercises, sets ->
        val head = sessions.firstOrNull() ?: return@combine null
        val setsByExercise = sets.groupBy { it.exercise_position }
        LiveSession(
            startedAtMs = head.started_at_ms,
            title = head.title,
            program = if (head.program_id.isNullOrBlank() || head.program_day_id.isNullOrBlank()) null else ProgramDayRef(head.program_id, head.program_day_id),
            rest = if (head.rest_exercise_id != null && head.rest_ends_at_ms != null && head.rest_total_seconds != null) {
                RestTimer(head.rest_exercise_id, head.rest_ends_at_ms, head.rest_total_seconds.toInt())
            } else null,
            exercises = exercises.sortedBy { it.position }.map { e ->
                LiveExercise(
                    exerciseId = e.exercise_id,
                    note = e.note,
                    seeded = e.seeded != 0L,
                    warmupsCollapsed = e.warmups_collapsed != 0L,
                    sets = (setsByExercise[e.position] ?: emptyList()).sortedBy { it.set_order }.map { s ->
                        SetDraft(s.weight, s.reps, s.seconds, s.distance_km, isWarmup = s.is_warmup != 0L, done = s.done != 0L)
                    },
                )
            },
        )
    }.flowOn(io)

    suspend fun load(): LiveSession? = withContext(io) {
        val head = q.selectLiveSession().executeAsOneOrNull() ?: return@withContext null
        val setsByExercise = q.selectLiveSets().executeAsList().groupBy { it.exercise_position }
        LiveSession(
            startedAtMs = head.started_at_ms,
            title = head.title,
            program = if (head.program_id.isNullOrBlank() || head.program_day_id.isNullOrBlank()) null else ProgramDayRef(head.program_id, head.program_day_id),
            rest = if (head.rest_exercise_id != null && head.rest_ends_at_ms != null && head.rest_total_seconds != null) {
                RestTimer(head.rest_exercise_id, head.rest_ends_at_ms, head.rest_total_seconds.toInt())
            } else null,
            exercises = q.selectLiveExercises().executeAsList().map { e ->
                LiveExercise(
                    exerciseId = e.exercise_id,
                    note = e.note,
                    seeded = e.seeded != 0L,
                    warmupsCollapsed = e.warmups_collapsed != 0L,
                    sets = (setsByExercise[e.position] ?: emptyList()).map { s ->
                        SetDraft(s.weight, s.reps, s.seconds, s.distance_km, isWarmup = s.is_warmup != 0L, done = s.done != 0L)
                    },
                )
            },
        )
    }

    /** Replaces the stored workout in one transaction. */
    suspend fun save(session: LiveSession) = withContext(io) {
        db.transaction {
            q.deleteLiveSets()
            q.deleteLiveExercises()
            q.insertLiveSession(
                started_at_ms = session.startedAtMs,
                title = session.title,
                program_id = session.program?.programId,
                program_day_id = session.program?.dayId,
                rest_exercise_id = session.rest?.exerciseId,
                rest_ends_at_ms = session.rest?.endsAtMs,
                rest_total_seconds = session.rest?.totalSeconds?.toLong(),
            )
            session.exercises.forEachIndexed { position, e ->
                q.insertLiveExercise(position.toLong(), e.exerciseId, e.note, if (e.seeded) 1L else 0L, if (e.warmupsCollapsed) 1L else 0L)
                e.sets.forEachIndexed { order, s ->
                    q.insertLiveSet(position.toLong(), order.toLong(), s.weight, s.reps, s.seconds, s.distanceKm, if (s.isWarmup) 1L else 0L, if (s.done) 1L else 0L)
                }
            }
        }
    }

    /** Drops the rest countdown from the stored workout, leaving everything else as it is. No-op without one. */
    suspend fun clearRest() = withContext(io) { q.clearRest() }

    suspend fun clear() = withContext(io) {
        db.transaction {
            q.deleteLiveSets()
            q.deleteLiveExercises()
            q.deleteLiveSession()
        }
    }
}
