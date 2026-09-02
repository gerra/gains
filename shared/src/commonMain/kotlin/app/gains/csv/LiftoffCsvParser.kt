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
    /** Stable identifier derived from the timestamp (ISO-8601, no zone). */
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

class CsvFormatException(message: String) : Exception(message)

/** Liftoff's export layout. Thin wrapper over the shared [WorkoutCsvParser]; see [LiftoffConnector]. */
class LiftoffCsvParser(
    weightUnit: WeightUnit = WeightUnit.LBS,
    maxPlausibleDurationMinutes: Int = 4 * 60,
) {
    private val options = ImportOptions(weightUnit, maxPlausibleDurationMinutes)

    fun parse(text: String): ParsedCsv {
        val header = CsvReader.parse(text.take(4000)).firstOrNull()?.fields?.map { it.trim() } ?: throw CsvFormatException("The file is empty.")
        if (LiftoffConnector.match(header) == 0) {
            val missing = LiftoffConnector.spec.required.map { it.first() }.filter { it !in header }
            throw CsvFormatException("Not a Liftoff export: missing column(s) ${missing.ifEmpty { listOf("Duration") }.joinToString()}.")
        }
        return LiftoffConnector.parse(text, options)
    }

    internal fun parseDuration(raw: String): Int? = WorkoutCsvParser.parseDurationText(raw)
}
