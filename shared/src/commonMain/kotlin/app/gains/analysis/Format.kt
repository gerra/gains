package app.gains.analysis

import app.gains.domain.Units
import app.gains.domain.WeightUnit
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Numbers as text. Anything with a unit word in it takes the [UnitLabels] of the screen's
 * language; the sentences around the numbers live in the UI's string resources.
 */
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
    fun weight(kg: Double, unit: WeightUnit, labels: UnitLabels, decimals: Int = if (unit == WeightUnit.KG) 2 else 1): String =
        number(Units.display(kg, unit), decimals) + " " + labels.unit(unit)

    fun weightValue(kg: Double, unit: WeightUnit): String =
        number(Units.display(kg, unit), if (unit == WeightUnit.KG) 2 else 1)

    /** "60 kg × 8" */
    fun set(weightKg: Double, reps: Int, unit: WeightUnit, labels: UnitLabels): String = "${weight(weightKg, unit, labels)} × $reps"

    fun percent(fraction: Double): String = number(fraction * 100, 0) + "%"

    /** "1:30" past a minute, else "30 s". */
    fun seconds(seconds: Int, labels: UnitLabels): String =
        if (seconds >= 60) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else "$seconds ${labels.second}"

    /** A running clock: "0:42", "12:05", "1:02:34". */
    fun clock(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}"
        else "$m:${sec.toString().padStart(2, '0')}"
    }

    /** "45 min", "1 h", "3 h 42 min". */
    fun minutes(minutes: Int, labels: UnitLabels): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m ${labels.minute}"
            m == 0 -> "$h ${labels.hour}"
            else -> "$h ${labels.hour} $m ${labels.minute}"
        }
    }

    fun km(km: Double, labels: UnitLabels): String = number(km, 2) + " " + labels.km

    /**
     * A lifetime's lifting: "10 t" or "1 250 t" in metric, "25 000 lbs" in pounds, where a tonne is
     * a round thing and a thousand kilograms is not. Under a tonne it is a plain weight.
     */
    fun tonnage(kg: Double, unit: WeightUnit, labels: UnitLabels): String = when (unit) {
        WeightUnit.KG -> if (kg < 1000) weight(kg, unit, labels, 0) else grouped(kg / 1000, if (kg < 10_000) 1 else 0) + " " + labels.tonne
        WeightUnit.LBS -> grouped(Units.kgToLbs(kg), 0) + " " + labels.lbs
    }

    /** [number] with a thin space every three digits: "1 250", "25 000". */
    fun grouped(value: Double, decimals: Int): String {
        val text = number(value, decimals)
        val sign = if (text.startsWith("-")) "-" else ""
        val body = text.removePrefix("-")
        val whole = body.substringBefore('.')
        val frac = if ('.' in body) "." + body.substringAfter('.') else ""
        val groupedWhole = whole.reversed().chunked(3).joinToString("\u202F").reversed()
        return sign + groupedWhole + frac
    }
}
