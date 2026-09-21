package app.gains.csv

import app.gains.connectors.ImportOptions
import app.gains.connectors.LiftoffConnector
import app.gains.connectors.WorkoutCsvParser
import app.gains.domain.WeightUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** One set as it appears in an export, after cleaning but before exercise resolution. */
data class RawSet(
    val lineNumber: Int,
    val order: Int,
    val type: app.gains.domain.SetType,
    val weightKg: Double?,
    val reps: Int?,
    val seconds: Int?,
    val distanceKm: Double?,
    val rpe: Double?,
)

data class RawExercise(
    val name: String,
    val sets: List<RawSet>,
    val note: String?,
)

data class RawSession(
    /** Stable identifier derived from the calendar date (ISO-8601). */
    val id: String,
    val timestamp: LocalDateTime,
    val durationMinutes: Int?,
    /** True when the export carried a duration that was discarded as corrupt. */
    val durationDiscarded: Boolean,
    val workoutName: String?,
    val exercises: List<RawExercise>,
    /** Connector id, filled in by the import service. */
    val source: String = "import",
) {
    val date: LocalDate get() = timestamp.date
    val setCount: Int get() = exercises.sumOf { it.sets.size }
}

enum class SkipReason(val label: String) {
    EMPTY_ROW("Empty row (no exercise or metrics)"),
    BAD_DATE("Unparseable date"),
    BAD_NUMBER("Unparseable number"),
    WRONG_COLUMN_COUNT("Wrong number of columns"),
}

data class SkippedRow(val lineNumber: Int, val reason: SkipReason, val excerpt: String)

data class ParsedCsv(
    val sessions: List<RawSession>,
    val skipped: List<SkippedRow>,
    val rowCount: Int,
) {
    val corruptDurationCount: Int get() = sessions.count { it.durationDiscarded }
}

/** Why a file could not be read, so the screen can say so in its own language. */
sealed interface CsvProblem {
    data object Empty : CsvProblem
    data object Unrecognised : CsvProblem
    data class MissingColumns(val columns: List<String>) : CsvProblem
    data class NotLiftoff(val columns: List<String>) : CsvProblem
    data object NoneReadable : CsvProblem

    /** The English wording, for logs and tests; the screen words [CsvProblem] from its resources. */
    val message: String get() = when (this) {
        Empty -> "The file is empty."
        Unrecognised -> "Not a recognised workout export. Expected columns for date, exercise, weight and reps."
        is MissingColumns -> "Missing column(s): ${columns.joinToString()}."
        is NotLiftoff -> "Not a Liftoff export: missing column(s) ${columns.joinToString()}."
        NoneReadable -> "None of the files could be read."
    }
}

/** A file the connectors cannot read. The message is the English wording of [problem]. */
class CsvFormatException(val problem: CsvProblem) : Exception(problem.message)

/** Liftoff's export layout. Thin wrapper over the shared [WorkoutCsvParser]; see [LiftoffConnector]. */
class LiftoffCsvParser(
    weightUnit: WeightUnit = WeightUnit.LBS,
    maxPlausibleDurationMinutes: Int = 4 * 60,
) {
    private val options = ImportOptions(weightUnit, maxPlausibleDurationMinutes)

    fun parse(text: String): ParsedCsv {
        val header = CsvReader.parse(text.take(4000)).firstOrNull()?.fields?.map { it.trim() } ?: throw CsvFormatException(CsvProblem.Empty)
        if (LiftoffConnector.match(header) == 0) {
            val missing = LiftoffConnector.spec.required.map { it.first() }.filter { it !in header }
            throw CsvFormatException(CsvProblem.NotLiftoff(missing.ifEmpty { listOf("Duration") }))
        }
        return LiftoffConnector.parse(text, options)
    }

    internal fun parseDuration(raw: String): Int? = WorkoutCsvParser.parseDurationText(raw)
}
