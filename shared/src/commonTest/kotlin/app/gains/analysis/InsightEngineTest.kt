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
        assertEquals("Dumbbell Shoulder Press", regression.title)
        assertEquals("12 kg × 10 now, 14 kg × 12 on 10 Feb — down 18%.", regression.detail)
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
        assertEquals("Lateral Raise", stall.title)
        assertEquals("4 kg since 1 Jun. 14 sessions, no change in 13 weeks.", stall.detail)
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
        assertEquals("Pull Up", neglect.title)
        assertEquals("Last trained 14 Jul, 7 weeks ago, after 8 sessions in the 12 weeks before that.", neglect.detail)
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
        assertEquals("Quads", quads.title)
        assertEquals("0 sets/week over the last 2 weeks, down from 9/week over the 8 weeks before.", quads.detail)
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
        assertEquals("Training less often", insight.title)
        assertEquals("2 sessions/week over the last 4 weeks, down from 4 the 4 weeks before.", insight.detail)
    }

    @Test
    fun progressIsReportedForLiftsThatMovedUp() {
        val sessions = listOf(
            session(LocalDate(2026, 6, 5), entry(TestData.bench, weighted(60.0, 8))),
            session(LocalDate(2026, 8, 20), entry(TestData.bench, weighted(62.5, 8))),
        )
        val progress = engine.generate(sessions, TestData.exercises, today).first { it.kind == InsightKind.PROGRESS }
        assertEquals("Bench Press", progress.title)
        assertEquals("62.5 kg × 8 on 20 Aug, up 4% on 60 kg × 8 from 5 Jun.", progress.detail)
    }

    @Test
    fun isometricRegressionUsesSeconds() {
        val sessions = listOf(
            session(LocalDate(2026, 5, 5), entry(TestData.plank, SetEntry(0, SetType.ISOMETRIC, seconds = 120))),
            session(LocalDate(2026, 8, 25), entry(TestData.plank, SetEntry(0, SetType.ISOMETRIC, seconds = 60))),
        )
        val r = engine.regression(TestData.plank, ExerciseAnalysis.history(sessions, TestData.plank), today)
        assertNotNull(r)
        assertEquals("1:00 min now, 2:00 min on 5 May — down 50%.", r.detail)
    }

    @Test
    fun emptyAndSingleSessionInputsProduceNoFailures() {
        assertEquals(emptyList(), engine.generate(emptyList(), TestData.exercises, today))
        val one = listOf(session(today.minusDays(1), entry(TestData.bench, weighted(60.0, 8))))
        val insights = engine.generate(one, TestData.exercises, today)
        assertTrue(insights.all { it.kind == InsightKind.CONSISTENCY })
    }
}
