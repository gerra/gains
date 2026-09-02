package app.gains.connectors

import app.gains.csv.CsvFormatException
import app.gains.csv.CsvReader
import app.gains.csv.ParsedCsv
import app.gains.csv.RawExercise
import app.gains.csv.RawSession
import app.gains.csv.RawSet
import app.gains.csv.SkipReason
import app.gains.csv.SkippedRow
import app.gains.domain.SetType
import app.gains.domain.Units
import app.gains.domain.WeightUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** Which header names carry which field. Several candidates per field; the first present wins. */
data class ColumnSpec(
    val date: List<String>,
    val exercise: List<String>,
    val weight: List<String>,
    val reps: List<String>,
    val duration: List<String> = emptyList(),
    /** Session end timestamp; duration is derived from it when there is no duration column. */
    val endTime: List<String> = emptyList(),
    val workoutName: List<String> = emptyList(),
    val setOrder: List<String> = emptyList(),
    val distance: List<String> = emptyList(),
    val seconds: List<String> = emptyList(),
    val rpe: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val workoutNotes: List<String> = emptyList(),
    /** Column holding "kg"/"lbs" per row, if the format has one. */
    val weightUnit: List<String> = emptyList(),
    val distanceUnit: List<String> = emptyList(),
    /** Column marking a set as warm-up ("warmup", "W"); the ratio rule still applies on top. */
    val setType: List<String> = emptyList(),
    /** Weight columns whose name fixes the unit, e.g. weight_kg -> KG. */
    val weightUnitByColumn: Map<String, WeightUnit> = emptyMap(),
    /** Distance columns whose name fixes the unit, in km per unit. */
    val distanceFactorByColumn: Map<String, Double> = emptyMap(),
) {
    val required: List<List<String>> get() = listOf(date, exercise, weight, reps)
}

/**
 * Row-per-set CSV parser shared by every CSV connector. Handles the defects seen in
 * real exports: arbitrary row order, float-precision unit conversions, corrupt
 * durations, empty rows, mixed set types, shuffled set orders, quoted notes,
 * notes repeated on every set.
 *
 * Duplicate-session and isometric-outlier detection live in the importer, because
 * they need existing history as well as the file.
 */
