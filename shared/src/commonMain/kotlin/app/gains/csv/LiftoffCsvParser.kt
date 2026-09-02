package app.gains.csv

import app.gains.domain.SetType
import app.gains.domain.Units
import app.gains.domain.WeightUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** One set as it appears in the export, after cleaning but before exercise resolution. */
data class RawSet(
    val lineNumber: Int,
    val order: Int,
    val type: SetType,
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
    /** Stable identifier derived from the export timestamp (ISO-8601, no zone). */
    val id: String,
    val timestamp: LocalDateTime,
    val durationMinutes: Int?,
    /** True when the export carried a duration that was discarded as corrupt. */
    val durationDiscarded: Boolean,
    val workoutName: String?,
    val exercises: List<RawExercise>,
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

/**
 * Parses a Liftoff CSV export into sessions. Handles the export's known defects:
 * arbitrary row order, lbs stored at float precision, corrupt durations, empty
 * rows, mixed set types, out-of-sequence set orders, quoted notes, repeated notes.
 *
 * Duplicate-session and isometric-outlier detection are done by the importer,
 * because they need to see existing history as well as the file.
 */
class LiftoffCsvParser(
    /** Unit the export stores `Weight` in. Liftoff exports lbs. */
    private val weightUnit: WeightUnit = WeightUnit.LBS,
    /** Durations longer than this are treated as a timer left running. */
    private val maxPlausibleDurationMinutes: Int = 4 * 60,
) {
    private companion object {
        val REQUIRED_COLUMNS = listOf("Date", "Exercise Name", "Set Order", "Weight", "Reps", "Distance", "Seconds")
    }

    fun parse(text: String): ParsedCsv {
        val records = CsvReader.parse(text)
        if (records.isEmpty()) throw CsvFormatException("The file is empty.")
        val header = records.first().fields.map { it.trim() }
        val columns = header.withIndex().associate { (i, name) -> name to i }
        val missing = REQUIRED_COLUMNS.filter { it !in columns }
        if (missing.isNotEmpty()) {
            throw CsvFormatException("Not a Liftoff export: missing column(s) ${missing.joinToString()}.")
        }
        val col = ColumnIndex(columns)

        val skipped = ArrayList<SkippedRow>()
        val rows = ArrayList<ParsedRow>()
        for (record in records.drop(1)) {
            val f = record.fields
            if (f.size < header.size) {
                // A short row that is entirely blank is just an empty line.
                if (f.all { it.isBlank() }) continue
                skipped.add(SkippedRow(record.lineNumber, SkipReason.WRONG_COLUMN_COUNT, excerpt(f)))
                continue
            }
            val exerciseName = f[col.exercise].trim()
            val weightRaw = f[col.weight].trim()
            val repsRaw = f[col.reps].trim()
            val distanceRaw = f[col.distance].trim()
            val secondsRaw = f[col.seconds].trim()

            val weight = parseDouble(weightRaw)
            val reps = parseDouble(repsRaw)
            val distance = parseDouble(distanceRaw)
            val seconds = parseDouble(secondsRaw)
            if (weight == null || reps == null || distance == null || seconds == null) {
                skipped.add(SkippedRow(record.lineNumber, SkipReason.BAD_NUMBER, excerpt(f)))
                continue
            }
            val hasMetrics = weight > 0 || reps > 0 || distance > 0 || seconds > 0
            if (exerciseName.isEmpty() || !hasMetrics) {
                skipped.add(SkippedRow(record.lineNumber, SkipReason.EMPTY_ROW, excerpt(f)))
                continue
            }
            val timestamp = parseTimestamp(f[col.date].trim())
            if (timestamp == null) {
                skipped.add(SkippedRow(record.lineNumber, SkipReason.BAD_DATE, excerpt(f)))
                continue
            }
            val order = parseDouble(f[col.setOrder].trim())?.toInt() ?: 0
            val rpe = col.rpe?.let { parseDouble(f[it].trim()) }?.takeIf { it > 0 }
            val notes = col.notes?.let { f[it].trim() }?.takeIf { it.isNotEmpty() }
            val workoutName = col.workoutName?.let { f[it].trim() }?.takeIf { it.isNotEmpty() }
            val duration = col.duration?.let { parseDuration(f[it]) }

            val weightKg = if (weight > 0) Units.roundToQuarter(Units.fromDisplay(weight, weightUnit)) else null
            val repsInt = reps.toInt().takeIf { it > 0 }
            val secondsInt = seconds.toInt().takeIf { it > 0 }
            val distanceKm = distance.takeIf { it > 0 }
            val type = classify(weightKg, repsInt, secondsInt, distanceKm)

            rows.add(
                ParsedRow(
                    lineNumber = record.lineNumber,
                    timestamp = timestamp,
                    duration = duration,
                    workoutName = workoutName,
                    exerciseName = exerciseName,
                    note = notes,
                    set = RawSet(record.lineNumber, order, type, weightKg, repsInt, secondsInt, distanceKm, rpe),
                )
            )
        }

        val sessions = rows
            .groupBy { it.timestamp }
            .map { (timestamp, sessionRows) -> buildSession(timestamp, sessionRows) }
            .sortedBy { it.timestamp }

        return ParsedCsv(sessions = sessions, skipped = skipped, rowCount = records.size - 1)
    }

    private fun buildSession(timestamp: LocalDateTime, rows: List<ParsedRow>): RawSession {
        // Exercises keep the order in which they first appear in the file for this session;
        // sets inside an exercise are ordered by the (0-indexed, sometimes shuffled) Set Order.
        val exercises = LinkedHashMap<String, MutableList<ParsedRow>>()
        for (row in rows) exercises.getOrPut(row.exerciseName) { ArrayList() }.add(row)

        val rawExercises = exercises.map { (name, exerciseRows) ->
            val sets = exerciseRows
                .sortedWith(compareBy({ it.set.order }, { it.lineNumber }))
                .mapIndexed { index, r -> r.set.copy(order = index) }
            // Notes are repeated on every set of the exercise; keep the distinct ones.
            val note = exerciseRows.mapNotNull { it.note }.distinct().joinToString("\n").takeIf { it.isNotEmpty() }
            RawExercise(name = name, sets = sets, note = note)
        }

        val durations = rows.mapNotNull { it.duration }
        val declared = durations.maxOrNull()
        val plausible = declared?.takeIf { it in 1..maxPlausibleDurationMinutes }
        val workoutName = rows.firstNotNullOfOrNull { it.workoutName }
        return RawSession(
            id = sessionId(timestamp),
            timestamp = timestamp,
            durationMinutes = plausible,
            durationDiscarded = declared != null && plausible == null,
            workoutName = workoutName,
            exercises = rawExercises,
        )
    }

    private class ColumnIndex(columns: Map<String, Int>) {
        val date = columns.getValue("Date")
        val duration = columns["Duration"]
        val workoutName = columns["Workout Name"]
        val exercise = columns.getValue("Exercise Name")
        val setOrder = columns.getValue("Set Order")
        val weight = columns.getValue("Weight")
        val reps = columns.getValue("Reps")
        val distance = columns.getValue("Distance")
        val seconds = columns.getValue("Seconds")
        val rpe = columns["RPE"]
        val notes = columns["Notes"]
    }

    private class ParsedRow(
        val lineNumber: Int,
        val timestamp: LocalDateTime,
        val duration: Int?,
        val workoutName: String?,
        val exerciseName: String,
        val note: String?,
        val set: RawSet,
    )

    private fun excerpt(fields: List<String>): String = fields.joinToString(",").take(80)

    private fun parseDouble(raw: String): Double? {
        if (raw.isEmpty()) return 0.0
        return raw.toDoubleOrNull()
    }

    private fun classify(weightKg: Double?, reps: Int?, seconds: Int?, distanceKm: Double?): SetType = when {
        distanceKm != null -> SetType.CARDIO
        reps != null && weightKg != null -> SetType.WEIGHTED
        reps != null -> SetType.BODYWEIGHT
        seconds != null -> SetType.ISOMETRIC
        // Weight logged but no reps, seconds or distance: treat as a weighted set with unknown reps.
        else -> SetType.WEIGHTED
    }

    /** Parses "01 hours 41 minutes 04 seconds" and truncated variants such as "109 hours 36 minutes". */
    internal fun parseDuration(raw: String): Int? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val hours = Regex("(\\d+)\\s*hour").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val minutes = Regex("(\\d+)\\s*min").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val seconds = Regex("(\\d+)\\s*sec").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (hours == null && minutes == null && seconds == null) return null
        val totalSeconds = (hours ?: 0) * 3600 + (minutes ?: 0) * 60 + (seconds ?: 0)
        return (totalSeconds + 30) / 60
    }

    private fun parseTimestamp(raw: String): LocalDateTime? {
        // "2026-02-18 20:40:47" – also tolerate an ISO "T" separator and a missing seconds field.
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?").find(raw) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        val s = m.groupValues[6].ifEmpty { "0" }
        return try {
            LocalDateTime(LocalDate(y.toInt(), mo.toInt(), d.toInt()), LocalTime(h.toInt(), mi.toInt(), s.toInt()))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun sessionId(timestamp: LocalDateTime): String = timestamp.toString()
}
