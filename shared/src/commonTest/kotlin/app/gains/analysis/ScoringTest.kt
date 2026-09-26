package app.gains.analysis

import app.gains.analysis.Dates.plusDays
import app.gains.analysis.TestData.bench
import app.gains.analysis.TestData.entry
import app.gains.analysis.TestData.exercises
import app.gains.analysis.TestData.session
import app.gains.analysis.TestData.weighted
import app.gains.domain.ExerciseEntry
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ScoringTest {
    private val byId = exercises.associateBy { it.id }
    private val day1 = LocalDate(2026, 3, 2)
    private fun day(n: Int) = day1.plusDays(n)

    @Test
    fun aWorkoutScoresForShowingUpTheSetsAndTheRecords() {
        val sessions = listOf(
            session(day(0), entry(bench, weighted(60.0, 8), weighted(60.0, 8), weighted(60.0, 8))),
            session(day(3), entry(bench, weighted(62.5, 8), weighted(62.5, 8), weighted(62.5, 8))),
        )
        val scores = Scoring.sessions(sessions, byId)
        val first = scores.getValue(sessions[0].id)
        assertEquals(SessionScore(sessions[0].id, showedUp = 10, work = 3, records = 0), first)
        // Four records (weight, e1RM, set volume, session volume): 20 points.
        val second = scores.getValue(sessions[1].id)
        assertEquals(SessionScore(sessions[1].id, showedUp = 10, work = 3, records = 20), second)
        assertEquals(46, Scoring.total(scores.values))
    }

    @Test
    fun theWorkAndTheRecordsAreCapped() {
        val thirty = ExerciseEntry(bench.id, (0 until 30).map { weighted(60.0, 8, order = it) })
        val session = session(day(0), thirty)
        val score = Scoring.session(session, emptyList())
        assertEquals(Scoring.MAX_WORK, score.work)
        val many = (0 until 6).map { Record(bench.id, RecordKind.WEIGHT, session.id, session.date, 60.0, null, 50.0, day(0)) }
        assertEquals(Scoring.MAX_RECORDS, Scoring.session(session, many).records)
    }

    @Test
    fun warmUpsDoNotScore() {
        val session = session(day(0), entry(bench, weighted(40.0, 5, warmup = true), weighted(60.0, 8)))
        assertEquals(1, Scoring.session(session, emptyList()).work)
    }

    @Test
    fun levelsComeQuicklyAtFirstAndThenFurtherApart() {
        assertEquals(Level(1, 0, 0, 100), Scoring.level(0))
        assertEquals(Level(1, 99, 0, 100), Scoring.level(99))
        assertEquals(Level(2, 100, 100, 300), Scoring.level(100))
        assertEquals(Level(3, 300, 300, 600), Scoring.level(300))
        assertEquals(Level(5, 1234, 1000, 1500), Scoring.level(1234))
        assertEquals(10, Scoring.level(4500).level)
        assertEquals(9, Scoring.level(4499).level)
        val l = Scoring.level(1234)
        assertEquals(234, l.into)
        assertEquals(266, l.toNext)
    }
}
