package app.gains.analysis

import app.gains.analysis.Dates.minusDays
import app.gains.analysis.TestData.entry
import app.gains.analysis.TestData.session
import app.gains.analysis.TestData.weighted
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InsightEngineTest {
    private val today = LocalDate(2026, 9, 2)
    private val engine = InsightEngine()

    @Test
    fun regressionReportsCurrentAndAllTimeBestWithNumbers() {
        val sessions = listOf(
            session(LocalDate(2026, 2, 10), entry(TestData.dbPress, weighted(14.0, 12))),
            session(LocalDate(2026, 5, 10), entry(TestData.dbPress, weighted(13.0, 10))),
            session(LocalDate(2026, 8, 20), entry(TestData.dbPress, weighted(12.0, 10))),
            session(LocalDate(2026, 8, 27), entry(TestData.dbPress, weighted(12.0, 9))),
        )
        val insights = engine.generate(sessions, TestData.exercises, today)
        val regression = insights.first { it.kind == InsightKind.REGRESSION }
        assertEquals(TestData.dbPress, (regression.subject as InsightSubject.Lift).exercise)
        val detail = regression.detail as InsightDetail.Regression
        assertEquals(12.0 to 10, detail.current.set.weightKg to detail.current.set.reps)
        assertEquals(14.0 to 12, detail.best.set.weightKg to detail.best.set.reps)
        assertEquals(LocalDate(2026, 2, 10), detail.bestDate)
        assertEquals(0.18, detail.drop, 0.005)
        assertEquals(TestData.dbPress.id, regression.exerciseId)
        assertTrue(regression === insights.first(), "regression should sort first")
        assertNull(insights.firstOrNull { it.kind == InsightKind.STALL && it.exerciseId == TestData.dbPress.id })
    }

    @Test
    fun smallDropsAreNotRegressions() {
        val sessions = listOf(
            session(LocalDate(2026, 6, 1), entry(TestData.bench, weighted(60.0, 8))),
            session(LocalDate(2026, 8, 25), entry(TestData.bench, weighted(60.0, 7))),
        )
        assertNull(engine.regression(TestData.bench, ExerciseAnalysis.history(sessions, TestData.bench), today))
    }

    @Test
    fun regressionUsesWorkingSetsOnly() {
        // A heavy warm-up must not count as the all-time best.
        val sessions = listOf(
            session(LocalDate(2026, 5, 1), entry(TestData.bench, weighted(80.0, 1, warmup = true), weighted(60.0, 8, order = 1))),
            session(LocalDate(2026, 8, 25), entry(TestData.bench, weighted(60.0, 8))),
        )
        assertNull(engine.regression(TestData.bench, ExerciseAnalysis.history(sessions, TestData.bench), today))
    }

    @Test
    fun stallReportsWeightSinceDateAndSessionCount() {
        val sessions = TestData.series(TestData.lateralRaise, end = today.minusDays(2), count = 14, everyDays = 7) {
            listOf(weighted(4.0, 15), weighted(4.0, 15, 1))
        }
        val stall = engine.generate(sessions, TestData.exercises, today).first { it.kind == InsightKind.STALL }
        assertEquals(TestData.lateralRaise, (stall.subject as InsightSubject.Lift).exercise)
        assertEquals(InsightDetail.Stall(4.0, (stall.detail as InsightDetail.Stall).best, LocalDate(2026, 6, 1), 14, 13), stall.detail)
    }

    @Test
    fun noStallWhenWeightIncreasedRecentlyOrTooFewSessions() {
        val increased = TestData.series(TestData.lateralRaise, end = today.minusDays(2), count = 10, everyDays = 7) { i ->
            listOf(weighted(if (i >= 8) 5.0 else 4.0, 15))
        }
        assertNull(engine.stall(TestData.lateralRaise, ExerciseAnalysis.history(increased, TestData.lateralRaise), today))

        val sparse = TestData.series(TestData.lateralRaise, end = today.minusDays(2), count = 3, everyDays = 28) {
            listOf(weighted(4.0, 15))
        }
        assertNull(engine.stall(TestData.lateralRaise, ExerciseAnalysis.history(sparse, TestData.lateralRaise), today))
    }

    @Test
    fun stallNotReportedForExerciseNotTrainedRecently() {
        val old = TestData.series(TestData.lateralRaise, end = today.minusDays(40), count = 10, everyDays = 7) {
            listOf(weighted(4.0, 15))
        }
        assertNull(engine.stall(TestData.lateralRaise, ExerciseAnalysis.history(old, TestData.lateralRaise), today))
    }

    @Test
    fun neglectedExerciseAfterRegularTraining() {
        val sessions = TestData.series(TestData.pullUp, end = today.minusDays(50), count = 8, everyDays = 7) {
            listOf(SetEntry(0, SetType.BODYWEIGHT, reps = 8))
        }
        val neglect = engine.generate(sessions, TestData.exercises, today).first { it.kind == InsightKind.NEGLECT }
        assertEquals(TestData.pullUp, (neglect.subject as InsightSubject.Lift).exercise)
        assertEquals(InsightDetail.NeglectedExercise(LocalDate(2026, 7, 14), 7, 8, 12), neglect.detail)
    }

    @Test
    fun rarelyTrainedExerciseIsNotNeglected() {
        val sessions = listOf(session(today.minusDays(60), entry(TestData.pullUp, SetEntry(0, SetType.BODYWEIGHT, reps = 8))))
        assertNull(engine.neglectedExercise(TestData.pullUp, ExerciseAnalysis.history(sessions, TestData.pullUp), today))
    }

    @Test
    fun neglectedMuscleGroupWhenWeeklySetsCollapse() {
        // 10 weeks of squats (quads 3 sets/session × 3 sessions = 9/week), then two quiet weeks with bench only.
        val squats = (0 until 10).flatMap { w ->
            (0 until 3).map { d ->
                session(Dates.weekStart(today).minusDays((w + 2) * 7 - d), entry(TestData.squat, weighted(100.0, 5), weighted(100.0, 5, 1), weighted(100.0, 5, 2)))
            }
        }
        val recent = (0 until 2).map { w -> session(Dates.weekStart(today).minusDays(w * 7 - 1), entry(TestData.bench, weighted(60.0, 8))) }
        val insights = engine.neglectedMuscles(squats + recent, TestData.exercises.associateBy { it.id }, today)
        val quads = insights.first { it.muscleGroup == app.gains.domain.MuscleGroup.QUADS }
        assertEquals(InsightSubject.Muscle(app.gains.domain.MuscleGroup.QUADS), quads.subject)
        val detail = quads.detail as InsightDetail.NeglectedMuscle
        assertEquals(0.0, detail.recentSetsPerWeek)
        assertEquals(2, detail.recentWeeks)
        assertEquals(9.0, detail.baselineSetsPerWeek, 1e-9)
        assertEquals(8, detail.baselineWeeks)
    }

    @Test
    fun consistencyTrendAndNumbers() {
        val previous = (0 until 16).map { i -> session(today.minusDays(55 - i * 1), entry(TestData.bench, weighted(60.0, 8))) }
        val recent = (0 until 8).map { i -> session(today.minusDays(27 - i * 3), entry(TestData.bench, weighted(60.0, 8))) }
        val stats = engine.consistencyStats(previous + recent, today)!!
        assertEquals(2.0, stats.recentSessionsPerWeek)
        assertEquals(4.0, stats.previousSessionsPerWeek)
        assertEquals(Trend.DOWN, stats.trend)
        val insight = engine.consistency(previous + recent, today)!!
        assertEquals(InsightSubject.Frequency(Trend.DOWN), insight.subject)
        assertEquals(InsightDetail.Consistency(2.0, 4.0, Trend.DOWN, 4), insight.detail)
    }

    @Test
    fun progressIsReportedForLiftsThatMovedUp() {
        val sessions = listOf(
            session(LocalDate(2026, 6, 5), entry(TestData.bench, weighted(60.0, 8))),
            session(LocalDate(2026, 8, 20), entry(TestData.bench, weighted(62.5, 8))),
        )
        val progress = engine.generate(sessions, TestData.exercises, today).first { it.kind == InsightKind.PROGRESS }
        assertEquals(TestData.bench, (progress.subject as InsightSubject.Lift).exercise)
        val detail = progress.detail as InsightDetail.Progress
        assertEquals(62.5 to 8, detail.current.set.weightKg to detail.current.set.reps)
        assertEquals(LocalDate(2026, 8, 20), detail.currentDate)
        assertEquals(60.0 to 8, detail.previous.set.weightKg to detail.previous.set.reps)
        assertEquals(LocalDate(2026, 6, 5), detail.previousDate)
        assertEquals(0.04, detail.gain, 0.005)
    }

    @Test
    fun isometricRegressionUsesSeconds() {
        val sessions = listOf(
            session(LocalDate(2026, 5, 5), entry(TestData.plank, SetEntry(0, SetType.ISOMETRIC, seconds = 120))),
            session(LocalDate(2026, 8, 25), entry(TestData.plank, SetEntry(0, SetType.ISOMETRIC, seconds = 60))),
        )
        val r = engine.regression(TestData.plank, ExerciseAnalysis.history(sessions, TestData.plank), today)
        assertNotNull(r)
        val detail = r.detail as InsightDetail.Regression
        assertEquals(60, detail.current.set.seconds)
        assertEquals(120, detail.best.set.seconds)
        assertEquals(LocalDate(2026, 5, 5), detail.bestDate)
        assertEquals(0.5, detail.drop, 1e-9)
    }

    @Test
    fun emptyAndSingleSessionInputsProduceNoFailures() {
        assertEquals(emptyList(), engine.generate(emptyList(), TestData.exercises, today))
        val one = listOf(session(today.minusDays(1), entry(TestData.bench, weighted(60.0, 8))))
        val insights = engine.generate(one, TestData.exercises, today)
        assertTrue(insights.all { it.kind == InsightKind.CONSISTENCY })
    }
}
