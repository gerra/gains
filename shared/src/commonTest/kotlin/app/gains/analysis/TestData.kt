package app.gains.analysis

import app.gains.catalogue.ExerciseCatalogue
import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

object TestData {
    val bench: Exercise = ExerciseCatalogue.byId("bench_press")!!
    val lateralRaise: Exercise = ExerciseCatalogue.byId("lateral_raise")!!
    val dbPress: Exercise = ExerciseCatalogue.byId("db_shoulder_press")!!
    val plank: Exercise = ExerciseCatalogue.byId("plank")!!
    val pullUp: Exercise = ExerciseCatalogue.byId("pull_up")!!
    val squat: Exercise = ExerciseCatalogue.byId("squat")!!
    val exercises = ExerciseCatalogue.builtIn

    fun weighted(weightKg: Double, reps: Int, order: Int = 0, warmup: Boolean = false) =
        SetEntry(order, SetType.WEIGHTED, weightKg = weightKg, reps = reps, isWarmup = warmup)

    fun session(date: LocalDate, vararg entries: ExerciseEntry, hour: Int = 18): Session {
        val ts = LocalDateTime(date, LocalTime(hour, 0))
        return Session(ts.toString(), ts, 60, entries.toList())
    }

    fun entry(exercise: Exercise, vararg sets: SetEntry) = ExerciseEntry(exercise.id, sets.toList())

    /** [count] sessions of [exercise] every [everyDays] days ending on [end]. */
    fun series(exercise: Exercise, end: LocalDate, count: Int, everyDays: Int, sets: (Int) -> List<SetEntry>): List<Session> =
        (0 until count).map { i ->
            val date = Dates.run { end.minusDays((count - 1 - i) * everyDays) }
            session(date, ExerciseEntry(exercise.id, sets(i)))
        }
}
