package app.gains.analysis

import app.gains.analysis.Dates.minusDays
import app.gains.domain.Exercise
import app.gains.domain.Modality
import app.gains.domain.MuscleGroup
import app.gains.domain.Session
import app.gains.domain.WeightUnit
import app.gains.i18n.English
import app.gains.i18n.Strings
import kotlinx.datetime.LocalDate

/**
 * Judgement calls behind each insight, gathered in one place so they can be tuned.
 * Every value is a default that has not been signed off by the product owner.
 */
data class InsightThresholds(
    /** "Current best" is the best performance within this many days. */
    val regressionWindowDays: Int = 30,
    /** A drop smaller than this fraction of the all-time best is noise, not a regression. */
    val regressionMinDropFraction: Double = 0.05,
    /** Top working weight unchanged for at least this long is a stall… */
    val stallWeeks: Int = 6,
    /** …provided the exercise was trained at least this many times in that period. */
    val stallMinSessions: Int = 4,
    /** An exercise must have been trained within this many days to be "stalled" rather than "neglected". */
    val activeWindowDays: Int = 28,
    /** An exercise missing for this many weeks counts as dropped… */
    val neglectExerciseWeeks: Int = 4,
    /** …if it was trained at least this many times in the [neglectExerciseLookbackWeeks] before that. */
    val neglectExerciseMinPriorSessions: Int = 4,
    val neglectExerciseLookbackWeeks: Int = 12,
    /** Muscle group averaging fewer working sets/week than this over the recent period is neglected… */
    val neglectMuscleMinWeeklySets: Double = 4.0,
    /** …if it previously averaged at least this many. */
    val neglectMuscleBaselineWeeklySets: Double = 8.0,
    val neglectMuscleRecentWeeks: Int = 2,
    val neglectMuscleBaselineWeeks: Int = 8,
    /** Minimum improvement over the previous best to be reported as progress. */
    val progressMinGainFraction: Double = 0.025,
    /** Sessions/week change smaller than this fraction is "steady". */
    val consistencyTrendFraction: Double = 0.15,
    val consistencyWeeks: Int = 4,
)

/** [label] is the English name; the UI shows `Strings.insightKind`. */
enum class InsightKind(val label: String) {
    REGRESSION("Regression"),
    STALL("Stall"),
    NEGLECT("Neglect"),
    CONSISTENCY("Consistency"),
    PROGRESS("Progress"),
}

data class Insight(
    val kind: InsightKind,
    /** Higher sorts first. */
    val severity: Double,
    val title: String,
    val detail: String,
    val exerciseId: String? = null,
    val muscleGroup: MuscleGroup? = null,
    /** Signed change behind the insight (−0.18 for an 18% regression, +0.13 for progress), when there is one. */
    val delta: Double? = null,
    /** Sessions the text refers to, in the order they are mentioned, so the UI can link to each. */
    val sessions: List<SessionRef> = emptyList(),
)

/** A session an insight points at. */
data class SessionRef(val id: String, val date: LocalDate)

private fun ExerciseSessionPoint.ref() = SessionRef(sessionId, date)

enum class Trend { UP, DOWN, FLAT }

data class ConsistencyStats(
    val recentSessionsPerWeek: Double,
    val previousSessionsPerWeek: Double?,
    val trend: Trend,
    val weeks: Int,
)

/**
 * Pure functions: sessions + exercises in, ordered insights out. No I/O, no clocks. The text of
 * each insight is worded by [strings]: English unless told otherwise, the UI's language when the
 * UI asks.
 */
