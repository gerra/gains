package app.gains.analysis

import app.gains.analysis.Dates.minusDays
import app.gains.analysis.TestData.entry
import app.gains.analysis.TestData.session
import app.gains.analysis.TestData.weighted
import app.gains.domain.BodyweightEntry
import app.gains.domain.MuscleGroup
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalyzersTest {
    private val today = LocalDate(2026, 9, 2) // a Wednesday

    @Test
    fun epleyMatchesSpec() {
        assertEquals(60.0 * (1 + 8 / 30.0), Epley.e1rm(60.0, 8))
        assertEquals(100.0, Epley.e1rm(100.0, 1))
    }

    @Test
    fun historyReportsTopSetVolumesAndBestE1rm() {
        val s = session(LocalDate(2026, 8, 1), entry(TestData.bench,
            weighted(20.0, 20, 0, warmup = true), weighted(55.0, 10, 1), weighted(60.0, 8, 2)))
        val point = ExerciseAnalysis.history(listOf(s), TestData.bench).single()
        assertEquals(60.0, point.topSetWeetKgOrNull())
        assertEquals(60.0 * (1 + 8 / 30.0), point.bestE1rm!!.value)
        assertEquals(550.0, point.bestSetVolumeKg)
        assertEquals(1030.0, point.totalVolumeKg)
        assertEquals(2, point.workingSetCount)
        assertEquals(3, point.setCount)
    }

    private fun ExerciseSessionPoint.topSetWeetKgOrNull() = topSetWeightKg

    @Test
    fun summaryComputesGapToAllTimeBest() {
        val sessions = listOf(
            session(LocalDate(2026, 2, 1), entry(TestData.bench, weighted(70.0, 5))),
            session(LocalDate(2026, 8, 25), entry(TestData.bench, weighted(60.0, 5))),
        )
        val summary = ExerciseSummary.of(ExerciseAnalysis.history(sessions, TestData.bench), today)
        assertEquals(LocalDate(2026, 2, 1), summary.allTimeBest!!.date)
        assertEquals(LocalDate(2026, 8, 25), summary.currentBest!!.date)
        assertEquals(1 - 60.0 / 70.0, summary.gapFraction!!, 1e-9)
        assertEquals(2, summary.sessionCount)
    }

    @Test
    fun weeklyVolumeCountsWorkingSetsWithContributionWeights() {
        val monday = Dates.weekStart(today)
        val sessions = listOf(
            session(monday, entry(TestData.bench, weighted(20.0, 10, 0, warmup = true), weighted(60.0, 8, 1), weighted(60.0, 8, 2))),
            session(monday.minusDays(7), entry(TestData.squat, weighted(100.0, 5))),
        )
        val weeks = VolumeAnalyzer.weekly(sessions, TestData.exercises.associateBy { it.id }, monday.minusDays(14), today)
        assertEquals(3, weeks.size)
        assertEquals(0.0, weeks[0].total)
        assertEquals(1.0, weeks[1].sets[MuscleGroup.QUADS])
        assertEquals(0.5, weeks[1].sets[MuscleGroup.CORE])
        val current = weeks[2]
        assertEquals(2.0, current.sets[MuscleGroup.CHEST])
        assertEquals(1.0, current.sets[MuscleGroup.TRICEPS])
        assertNull(current.sets[MuscleGroup.QUADS])
        assertEquals(VolumeStatus.LOW, VolumeAnalyzer.status(2.0))
        assertEquals(VolumeStatus.OK, VolumeAnalyzer.status(8.0))
        assertEquals(VolumeStatus.HIGH, VolumeAnalyzer.status(23.0))
    }

    @Test
    fun sessionsPerWeekWithRollingAverage() {
        val monday = Dates.weekStart(today)
        val sessions = listOf(
            session(monday.minusDays(21), entry(TestData.bench, weighted(60.0, 8))),
            session(monday.minusDays(20), entry(TestData.bench, weighted(60.0, 8))),
            session(monday.minusDays(7), entry(TestData.bench, weighted(60.0, 8))),
            session(monday, entry(TestData.bench, weighted(60.0, 8))),
        )
        val weeks = ConsistencyAnalyzer.sessionsPerWeek(sessions, today)
        assertEquals(listOf(2, 0, 1, 1), weeks.map { it.sessions })
        assertEquals(1.0, weeks.last().rollingAverage)
        assertEquals(2, ConsistencyAnalyzer.currentStreakWeeks(sessions, today))
    }

    @Test
    fun bodyweightRollingAverageUsesTrailingWeek() {
        val entries = (0 until 10).map { BodyweightEntry(LocalDate(2026, 8, 1 + it), 80.0 + it) }
        val points = BodyweightAnalyzer.withRollingAverage(entries)
        assertEquals(80.0, points[0].rollingAverageKg)
        assertEquals(83.0, points[6].rollingAverageKg, 1e-9) // mean of 80..86
        assertEquals(86.0, points[9].rollingAverageKg, 1e-9) // mean of 83..89
    }

    @Test
    fun formatHelpers() {
        assertEquals("60 kg", Format.weight(60.0, app.gains.domain.WeightUnit.KG))
        assertEquals("62.5 kg", Format.weight(62.5, app.gains.domain.WeightUnit.KG))
        assertEquals("132.3 lbs", Format.weight(60.0, app.gains.domain.WeightUnit.LBS))
        assertEquals("18%", Format.percent(0.184))
        assertEquals("1:30 min", Format.seconds(90))
        assertEquals("2.5", Format.number(2.49, 1))
    }
}
