package app.gains.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** Muscle groups tracked by the volume dashboard and the neglect insight. */
enum class MuscleGroup(val displayName: String) {
    CHEST("Chest"),
    FRONT_DELTS("Front delts"),
    SIDE_DELTS("Side delts"),
    REAR_DELTS("Rear delts"),
    LATS("Lats"),
    UPPER_BACK("Upper back"),
    LOWER_BACK("Lower back"),
    TRAPS("Traps"),
    BICEPS("Biceps"),
    TRICEPS("Triceps"),
    FOREARMS("Forearms"),
    QUADS("Quads"),
    HAMSTRINGS("Hamstrings"),
    GLUTES("Glutes"),
    CALVES("Calves"),
    CORE("Core"),
    NECK("Neck"),
}

/** How an exercise is normally loaded. Drives which performance metric is tracked. */
enum class Modality { WEIGHTED, BODYWEIGHT, ISOMETRIC, CARDIO }

/** Classification of a single logged set (see parsing rule 6). */
enum class SetType { WEIGHTED, BODYWEIGHT, ISOMETRIC, CARDIO }

/** A muscle group and how much one set of the exercise counts towards it (1.0 primary, 0.5 secondary). */
data class MuscleContribution(val group: MuscleGroup, val weight: Double)

data class Exercise(
    val id: String,
    val name: String,
    val canonicalName: String,
    val muscleGroups: List<MuscleContribution>,
    val modality: Modality,
    /** Per-dumbbell loads are logged for these; the UI labels weights accordingly. */
    val isDumbbell: Boolean = false,
    val isBuiltIn: Boolean = false,
)

data class SetEntry(
    val order: Int,
    val type: SetType,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val distanceKm: Double? = null,
    val rpe: Double? = null,
    val isWarmup: Boolean = false,
) {
    val isWorking: Boolean get() = !isWarmup
    val volumeKg: Double get() = (weightKg ?: 0.0) * (reps ?: 0)
}

data class ExerciseEntry(
    val exerciseId: String,
    val sets: List<SetEntry>,
    val note: String? = null,
) {
    val workingSets: List<SetEntry> get() = sets.filter { it.isWorking }
}

data class Session(
    val id: String,
    val timestamp: LocalDateTime,
    val durationMinutes: Int? = null,
    val exercises: List<ExerciseEntry>,
) {
    val date: LocalDate get() = timestamp.date
    val setCount: Int get() = exercises.sumOf { it.sets.size }
}

data class BodyweightEntry(val date: LocalDate, val weightKg: Double)

enum class WeightUnit(val label: String) { KG("kg"), LBS("lbs") }

object Units {
    const val LBS_PER_KG = 2.20462262185

    fun lbsToKg(lbs: Double): Double = lbs / LBS_PER_KG
    fun kgToLbs(kg: Double): Double = kg * LBS_PER_KG

    /** Round to the nearest quarter kilogram: the canonical stored precision. */
    fun roundToQuarter(kg: Double): Double = kotlin.math.round(kg * 4.0) / 4.0

    fun display(kg: Double, unit: WeightUnit): Double = when (unit) {
        WeightUnit.KG -> kg
        WeightUnit.LBS -> kgToLbs(kg)
    }

    fun fromDisplay(value: Double, unit: WeightUnit): Double = when (unit) {
        WeightUnit.KG -> value
        WeightUnit.LBS -> lbsToKg(value)
    }
}
