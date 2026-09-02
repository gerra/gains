package app.gains.importer

import app.gains.csv.LiftoffCsvParser
import app.gains.data.ExerciseRepository
import app.gains.data.SessionRepository
import app.gains.domain.WeightUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportResult(
    val sessionsWritten: Int,
    val exercisesCreated: Int,
    val outliersDiscarded: Int,
)

/** Orchestrates parse → analyze → commit. Parsing runs off the main thread. */
class ImportService(
    private val sessions: SessionRepository,
    private val exercises: ExerciseRepository,
) {
    suspend fun preview(csvText: String, weightUnit: WeightUnit = WeightUnit.LBS): ImportPreview {
        val existing = ExistingData(
            sessions = sessions.summaries(),
            exercises = exercises.exercises(),
            userAliases = exercises.aliases(),
            workingSetRatios = exercises.workingSetRatios(),
            isometricHistory = sessions.isometricHistory(),
        )
        return withContext(Dispatchers.Default) {
            val parsed = LiftoffCsvParser(weightUnit).parse(csvText)
            ImportAnalyzer().analyze(parsed, existing)
        }
    }

    suspend fun commit(preview: ImportPreview, confirmedOutlierKeys: Set<String>): ImportResult {
        val toWrite = withContext(Dispatchers.Default) { preview.sessionsToCommit(confirmedOutlierKeys) }
        exercises.insertIfMissing(preview.newExercises)
        sessions.upsertAll(toWrite)
        return ImportResult(
            sessionsWritten = toWrite.size,
            exercisesCreated = preview.newExercises.size,
            outliersDiscarded = preview.outliers.count { it.key !in confirmedOutlierKeys },
        )
    }
}
