package app.gains.analysis

import app.gains.analysis.Dates.plusDays
import app.gains.domain.Exercise
import app.gains.domain.MuscleGroup
import app.gains.domain.Session
import app.gains.domain.SetType
import kotlinx.datetime.LocalDate

data class WeekVolume(
    val weekStart: LocalDate,
    /** Weighted working-set count per muscle group (1.0 primary, 0.5 secondary). */
    val sets: Map<MuscleGroup, Double>,
    val sessionCount: Int,
) {
    val total: Double get() = sets.values.sum()
}

enum class VolumeStatus { NONE, LOW, OK, HIGH }

object VolumeAnalyzer {
    const val MAINTENANCE_SETS = 8.0
    const val JUNK_SETS = 22.0

    fun status(sets: Double): VolumeStatus = when {
        sets <= 0.0 -> VolumeStatus.NONE
        sets < MAINTENANCE_SETS -> VolumeStatus.LOW
        sets > JUNK_SETS -> VolumeStatus.HIGH
        else -> VolumeStatus.OK
    }

    /** Working sets per muscle group for one session. Cardio sets do not count. */
    fun sessionSets(session: Session, exercisesById: Map<String, Exercise>): Map<MuscleGroup, Double> {
        val result = HashMap<MuscleGroup, Double>()
        for (entry in session.exercises) {
            val exercise = exercisesById[entry.exerciseId] ?: continue
            val working = entry.sets.count { it.isWorking && it.type != SetType.CARDIO }
            if (working == 0) continue
            for (c in exercise.muscleGroups) {
                result[c.group] = (result[c.group] ?: 0.0) + working * c.weight
            }
        }
        return result
    }

    /**
     * Weekly volume for every ISO week between [from] and [to] inclusive; weeks with no
     * training are present with zero sets so charts show the gaps.
     */
    fun weekly(sessions: List<Session>, exercisesById: Map<String, Exercise>, from: LocalDate, to: LocalDate): List<WeekVolume> {
        if (from > to) return emptyList()
        val byWeek = sessions.filter { it.date >= Dates.weekStart(from) && it.date <= to }.groupBy { Dates.weekStart(it.date) }
        return Dates.weeksBetween(from, to).map { week ->
            val weekSessions = byWeek[week] ?: emptyList()
            val totals = HashMap<MuscleGroup, Double>()
            for (s in weekSessions) for ((g, v) in sessionSets(s, exercisesById)) totals[g] = (totals[g] ?: 0.0) + v
            WeekVolume(week, totals, weekSessions.size)
        }
    }

    /** The week containing [today], i.e. the "current week" table. */
    fun currentWeek(sessions: List<Session>, exercisesById: Map<String, Exercise>, today: LocalDate): WeekVolume {
        val start = Dates.weekStart(today)
        return weekly(sessions, exercisesById, start, start.plusDays(6)).first()
    }
}
