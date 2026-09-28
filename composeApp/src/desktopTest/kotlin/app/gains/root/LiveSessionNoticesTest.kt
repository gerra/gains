package app.gains.root

import app.gains.LogErrorReporter
import app.gains.data.DesktopDriverFactory
import app.gains.data.LiveSessionRepository
import app.gains.db.GainsDatabase
import app.gains.domain.LiveExercise
import app.gains.domain.LiveSession
import app.gains.domain.ProgramDayRef
import app.gains.domain.RestTimer
import app.gains.domain.SetDraft
import app.gains.platform.LiveSessionNotice
import app.gains.platform.LiveSessionNotifier
import app.gains.platform.NoticeRequests
import app.gains.ui.ScreenModel
import app.gains.ui.nav.Navigator
import app.gains.ui.nav.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The workout notice and its two buttons, as the root runs them: against a real (in-memory)
 * repository, a recording notifier, a real [Navigator] and virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionNoticesTest {
    /**
     * Stands in for the workout editor: only the one running the workout takes a skip. It launches
     * nothing, so nothing can reach its reporter.
     */
    private class Editor(private val running: Boolean) : ScreenModel(LogErrorReporter) {
        var asked = 0
        fun skipRest(): Boolean {
            asked++
            return running
        }
    }

    private class Requests : NoticeRequests()

    private class Rig(scope: TestScope) {
        val repository = LiveSessionRepository(GainsDatabase(DesktopDriverFactory(file = null).createDriver()), Dispatchers.Unconfined)
        val posted = mutableListOf<LiveSessionNotice?>()
        val navigator = Navigator()
        val resumes = Requests()
        val skips = Requests()
        val notices = LiveSessionNotices(
            repository, LiveSessionNotifier { posted += it }, navigator, resumes, skips,
            now = { scope.testScheduler.currentTime },
            skipRestIn = { it.peek(Editor::class)?.skipRest() == true },
        )
    }

    private fun TestScope.started(): Rig = Rig(this).also { rig ->
        backgroundScope.launch { rig.notices.run() }
        runCurrent()
    }

    private val day = ProgramDayRef("gzclp", "gzclp/a1")
    private val workout = LiveSession(startedAtMs = 1_000, title = "A1", program = day)

    @Test
    fun theNoticeFollowsTheWorkoutsTitleAndStart() = runTest {
        val rig = started()
        assertEquals(listOf<LiveSessionNotice?>(null), rig.posted)

        rig.repository.save(workout)
        runCurrent()
        assertEquals(LiveSessionNotice("A1", 1_000), rig.posted.last())

        rig.repository.save(workout.copy(title = "A2", startedAtMs = 2_000))
        runCurrent()
        assertEquals(LiveSessionNotice("A2", 2_000), rig.posted.last())

        rig.repository.clear()
        runCurrent()
        assertNull(rig.posted.last())
    }

    @Test
    fun aRestIsPostedWithItsEndAndRepostedWithoutItOnceOver() = runTest {
        val rig = started()
        rig.repository.save(workout.copy(rest = RestTimer("squat", endsAtMs = 90_000, totalSeconds = 90)))
        runCurrent()
        assertEquals(LiveSessionNotice("A1", 1_000, restEndsAtMs = 90_000), rig.posted.last())
        val postedDuringRest = rig.posted.size

        advanceTimeBy(89_000)
        runCurrent()
        assertEquals(postedDuringRest, rig.posted.size)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(postedDuringRest + 1, rig.posted.size)
        assertEquals(LiveSessionNotice("A1", 1_000), rig.posted.last())
    }

    @Test
    fun aRestAlreadyOverIsPostedWithoutIt() = runTest {
        val rig = started()
        advanceTimeBy(100_000)
        rig.repository.save(workout.copy(rest = RestTimer("squat", endsAtMs = 90_000, totalSeconds = 90)))
        runCurrent()
        assertEquals(LiveSessionNotice("A1", 1_000), rig.posted.last())
    }

    @Test
    fun aWeightChangeDoesNotRepost() = runTest {
        val rig = started()
        val squat = LiveExercise("squat", sets = listOf(SetDraft(weight = "60", reps = "5")))
        rig.repository.save(workout.copy(exercises = listOf(squat)))
        runCurrent()
        val posted = rig.posted.size

        rig.repository.save(workout.copy(exercises = listOf(squat.copy(sets = listOf(SetDraft(weight = "62.5", reps = "5", done = true))))))
        runCurrent()
        assertEquals(posted, rig.posted.size)
    }

    @Test
    fun aResumeOpensTheRunningWorkoutOnceAndNotWhenItIsOnTop() = runTest {
        val rig = started()
        rig.repository.save(workout)
        runCurrent()

        rig.resumes.request()
        runCurrent()
        assertEquals(Screen.EditSession(null, day, live = true), rig.navigator.current)
        assertEquals(2, rig.navigator.stack.size)
        assertEquals(0, rig.resumes.pending.value)

        rig.resumes.request()
        runCurrent()
        assertEquals(2, rig.navigator.stack.size)
        assertEquals(0, rig.resumes.pending.value)
    }

    @Test
    fun aResumeWithNoWorkoutRunningOpensNothing() = runTest {
        val rig = started()
        rig.resumes.request()
        runCurrent()
        assertEquals(listOf<Screen>(Screen.Home), rig.navigator.stack.map { it.screen })
        assertEquals(0, rig.resumes.pending.value)
    }

    @Test
    fun aResumeTappedBeforeTheAppRanIsHonouredOnceItDoes() = runTest {
        val rig = Rig(this)
        rig.repository.save(workout)
        rig.resumes.request()
        backgroundScope.launch { rig.notices.run() }
        runCurrent()
        assertEquals(Screen.EditSession(null, day, live = true), rig.navigator.current)
    }

    @Test
    fun aSkipReachesTheCoveredEditorRunningTheWorkout() = runTest {
        val rig = started()
        rig.repository.save(workout.copy(rest = RestTimer("squat", endsAtMs = 90_000, totalSeconds = 90)))
        rig.navigator.push(Screen.EditSession(null, day, live = true))
        val editor = rig.navigator.currentEntry.model(Editor::class, emptyList()) { Editor(running = true) }
        rig.navigator.push(Screen.Settings)

        rig.skips.request()
        runCurrent()
        assertEquals(1, editor.asked)
        assertEquals(0, rig.skips.pending.value)
        // The editor drops it; the database hears of it through the editor's next persist, not from here.
        assertNotNull(rig.repository.load()?.rest)
    }

    @Test
    fun aSkipFallsBackToTheRepositoryWhenNoEditorIsRunningTheWorkout() = runTest {
        val rig = started()
        rig.repository.save(workout.copy(rest = RestTimer("squat", endsAtMs = 90_000, totalSeconds = 90)))
        // A past workout's editor is open, and the running one's was closed (its entry released).
        rig.navigator.push(Screen.EditSession("past"))
        val past = rig.navigator.currentEntry.model(Editor::class, emptyList()) { Editor(running = false) }
        rig.navigator.push(Screen.EditSession(null, day, live = true))
        val closed = rig.navigator.currentEntry.model(Editor::class, emptyList()) { Editor(running = true) }
        rig.navigator.pop()

        rig.skips.request()
        runCurrent()
        assertEquals(1, past.asked)
        assertEquals(0, closed.asked)
        assertNull(rig.repository.load()?.rest)
        assertEquals(0, rig.skips.pending.value)
    }

    @Test
    fun theRequestsAreAttendedOnlyWhileItRuns() = runTest {
        val rig = Rig(this)
        assertFalse(rig.resumes.attended)
        val running = backgroundScope.launch { rig.notices.run() }
        runCurrent()
        assertTrue(rig.resumes.attended)
        assertTrue(rig.skips.attended)
        running.cancel()
        runCurrent()
        assertFalse(rig.resumes.attended)
        assertFalse(rig.skips.attended)
    }
}