class WorkoutCsvParser(
    private val spec: ColumnSpec,
    private val options: ImportOptions,
    private val parseTimestamp: (String) -> LocalDateTime? = ::parseIsoLikeTimestamp,
    private val parseDuration: (String) -> Int? = ::parseDurationText,
) {
    fun parse(text: String): ParsedCsv {
        val records = CsvReader.parse(text)
        if (records.isEmpty()) throw CsvFormatException("The file is empty.")
        val header = records.first().fields.map { it.trim() }
        val col = ColumnIndex(header, spec)
        val missing = spec.required.filter { candidates -> candidates.none { it in col.byName } }
        if (missing.isNotEmpty()) {
            throw CsvFormatException("Missing column(s): ${missing.joinToString { it.first() }}.")
        }

        val skipped = ArrayList<SkippedRow>()
        val rows = ArrayList<ParsedRow>()
        for (record in records.drop(1)) {
            val f = record.fields
            if (f.size < header.size) {
                if (f.all { it.isBlank() }) continue
                skipped.add(SkippedRow(record.lineNumber, SkipReason.WRONG_COLUMN_COUNT, excerpt(f)))
                continue
            }
            val exerciseName = f[col.exercise].trim()
            val weight = parseDouble(f[col.weight])
            val reps = parseDouble(f[col.reps])
            val parsedDistance = col.distance?.let { parseDouble(f[it]) }
            val parsedSeconds = col.seconds?.let { parseDouble(f[it]) }
            val distance = parsedDistance ?: 0.0
            val seconds = parsedSeconds ?: 0.0
            if (weight == null || reps == null || (col.distance != null && parsedDistance == null) ||
                (col.seconds != null && parsedSeconds == null)
            ) {
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
            val order = col.setOrder?.let { parseDouble(f[it])?.toInt() } ?: rows.count { it.timestamp == timestamp && it.exerciseName == exerciseName }
            val rpe = col.rpe?.let { parseDouble(f[it]) }?.takeIf { it > 0 }
            val notes = col.notes?.let { f[it].trim() }?.takeIf { it.isNotEmpty() }
            val workoutName = col.workoutName?.let { f[it].trim() }?.takeIf { it.isNotEmpty() }
            val duration = col.duration?.let { parseDuration(f[it]) }
                ?: col.endTime?.let { end -> parseTimestamp(f[end].trim())?.let { minutesBetween(timestamp, it) } }

            val unit = col.weightUnitFor(f)
            val weightKg = if (weight > 0) Units.roundToQuarter(Units.fromDisplay(weight, unit)) else null
            val repsInt = reps.toInt().takeIf { it > 0 }
            val secondsInt = seconds.toInt().takeIf { it > 0 }
            val distanceKm = (distance * col.distanceFactorFor(f)).takeIf { it > 0 }
            val type = classify(weightKg, repsInt, secondsInt, distanceKm)
            val warmup = col.setType?.let { f[it].trim().lowercase() }?.let { it == "warmup" || it == "warm-up" || it == "w" } ?: false

            rows.add(ParsedRow(record.lineNumber, timestamp, duration, workoutName, exerciseName, notes, warmup,
                RawSet(record.lineNumber, order, type, weightKg, repsInt, secondsInt, distanceKm, rpe)))
        }

        val sessions = rows.groupBy { it.timestamp }
            .map { (timestamp, sessionRows) -> buildSession(timestamp, sessionRows) }
            .sortedBy { it.timestamp }
        return ParsedCsv(sessions = sessions, skipped = skipped, rowCount = records.size - 1)
    }

    private fun buildSession(timestamp: LocalDateTime, rows: List<ParsedRow>): RawSession {
        val exercises = LinkedHashMap<String, MutableList<ParsedRow>>()
        for (row in rows) exercises.getOrPut(row.exerciseName) { ArrayList() }.add(row)
        val rawExercises = exercises.map { (name, exerciseRows) ->
            val sets = exerciseRows
                .sortedWith(compareBy({ it.set.order }, { it.lineNumber }))
                .mapIndexed { index, r -> r.set.copy(order = index) }
            val note = exerciseRows.mapNotNull { it.note }.distinct().joinToString("\n").takeIf { it.isNotEmpty() }
            RawExercise(name = name, sets = sets, note = note)
        }
        val declared = rows.mapNotNull { it.duration }.maxOrNull()
        val plausible = declared?.takeIf { it in 1..options.maxPlausibleDurationMinutes }
        return RawSession(
            id = timestamp.toString(),
            timestamp = timestamp,
            durationMinutes = plausible,
            durationDiscarded = declared != null && plausible == null,
            workoutName = rows.firstNotNullOfOrNull { it.workoutName },
            exercises = rawExercises,
        )
    }

    private inner class ColumnIndex(header: List<String>, spec: ColumnSpec) {
        val byName: Map<String, Int> = header.withIndex().associate { (i, n) -> n to i }
        private fun find(candidates: List<String>): Int? = candidates.firstNotNullOfOrNull { byName[it] }
        val date = find(spec.date)!!
        val exercise = find(spec.exercise)!!
        val weight = find(spec.weight)!!
        val reps = find(spec.reps)!!
        val duration = find(spec.duration)
        val endTime = find(spec.endTime)
        val workoutName = find(spec.workoutName)
        val setOrder = find(spec.setOrder)
        val distance = find(spec.distance)
        val seconds = find(spec.seconds)
        val rpe = find(spec.rpe)
        val notes = find(spec.notes)
        val weightUnit = find(spec.weightUnit)
        val distanceUnit = find(spec.distanceUnit)
        val setType = find(spec.setType)
        private val weightColumnName = header[weight]
        private val distanceColumnName = distance?.let { header[it] }

        fun weightUnitFor(fields: List<String>): WeightUnit {
            spec.weightUnitByColumn[weightColumnName]?.let { return it }
            weightUnit?.let { idx ->
                val v = fields[idx].trim().lowercase()
                if (v.startsWith("lb")) return WeightUnit.LBS
                if (v.startsWith("kg")) return WeightUnit.KG
            }
            return options.weightUnit
        }

        fun distanceFactorFor(fields: List<String>): Double {
            distanceColumnName?.let { name -> spec.distanceFactorByColumn[name]?.let { return it } }
            distanceUnit?.let { idx ->
                val v = fields[idx].trim().lowercase()
                if (v.startsWith("mi")) return 1.609344
                if (v == "m" || v.startsWith("met")) return 0.001
            }
            return 1.0
        }
    }

    private class ParsedRow(
        val lineNumber: Int,
        val timestamp: LocalDateTime,
        val duration: Int?,
        val workoutName: String?,
        val exerciseName: String,
        val note: String?,
        val warmup: Boolean,
        val set: RawSet,
    )

    private fun excerpt(fields: List<String>): String = fields.joinToString(",").take(80)

    private fun parseDouble(raw: String): Double? {
        val t = raw.trim()
        if (t.isEmpty()) return 0.0
        return t.replace(',', '.').toDoubleOrNull()
    }

    private fun classify(weightKg: Double?, reps: Int?, seconds: Int?, distanceKm: Double?): SetType = when {
        distanceKm != null -> SetType.CARDIO
        reps != null && weightKg != null -> SetType.WEIGHTED
        reps != null -> SetType.BODYWEIGHT
        seconds != null -> SetType.ISOMETRIC
        else -> SetType.WEIGHTED
    }

    companion object {
        private fun minutesBetween(start: LocalDateTime, end: LocalDateTime): Int? {
            if (end.date != start.date && end < start) return null
            val startMin = start.hour * 60 + start.minute
            val endMin = end.hour * 60 + end.minute + if (end.date > start.date) 24 * 60 else 0
            return (endMin - startMin).takeIf { it > 0 }
        }

        /** "2026-02-18 20:40:47", "2026-02-18T20:40", "2026-02-18". */
        fun parseIsoLikeTimestamp(raw: String): LocalDateTime? {
            val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?").find(raw) ?: return null
            val g = m.groupValues
            return try {
                LocalDateTime(
                    LocalDate(g[1].toInt(), g[2].toInt(), g[3].toInt()),
                    LocalTime(g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt()),
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

        /** "5 Jan 2024, 18:30" (Hevy) and "Jan 5, 2024 6:30 PM" style dates, falling back to ISO. */
        fun parseNamedMonthTimestamp(raw: String): LocalDateTime? {
            parseIsoLikeTimestamp(raw)?.let { return it }
            val t = raw.trim().replace(",", " ").replace(Regex("\\s+"), " ")
            val dmy = Regex("^(\\d{1,2}) ([A-Za-z]{3})[A-Za-z]* (\\d{4})(?: (\\d{1,2}):(\\d{2}))?", RegexOption.IGNORE_CASE).find(t)
            val mdy = Regex("^([A-Za-z]{3})[A-Za-z]* (\\d{1,2}) (\\d{4})(?: (\\d{1,2}):(\\d{2}) ?([AaPp][Mm])?)?").find(t)
            val (day, monthText, year, hourText, minuteText, ampm) = when {
                dmy != null -> dmy.groupValues.let { Six(it[1], it[2], it[3], it[4], it[5], "") }
                mdy != null -> mdy.groupValues.let { Six(it[2], it[1], it[3], it[4], it[5], it[6]) }
                else -> return null
            }
            val month = monthNames.indexOf(monthText.lowercase().take(3)) + 1
            if (month == 0) return null
            var hour = hourText.ifEmpty { "0" }.toInt()
            if (ampm.equals("pm", true) && hour < 12) hour += 12
            if (ampm.equals("am", true) && hour == 12) hour = 0
            return try {
                LocalDateTime(LocalDate(year.toInt(), month, day.toInt()), LocalTime(hour, minuteText.ifEmpty { "0" }.toInt()))
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private data class Six(val a: String, val b: String, val c: String, val d: String, val e: String, val f: String)

        /**
         * "01 hours 41 minutes 04 seconds", "109 hours 36 minutes", "1h 5m", "45m", "1:05:00", "65".
         * Returns whole minutes.
         */
        fun parseDurationText(raw: String): Int? {
            val text = raw.trim().lowercase()
            if (text.isEmpty()) return null
            Regex("^(\\d+):(\\d{2})(?::(\\d{2}))?$").find(text)?.let { m ->
                val (h, mi) = m.destructured
                val s = m.groupValues[3].ifEmpty { "0" }
                return (h.toInt() * 3600 + mi.toInt() * 60 + s.toInt() + 30) / 60
            }
            if (text.all { it.isDigit() }) return text.toInt()
            val hours = Regex("(\\d+)\\s*h").find(text)?.groupValues?.get(1)?.toIntOrNull()
            val minutes = Regex("(\\d+)\\s*m(?!s)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            val seconds = Regex("(\\d+)\\s*s").find(text)?.groupValues?.get(1)?.toIntOrNull()
            if (hours == null && minutes == null && seconds == null) return null
            return ((hours ?: 0) * 3600 + (minutes ?: 0) * 60 + (seconds ?: 0) + 30) / 60
        }
    }
}
