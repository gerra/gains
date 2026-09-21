package app.gains.analysis

import app.gains.domain.WeightUnit

/**
 * The words that follow a number, in the language of the screen: "kg", "s", "min"… The UI reads
 * them from its string resources and hands them to the shared formatting helpers, which know the
 * numbers but not the language.
 */
data class UnitLabels(
    val kg: String,
    val lbs: String,
    val second: String,
    val minute: String,
    val hour: String,
    val km: String,
    /** "reps" after a count of repetitions. */
    val reps: String,
) {
    fun unit(unit: WeightUnit): String = when (unit) { WeightUnit.KG -> kg; WeightUnit.LBS -> lbs }
}
