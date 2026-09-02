package app.gains.importer

import app.gains.csv.CsvFormatException
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

/** A CSV handed to the importer: file name plus its text. */
data class CsvFile(val name: String, val content: String)

/** Orchestrates parse → analyze → commit. Parsing runs off the main thread. */
class ImportService(
    private val sessions: SessionRepository,
    private val exercises: ExerciseRepository,
) {
    suspend fun preview(csvText: String, weightUnit: WeightUnit = WeightUnit.LBS): ImportPreview =
        preview(listOf(CsvFile("export.csv", csvText)), weightUnit)

    /**
     * Previews one or more exports at once. Files are parsed independently, merged (a session
     * present in several files is kept once), then de-duplicated against each other and the database.
     */
    suspend fun preview(files: List<CsvFile>, weightUnit: WeightUnit = WeightUnit.LBS): ImportPreview {
        require(files.isNotEmpty()) { "No files to import." }
        val existing = ExistingData(
            sessions = sessions.summaries(),
            exercises = exercises.exercises(),
            userAliases = exercises.aliases(),
            workingSetRatios = exercises.workingSetRatios(),
            isometricHistory = sessions.isometricHistory(),
        )
        return withContext(Dispatchers.Default) {
            val parser = LiftoffCsvParser(weightUnit)
            val parsed = files.map { file -> file to runCatching { parser.parse(file.content) } }
            val failures = parsed.filter { it.second.isFailure }
            if (failures.size == files.size) {
                throw failures.first().second.exceptionOrNull() as? CsvFormatException
                    ?: CsvFormatException("None of the files could be read.")
            }
            val summaries = parsed.map { (file, result) ->
                val csv = result.getOrNull()
                ImportedFile(
                    name = file.name,
                    rowCount = csv?.rowCount ?: 0,
                    sessionCount = csv?.sessions?.size ?: 0,
                    error = result.exceptionOrNull()?.message,
                )
            }
            val merged = MultiFileMerger.merge(parsed.mapNotNull { it.second.getOrNull() })
            ImportAnalyzer().analyze(merged.csv, existing).copy(
                files = summaries,
                sessionsInSeveralFiles = merged.sessionsInSeveralFiles,
            )
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
