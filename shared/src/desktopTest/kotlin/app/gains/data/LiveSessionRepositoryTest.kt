package app.gains.data

import app.gains.db.GainsDatabase
import app.gains.domain.LiveExercise
import app.gains.domain.LiveSession
import app.gains.domain.ProgramDayRef
import app.gains.domain.RestTimer
import app.gains.domain.SetDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveSessionRepositoryTest {
    private fun newRepo() = LiveSessionRepository(GainsDatabase(DesktopDriverFactory(file = null).createDriver()), Dispatchers.Unconfined)

    private val live = LiveSession(
        startedAtMs = 1_700_000_000_000,
        title = "A1",
        program = ProgramDayRef("gzclp", "gzclp/a1"),
        exercises = listOf(
            LiveExercise(
                "squat",
                sets = listOf(
                    SetDraft(weight = "20", reps = "10", isWarmup = true, done = true),
                    SetDraft(weight = "60", reps = "3", done = true),
                    SetDraft(weight = "60", reps = "3"),
                    SetDraft(weight = "6.", reps = ""),
                ),
                note = "felt heavy; \"pipes\" | and, commas\nsecond line",
                seeded = true,
                warmupsCollapsed = true,
            ),
            LiveExercise("plank", sets = listOf(SetDraft(seconds = "45"))),
            LiveExercise("row", sets = emptyList(), note = ""),
        ),
        rest = RestTimer("squat", endsAtMs = 1_700_000_180_000, totalSeconds = 180),
    )

    @Test
    fun emptyUntilSaved() = runTest {
        val repo = newRepo()
        assertNull(repo.load())
        assertNull(repo.observe().first())
    }

    @Test
    fun roundTripsEveryField() = runTest {
        val repo = newRepo()
        repo.save(live)
        assertEquals(live, repo.load())
        assertEquals(live, repo.observe().first())
    }

    @Test
    fun saveReplacesThePreviousWorkout() = runTest {
        val repo = newRepo()
        repo.save(live)
        val free = LiveSession(startedAtMs = 5, title = "Workout", exercises = listOf(LiveExercise("bench_press", listOf(SetDraft(weight = "40", reps = "8")))))
        repo.save(free)
        assertEquals(free, repo.load())
        // No rest, no program: the optional columns come back as nulls rather than stale values.
        assertNull(repo.load()!!.rest)
        assertNull(repo.load()!!.program)
    }

    @Test
    fun clearRemovesIt() = runTest {
        val repo = newRepo()
        repo.save(live)
        repo.clear()
        assertNull(repo.load())
        assertNull(repo.observe().first())
    }
}