class InsightEngine(
    private val thresholds: InsightThresholds = InsightThresholds(),
    private val unit: WeightUnit = WeightUnit.KG,
    private val strings: Strings = English,
) {
    fun generate(sessions: List<Session>, exercises: List<Exercise>, today: LocalDate): List<Insight> {
        if (sessions.isEmpty()) return emptyList()
        val byId = exercises.associateBy { it.id }
        val sorted = sessions.sortedBy { it.timestamp }
        val insights = ArrayList<Insight>()
        val regressed = HashSet<String>()

        val trained = sorted.flatMap { s -> s.exercises.map { it.exerciseId } }.distinct().mapNotNull { byId[it] }
        val histories = trained.associateWith { ExerciseAnalysis.history(sorted, it) }

        for ((exercise, history) in histories) {
            regression(exercise, history, today)?.let { insights.add(it); regressed.add(exercise.id) }
        }
        for ((exercise, history) in histories) {
            if (exercise.id in regressed) continue
            stall(exercise, history, today)?.let { insights.add(it) }
        }
        for ((exercise, history) in histories) {
            neglectedExercise(exercise, history, today)?.let { insights.add(it) }
        }
        insights.addAll(neglectedMuscles(sorted, byId, today))
        consistency(sorted, today)?.let { insights.add(it) }
        for ((exercise, history) in histories) {
            progress(exercise, history, today)?.let { insights.add(it) }
        }
        return insights.sortedByDescending { it.severity }
    }

    // ---- Regression -----------------------------------------------------------------------

    fun regression(exercise: Exercise, history: List<ExerciseSessionPoint>, today: LocalDate): Insight? {
        val points = history.filter { it.best != null }
        if (points.size < 2) return null
        val windowStart = today.minusDays(thresholds.regressionWindowDays)
        val recent = points.filter { it.date >= windowStart }
        if (recent.isEmpty()) return null
        val current = recent.maxBy { it.best!!.value }
        val earlier = points.filter { it.date < windowStart }
        if (earlier.isEmpty()) return null
        val allTime = earlier.maxBy { it.best!!.value }
        val best = allTime.best!!.value
        if (best <= 0) return null
        val drop = (best - current.best!!.value) / best
        if (drop < thresholds.regressionMinDropFraction) return null
        val detail = strings.regressionDetail(
            current.best.describe(exercise.modality, unit, strings), allTime.best.describe(exercise.modality, unit, strings),
            strings.dateContextual(allTime.date, today), Format.percent(drop),
        )
        return Insight(
            kind = InsightKind.REGRESSION,
            severity = 100 + drop * 100,
            title = strings.exerciseName(exercise),
            detail = detail,
            exerciseId = exercise.id,
            delta = -drop,
            sessions = listOf(current.ref(), allTime.ref()),
        )
    }

    // ---- Stall ------------------------------------------------------------------------------

    fun stall(exercise: Exercise, history: List<ExerciseSessionPoint>, today: LocalDate): Insight? {
        if (exercise.modality == Modality.CARDIO) return null
        val points = history.filter { stallValue(it, exercise.modality) != null }
        if (points.isEmpty()) return null
        val last = points.last()
        if (last.date < today.minusDays(thresholds.activeWindowDays)) return null
        val top = points.maxOf { stallValue(it, exercise.modality)!! }
        val firstAtTop = points.first { stallValue(it, exercise.modality)!! >= top - 1e-9 }
        val stalledDays = Dates.daysBetween(firstAtTop.date, today)
        if (stalledDays < thresholds.stallWeeks * 7) return null
        val sessionsSince = points.count { it.date >= firstAtTop.date }
        if (sessionsSince < thresholds.stallMinSessions) return null
        val weeks = stalledDays / 7
        val value = when (exercise.modality) {
            Modality.WEIGHTED -> strings.weight(top, unit)
            else -> firstAtTop.best!!.describe(exercise.modality, unit, strings)
        }
        val detail = strings.stallDetail(value, strings.dateContextual(firstAtTop.date, today), sessionsSince, weeks)
        return Insight(
            kind = InsightKind.STALL,
            severity = 40.0 + weeks,
            title = strings.exerciseName(exercise),
            detail = detail,
            exerciseId = exercise.id,
            sessions = listOf(firstAtTop.ref()),
        )
    }

    private fun stallValue(point: ExerciseSessionPoint, modality: Modality): Double? = when (modality) {
        Modality.WEIGHTED -> point.topSetWeightKg
        else -> point.best?.value
    }

    // ---- Neglect ----------------------------------------------------------------------------

    fun neglectedExercise(exercise: Exercise, history: List<ExerciseSessionPoint>, today: LocalDate): Insight? {
        if (history.isEmpty()) return null
        val absentSince = today.minusDays(thresholds.neglectExerciseWeeks * 7)
        val last = history.last()
        if (last.date >= absentSince) return null
        val lookbackStart = absentSince.minusDays(thresholds.neglectExerciseLookbackWeeks * 7)
        val prior = history.count { it.date >= lookbackStart && it.date < absentSince }
        if (prior < thresholds.neglectExerciseMinPriorSessions) return null
        val weeksAgo = Dates.daysBetween(last.date, today) / 7
        val detail = strings.neglectedExerciseDetail(strings.dateContextual(last.date, today), weeksAgo, prior, thresholds.neglectExerciseLookbackWeeks)
        return Insight(
            kind = InsightKind.NEGLECT,
            severity = 60.0 + weeksAgo,
            title = strings.exerciseName(exercise),
            detail = detail,
            exerciseId = exercise.id,
            sessions = listOf(last.ref()),
        )
    }

    fun neglectedMuscles(sessions: List<Session>, exercisesById: Map<String, Exercise>, today: LocalDate): List<Insight> {
        val recentStart = Dates.weekStart(today).minusDays((thresholds.neglectMuscleRecentWeeks - 1) * 7)
        val baselineStart = recentStart.minusDays(thresholds.neglectMuscleBaselineWeeks * 7)
        val weeks = VolumeAnalyzer.weekly(sessions, exercisesById, baselineStart, today)
        if (weeks.isEmpty()) return emptyList()
        val recent = weeks.filter { it.weekStart >= recentStart }
        val baseline = weeks.filter { it.weekStart < recentStart }
        if (baseline.size < thresholds.neglectMuscleBaselineWeeks / 2) return emptyList()
        val result = ArrayList<Insight>()
        for (group in MuscleGroup.entries) {
            val recentAvg = recent.sumOf { it.sets[group] ?: 0.0 } / thresholds.neglectMuscleRecentWeeks
            val baselineAvg = baseline.sumOf { it.sets[group] ?: 0.0 } / thresholds.neglectMuscleBaselineWeeks
            if (baselineAvg >= thresholds.neglectMuscleBaselineWeeklySets && recentAvg < thresholds.neglectMuscleMinWeeklySets) {
                val detail = strings.neglectedMuscleDetail(Format.number(recentAvg, 1), thresholds.neglectMuscleRecentWeeks, Format.number(baselineAvg, 1), thresholds.neglectMuscleBaselineWeeks)
                result.add(Insight(InsightKind.NEGLECT, 60.0 + (baselineAvg - recentAvg), strings.muscleGroup(group), detail, muscleGroup = group, delta = if (baselineAvg > 0) -(baselineAvg - recentAvg) / baselineAvg else null))
            }
        }
        return result
    }

    // ---- Consistency ------------------------------------------------------------------------

    fun consistencyStats(sessions: List<Session>, today: LocalDate): ConsistencyStats? {
        if (sessions.isEmpty()) return null
        val weeks = thresholds.consistencyWeeks
        val recentStart = today.minusDays(weeks * 7 - 1)
        val previousStart = recentStart.minusDays(weeks * 7)
        val firstDate = sessions.minOf { it.date }
        val recent = sessions.count { it.date >= recentStart && it.date <= today }
        val previous = sessions.count { it.date >= previousStart && it.date < recentStart }
        val recentRate = recent.toDouble() / weeks
        val previousRate = if (firstDate < recentStart) previous.toDouble() / weeks else null
        val trend = when {
            previousRate == null || previousRate == 0.0 -> if (recentRate > 0 && previousRate == 0.0) Trend.UP else Trend.FLAT
            (recentRate - previousRate) / previousRate >= thresholds.consistencyTrendFraction -> Trend.UP
            (previousRate - recentRate) / previousRate >= thresholds.consistencyTrendFraction -> Trend.DOWN
            else -> Trend.FLAT
        }
        return ConsistencyStats(recentRate, previousRate, trend, weeks)
    }

    fun consistency(sessions: List<Session>, today: LocalDate): Insight? {
        val stats = consistencyStats(sessions, today) ?: return null
        val recentText = strings.consistencyRecent(Format.number(stats.recentSessionsPerWeek, 1), stats.weeks)
        val previous = stats.previousSessionsPerWeek?.let { Format.number(it, 1) }
        val detail = when {
            previous == null -> "$recentText."
            stats.trend == Trend.UP -> strings.consistencyUp(recentText, previous, stats.weeks)
            stats.trend == Trend.DOWN -> strings.consistencyDown(recentText, previous, stats.weeks)
            else -> strings.consistencySteady(recentText, previous, stats.weeks)
        }
        val severity = when (stats.trend) {
            Trend.DOWN -> 50.0
            Trend.FLAT -> 20.0
            Trend.UP -> 15.0
        }
        val title = when (stats.trend) {
            Trend.DOWN -> strings.trainingLessOften
            Trend.UP -> strings.trainingMoreOften
            Trend.FLAT -> strings.steadyFrequency
        }
        val delta = stats.previousSessionsPerWeek?.takeIf { it > 0 }?.let { (stats.recentSessionsPerWeek - it) / it }
        return Insight(InsightKind.CONSISTENCY, severity, title, detail, delta = delta)
    }

    // ---- Progress ---------------------------------------------------------------------------

    fun progress(exercise: Exercise, history: List<ExerciseSessionPoint>, today: LocalDate): Insight? {
        val points = history.filter { it.best != null }
        if (points.size < 2) return null
        val windowStart = today.minusDays(thresholds.regressionWindowDays)
        val recent = points.filter { it.date >= windowStart }
        if (recent.isEmpty()) return null
        val current = recent.maxBy { it.best!!.value }
        val earlier = points.filter { it.date < windowStart }
        if (earlier.isEmpty()) return null
        val previousBest = earlier.maxBy { it.best!!.value }
        val base = previousBest.best!!.value
        if (base <= 0) return null
        val gain = (current.best!!.value - base) / base
        if (gain < thresholds.progressMinGainFraction) return null
        val detail = strings.progressDetail(
            current.best.describe(exercise.modality, unit, strings), strings.dateContextual(current.date, today), Format.percent(gain),
            previousBest.best.describe(exercise.modality, unit, strings), strings.dateContextual(previousBest.date, today),
        )
        return Insight(InsightKind.PROGRESS, 10.0 + gain * 100, strings.exerciseName(exercise), detail, exerciseId = exercise.id, delta = gain, sessions = listOf(current.ref(), previousBest.ref()))
    }
}
