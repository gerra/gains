package app.gains.connectors

import app.gains.csv.ParsedCsv
import app.gains.domain.WeightUnit

/** Liftoff exports lbs at float precision, in the fixed column order below. */
object LiftoffConnector : ImportConnector {
    override val id = "liftoff"
    override val displayName = "Liftoff"
    override val defaultWeightUnit = WeightUnit.LBS
    val header = listOf("Date", "Duration", "Workout Name", "Exercise Name", "Set Order", "Weight", "Reps", "Distance", "Seconds", "RPE", "Notes")
    val spec = ColumnSpec(
        date = listOf("Date"), exercise = listOf("Exercise Name"), weight = listOf("Weight"), reps = listOf("Reps"),
        duration = listOf("Duration"), workoutName = listOf("Workout Name"), setOrder = listOf("Set Order"),
        distance = listOf("Distance"), seconds = listOf("Seconds"), rpe = listOf("RPE"), notes = listOf("Notes"),
    )

    override fun match(header: List<String>): Int = when {
        header == this.header -> 100
        header.containsAll(this.header) && "Workout Notes" !in header && "Weight Unit" !in header -> 60
        else -> 0
    }

    override fun parse(text: String, options: ImportOptions): ParsedCsv =
        WorkoutCsvParser(spec, options.copy(weightUnit = options.weightUnit)).parse(text)
}

/** Strong: same row-per-set layout, weights in the user's unit (a "Weight Unit" column in newer exports). */
object StrongConnector : ImportConnector {
    override val id = "strong"
    override val displayName = "Strong"
    override val defaultWeightUnit = WeightUnit.KG
    val spec = ColumnSpec(
        date = listOf("Date"), exercise = listOf("Exercise Name"), weight = listOf("Weight"), reps = listOf("Reps"),
        duration = listOf("Duration", "Workout Duration"), workoutName = listOf("Workout Name"), setOrder = listOf("Set Order"),
        distance = listOf("Distance"), seconds = listOf("Seconds"), rpe = listOf("RPE"), notes = listOf("Notes"),
        workoutNotes = listOf("Workout Notes"), weightUnit = listOf("Weight Unit"), distanceUnit = listOf("Distance Unit"),
    )

    override fun match(header: List<String>): Int = when {
        "Exercise Name" in header && "Set Order" in header && ("Workout Notes" in header || "Weight Unit" in header) -> 90
        else -> 0
    }

    override fun parse(text: String, options: ImportOptions): ParsedCsv = WorkoutCsvParser(spec, options).parse(text)
}

/** Hevy: one row per set, units in the column names, session bounds as start/end times. */
object HevyConnector : ImportConnector {
    override val id = "hevy"
    override val displayName = "Hevy"
    override val defaultWeightUnit: WeightUnit? = null
    val spec = ColumnSpec(
        date = listOf("start_time"), exercise = listOf("exercise_title"), weight = listOf("weight_kg", "weight_lbs"), reps = listOf("reps"),
        endTime = listOf("end_time"), workoutName = listOf("title"), setOrder = listOf("set_index"),
        distance = listOf("distance_km", "distance_miles"), seconds = listOf("duration_seconds"), rpe = listOf("rpe"),
        notes = listOf("exercise_notes"), workoutNotes = listOf("description"), setType = listOf("set_type"),
        weightUnitByColumn = mapOf("weight_kg" to WeightUnit.KG, "weight_lbs" to WeightUnit.LBS),
        distanceFactorByColumn = mapOf("distance_km" to 1.0, "distance_miles" to 1.609344),
    )

    override fun match(header: List<String>): Int = if ("exercise_title" in header && "start_time" in header) 95 else 0

    override fun parse(text: String, options: ImportOptions): ParsedCsv =
        WorkoutCsvParser(spec, options, parseTimestamp = WorkoutCsvParser::parseNamedMonthTimestamp).parse(text)
}

/** Any row-per-set CSV with recognisable date / exercise / weight / reps columns. */
object GenericCsvConnector : ImportConnector {
    override val id = "csv"
    override val displayName = "Workout CSV"
    override val defaultWeightUnit = WeightUnit.KG
    val spec = ColumnSpec(
        date = listOf("Date", "date", "Timestamp", "timestamp", "start_time", "Start Time"),
        exercise = listOf("Exercise Name", "Exercise", "exercise", "exercise_name", "exercise_title", "Movement"),
        weight = listOf("Weight", "weight", "Weight (kg)", "weight_kg", "Weight (lbs)", "weight_lbs", "Load"),
        reps = listOf("Reps", "reps", "Repetitions"),
        duration = listOf("Duration", "duration", "Workout Duration"), endTime = listOf("end_time", "End Time"),
        workoutName = listOf("Workout Name", "Workout", "title", "Routine"), setOrder = listOf("Set Order", "Set", "set", "set_index", "Set Number"),
        distance = listOf("Distance", "distance", "distance_km", "Distance (km)"), seconds = listOf("Seconds", "seconds", "Time", "duration_seconds"),
        rpe = listOf("RPE", "rpe"), notes = listOf("Notes", "notes", "Note", "exercise_notes"),
        weightUnit = listOf("Weight Unit", "Unit", "unit"),
        weightUnitByColumn = mapOf("Weight (kg)" to WeightUnit.KG, "weight_kg" to WeightUnit.KG, "Weight (lbs)" to WeightUnit.LBS, "weight_lbs" to WeightUnit.LBS),
        distanceFactorByColumn = mapOf("distance_km" to 1.0, "Distance (km)" to 1.0),
    )

    override fun match(header: List<String>): Int =
        if (spec.required.all { candidates -> candidates.any { it in header } }) 10 else 0

    override fun parse(text: String, options: ImportOptions): ParsedCsv =
        WorkoutCsvParser(spec, options, parseTimestamp = WorkoutCsvParser::parseNamedMonthTimestamp).parse(text)
}
