package app.gains.analysis

import app.gains.domain.Units
import app.gains.domain.WeightUnit
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

object Format {
    fun number(value: Double, decimals: Int = 1): String {
        if (decimals == 0) return value.roundToInt().toString()
        var factor = 1.0
        repeat(decimals) { factor *= 10 }
        val rounded = round(abs(value) * factor) / factor
        val whole = rounded.toLong()
        var frac = ((rounded - whole) * factor).roundToInt()
        var wholeAdj = whole
        if (frac >= factor.toInt()) { frac = 0; wholeAdj += 1 }
        val fracText = frac.toString().padStart(decimals, '0').trimEnd('0')
        val sign = if (value < 0 && (wholeAdj != 0L || frac != 0)) "-" else ""
        return if (fracText.isEmpty()) "$sign$wholeAdj" else "$sign$wholeAdj.$fracText"
    }

    /** "60 kg" / "132.3 lbs" */
    fun weight(kg: Double, unit: WeightUnit, decimals: Int = if (unit == WeightUnit.KG) 2 else 1): String =
        number(Units.display(kg, unit), decimals) + " " + unit.label

    fun weightValue(kg: Double, unit: WeightUnit): String =
        number(Units.display(kg, unit), if (unit == WeightUnit.KG) 2 else 1)

    /** "60 kg × 8" */
    fun set(weightKg: Double, reps: Int, unit: WeightUnit): String = "${weight(weightKg, unit)} × $reps"

    fun percent(fraction: Double): String = number(fraction * 100, 0) + "%"

    fun seconds(seconds: Int): String = if (seconds >= 60) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else "$seconds s"

    fun km(km: Double): String = number(km, 2) + " km"

    fun plural(count: Int, singular: String, plural: String = singular + "s"): String =
        "$count " + if (count == 1) singular else plural
}
