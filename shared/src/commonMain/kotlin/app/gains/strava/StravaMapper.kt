package app.gains.strava

import app.gains.analysis.Format
import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.Modality
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import app.gains.domain.WeightUnit
import app.gains.importer.ExerciseResolver
import kotlinx.datetime.LocalDateTime
import kotlin.math.roundToInt

/**
 * Pure translation between Strava activities and Gains sessions. A downloaded activity
 * becomes a one-exercise cardio session; an uploaded session becomes a manual activity whose
 * description lists every set.
 */
object StravaMapper {
    /**
     * Gym sessions on Strava are what Gains itself uploads, and a bare "Workout" carries no
     * sets to analyse, so neither is imported as a session.
     */
    val skippedSportTypes: Set<String> = setOf("WeightTraining", "Workout")

    private val sportToExercise: Map<String, String> = mapOf(
        "Run" to "running", "VirtualRun" to "running",
        "TrailRun" to "trail_running", "Hike" to "trail_running",
        "Walk" to "walking",
        "Ride" to "cycling", "VirtualRide" to "cycling", "GravelRide" to "cycling", "MountainBikeRide" to "cycling",
        "EBikeRide" to "cycling", "EMountainBikeRide" to "cycling", "Handcycle" to "cycling", "Velomobile" to "cycling",
        "Swim" to "swimming",
        "Rowing" to "rowing", "VirtualRow" to "rowing",
        "Elliptical" to "elliptical",
        "StairStepper" to "stair_climber",
        "IceSkate" to "skating", "InlineSkate" to "skating",
        "HighIntensityIntervalTraining" to "hiit", "Crossfit" to "hiit",
    )

    private val exerciseToSport: Map<String, String> = mapOf(
        "running" to "Run", "trail_running" to "TrailRun", "walking" to "Walk",
        "cycling" to "Ride", "recumbent_bike" to "Ride",
        "swimming" to "Swim", "rowing" to "Rowing",
        "elliptical" to "Elliptical", "stair_climber" to "StairStepper",
        "skating" to "InlineSkate", "hiit" to "HighIntensityIntervalTraining",
    )

    /** Gym cardio machines: uploaded with Strava's "trainer" flag. */
    private val indoorExercises = setOf("recumbent_bike", "rowing", "elliptical", "stair_climber")

    const val SPORT_WEIGHT_TRAINING = "WeightTraining"
    const val SPORT_WORKOUT = "Workout"

    fun sessionId(activityId: Long): String = "strava-$activityId"

    /** The catalogue exercise for a Strava sport type, or null when it has to be created by name. */
    fun exerciseIdFor(sportType: String): String? = sportToExercise[sportType]

    /** "AlpineSki" -> "Alpine Ski", "EBikeRide" -> "E Bike Ride". */
    fun humanize(sportType: String): String =
        sportType.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").trim().ifBlank { "Activity" }

    /**
     * Turns an activity into a session, or returns null for sport types Gains leaves out and
     * for activities whose start time cannot be read. Unknown sports become custom cardio exercises.
     */
    fun toSession(activity: StravaActivity, resolver: ExerciseResolver): Session? {
        val sport = activity.sport
        if (sport in skippedSportTypes) return null
        val timestamp = parseLocal(activity.startDateLocal) ?: return null
        val exercise = exerciseIdFor(sport)?.let { resolver.exercise(it) } ?: resolver.resolve(humanize(sport), listOf(SetType.CARDIO))
        val seconds = activity.movingTime.takeIf { it > 0 } ?: activity.elapsedTime.takeIf { it > 0 }
        val km = ((activity.distance / 10.0).roundToInt() / 100.0).takeIf { it > 0 }
        val set = SetEntry(order = 0, type = SetType.CARDIO, seconds = seconds, distanceKm = km)
        val note = buildList {
            activity.name.trim().takeIf { it.isNotEmpty() }?.let(::add)
            if (activity.elevationGain > 0) add("↑ ${activity.elevationGain.roundToInt()} m")
            activity.averageHeartrate?.let { add("avg HR ${it.roundToInt()} bpm") }
            if (activity.trainer) add("indoor")
        }.joinToString(" · ").ifBlank { null }
        return Session(
            id = sessionId(activity.id),
            timestamp = timestamp,
            durationMinutes = (activity.elapsedTime / 60.0).roundToInt().takeIf { it > 0 },
            exercises = listOf(ExerciseEntry(exercise.id, listOf(set), note)),
            source = Session.STRAVA,
        )
    }

