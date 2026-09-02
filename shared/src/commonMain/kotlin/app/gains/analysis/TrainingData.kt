package app.gains.analysis

import app.gains.data.ExerciseRepository
import app.gains.data.SessionRepository
import app.gains.domain.Exercise
import app.gains.domain.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Sessions with the warm-up rule applied, plus the exercises they reference. */
data class TrainingSnapshot(
    val sessions: List<Session>,
    val exercises: List<Exercise>,
) {
    val exercisesById: Map<String, Exercise> by lazy { exercises.associateBy { it.id } }
    val isEmpty: Boolean get() = sessions.isEmpty()

    /** Exercises that appear in at least one session, most recently trained first. */
    val trainedExercises: List<Exercise> by lazy {
        val lastTrained = HashMap<String, Session>()
        for (s in sessions) for (e in s.exercises) {
            val prev = lastTrained[e.exerciseId]
            if (prev == null || prev.timestamp < s.timestamp) lastTrained[e.exerciseId] = s
        }
        lastTrained.entries.sortedByDescending { it.value.timestamp }.mapNotNull { exercisesById[it.key] }
    }
}

/** Single reactive source of truth for every analysis screen. */
class TrainingData(
    private val sessions: SessionRepository,
    private val exercises: ExerciseRepository,
) {
    val snapshot: Flow<TrainingSnapshot> = combine(
        sessions.observeRawSessions(),
        exercises.observeExercises(),
        exercises.observeWorkingSetRatios(),
    ) { rawSessions, exerciseList, ratios ->
        TrainingSnapshot(WorkingSets.apply(rawSessions, ratios), exerciseList)
    }
}
