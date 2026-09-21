package app.gains.analysis

import app.gains.domain.Units
import app.gains.domain.WeightUnit
import app.gains.i18n.English
import app.gains.i18n.Strings
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

    /*
     * Anything with a unit word in it is worded by a [Strings]; the language defaults to English so
     * that tests and the shared module's own callers read as before, and the UI passes its own.
     */

    /** "60 kg" / "132.3 lbs" */
    fun weight(kg: Double, unit: WeightUnit, decimals: Int = if (unit == WeightUnit.KG) 2 else 1, strings: Strings = English): String =
        strings.weight(kg, unit, decimals)

    fun weightValue(kg: Double, unit: WeightUnit): String =
        number(Units.display(kg, unit), if (unit == WeightUnit.KG) 2 else 1)

    /** "60 kg × 8" */
    fun set(weightKg: Double, reps: Int, unit: WeightUnit, strings: Strings = English): String = strings.set(weightKg, reps, unit)

    fun percent(fraction: Double): String = number(fraction * 100, 0) + "%"

    fun seconds(seconds: Int, strings: Strings = English): String = strings.seconds(seconds)

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
    fun minutes(minutes: Int, strings: Strings = English): String = strings.minutes(minutes)

    fun km(km: Double, strings: Strings = English): String = strings.km(km)

    /** English only: the UI counts things through its [Strings]. */
    fun plural(count: Int, singular: String, plural: String = singular + "s"): String =
        "$count " + if (count == 1) singular else plural
}