    private val localPrefix = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2})?)")

    /** Reads Strava's local timestamps, ignoring the trailing Z, fractions or an offset. */
    fun parseLocal(iso: String): LocalDateTime? =
        localPrefix.find(iso.trim())?.groupValues?.get(1)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    /** "2026-09-05T18:30:00Z", the shape Strava expects for `start_date_local`. */
    fun formatLocal(t: LocalDateTime): String {
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${t.year.toString().padStart(4, '0')}-${two(t.month.ordinal + 1)}-${two(t.day)}T${two(t.hour)}:${two(t.minute)}:${two(t.second)}Z"
    }

    /** True when the session is a single cardio exercise, which maps to a sport of its own on Strava. */
    fun isCardioOnly(session: Session, exercisesById: Map<String, Exercise>): Boolean =
        session.exercises.size == 1 && exercisesById[session.exercises.single().exerciseId]?.modality == Modality.CARDIO

    /**
     * The manual activity to create for [session]. Cardio-only sessions become the matching
     * sport with distance and time; everything else is a WeightTraining activity. [name]
     * overrides the title (the program day, when known).
     */
    fun toActivity(session: Session, exercisesById: Map<String, Exercise>, unit: WeightUnit, name: String? = null): NewStravaActivity {
        val cardioOnly = isCardioOnly(session, exercisesById)
        val onlyExercise = session.exercises.singleOrNull()?.let { exercisesById[it.exerciseId] }
        val cardioSets = session.exercises.flatMap { it.sets }.filter { it.type == SetType.CARDIO }
        val distanceKm = cardioSets.sumOf { it.distanceKm ?: 0.0 }.takeIf { it > 0 }
        val cardioSeconds = cardioSets.sumOf { it.seconds ?: 0 }
        val elapsed = session.durationMinutes?.let { it * 60 } ?: cardioSeconds.takeIf { it > 0 } ?: estimateSeconds(session)
        val sport = if (cardioOnly) exerciseToSport[onlyExercise!!.id] ?: SPORT_WORKOUT else SPORT_WEIGHT_TRAINING
        val title = name?.takeIf { it.isNotBlank() } ?: if (cardioOnly) onlyExercise!!.name else "Workout"
        return NewStravaActivity(
            name = title,
            sportType = sport,
            startDateLocal = formatLocal(session.timestamp),
            elapsedSeconds = elapsed,
            description = describe(session, exercisesById, unit) + "\n\nLogged with Gains",
            distanceMetres = distanceKm?.let { (it * 1000).roundToInt().toDouble() },
            trainer = cardioOnly && onlyExercise!!.id in indoorExercises,
        )
    }

    /** No duration recorded: three minutes a set, never under 20 or over 180 minutes. */
    fun estimateSeconds(session: Session): Int = (session.setCount * 3 * 60).coerceIn(20 * 60, 180 * 60)

    /** One line per exercise: `Bench Press: 60 kg × 5, 5, 5 · 62.5 kg × 3`. */
    fun describe(session: Session, exercisesById: Map<String, Exercise>, unit: WeightUnit): String =
        session.exercises.joinToString("\n") { entry ->
            val label = exercisesById[entry.exerciseId]?.name ?: entry.exerciseId
            val note = entry.note?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
            "$label: ${describeSets(entry.sets, unit)}$note"
        }

    fun describeSets(sets: List<SetEntry>, unit: WeightUnit): String {
        if (sets.isEmpty()) return "no sets"
        val groups = ArrayList<Pair<String, MutableList<String>>>()
        for (set in sets) {
            val (prefix, item) = when (set.type) {
                SetType.WEIGHTED -> (set.weightKg?.let { Format.weight(it, unit) } ?: "") to (set.reps?.toString() ?: "–")
                SetType.BODYWEIGHT -> "" to (set.reps?.toString() ?: "–")
                SetType.ISOMETRIC -> (set.weightKg?.let { Format.weight(it, unit) } ?: "") to (set.seconds?.let { Format.seconds(it) } ?: "–")
                SetType.CARDIO -> "" to listOfNotNull(set.distanceKm?.let { Format.km(it) }, set.seconds?.let { "in ${Format.seconds(it)}" }).joinToString(" ").ifBlank { "–" }
            }
            val last = groups.lastOrNull()
            if (last != null && last.first == prefix) last.second.add(item) else groups.add(prefix to mutableListOf(item))
        }
        return groups.joinToString(" · ") { (prefix, items) ->
            if (prefix.isEmpty()) items.joinToString(", ") else "$prefix × ${items.joinToString(", ")}"
        }
    }
}
