package app.gains.program

import app.gains.catalogue.ExerciseCatalogue
import app.gains.domain.Modality
import app.gains.domain.ProgramDay

/**
 * Roughly how long a program day takes, so a focus that adds exercises shows what it costs and a
 * newcomer knows what they are signing up for. A heavy set (five reps or fewer, or a GZCLP T1) takes
 * about three and a half minutes with its rest, a hold a minute and a half, any other set two; five
 * minutes for warming up; rounded to five minutes. An estimate, worded "about" on screen.
 */
object DayEstimate {
    fun minutes(day: ProgramDay): Int {
        val work = day.slots.sumOf { slot ->
            val modality = ExerciseCatalogue.byId(slot.exerciseId)?.modality
            val perSet = when {
                modality == Modality.ISOMETRIC -> 1.5
                Gzclp.tierOf(slot) == Gzclp.Tier.T1 || (modality == Modality.WEIGHTED && slot.reps.successReps <= 5) -> 3.5
                else -> 2.0
            }
            slot.sets * perSet
        }
        return (((work + WARM_UP_MINUTES) / 5.0).let { kotlin.math.round(it) } * 5).toInt().coerceAtLeast(5)
    }

    private const val WARM_UP_MINUTES = 5.0
}
