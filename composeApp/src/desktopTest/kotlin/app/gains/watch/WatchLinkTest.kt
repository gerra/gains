package app.gains.watch

import app.gains.LogErrorReporter
import app.gains.data.DesktopDriverFactory
import app.gains.data.ExerciseRepository
import app.gains.data.LiveSessionRepository
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.domain.ExerciseEntry
import app.gains.domain.LiveExercise
import app.gains.domain.LiveSession
import app.gains.domain.ProgramDayRef
import app.gains.domain.RestTimer
import app.gains.domain.Session
import app.gains.domain.SetDraft
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import app.gains.platform.LiveSessionNotifier
import app.gains.platform.NoticeRequests
import app.gains.root.LiveSessionNotices
import app.gains.ui.ScreenModel
import app.gains.ui.nav.Navigator
import app.gains.ui.nav.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone's half of the watch link against a real (in-memory) database: what the watch is sent
 * as the workout changes and ends, a command carried out on the stored workout, and the root
 * handing a command to the open editor rather than the database under it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchLinkTest {
    private class Rig(scope: TestScope) {
        val db = GainsDatabase(DesktopDriverFactory(file = null).createDriver())
        val io = Dispatchers.Unconfined
        val live = LiveSessionRepository(db, io)
        val sessions = SessionRepository(db, io)
        val exercises = ExerciseRepository(db, io)
        val settings = SettingsRepository(db, io)
        val programs = ProgramRepository(db, settings, io)
        val link = WatchLink(live, sessions, exercises, settings, programs, names = { "«${it.name}»" }, now = { scope.testScheduler.currentTime })
    }

    // A minute boundary, so the stored session's id is exactly the start's minute.
    private val startedAt = LocalDateTime(2026, 11, 16, 18, 30).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
    private val day = ProgramDayRef("gzclp", "gzclp/a1")
    private val workout = LiveSession(
        startedAtMs = startedAt, title = "A1", program = day,
        exercises = listOf(
            LiveExercise("squat", listOf(SetDraft("40", "10", isWarmup = true, done = true), SetDraft("100", "3"))),
            LiveExercise("bench_press", listOf(SetDraft("60", "10"))),
        ),
    )

    private fun TestScope.collecting(rig: Rig): MutableList<WatchState> {
        val states = mutableListOf<WatchState>()
        backgroundScope.launch { rig.link.states().collect { states += it } }
        runCurrent()
        return states
    }

    @Test
    fun theWatchIsSentTheWorkoutWithNamesAndWhatIsNext() = runTest {
        val rig = Rig(this)
        rig.exercises.seedCatalogue()
        val states = collecting(rig)
        assertNull(states.last().workout)

        rig.live.save(workout)
        runCurrent()
        val sent = assertNotNull(states.last().workout)
        assertEquals("A1", sent.title)
        assertEquals("kg", sent.unit)
        assertEquals("«Squat»", sent.exercises[0].name)
        assertEquals(WatchSetRef(0, 1), sent.current)
        assertEquals(WatchSetRef(1, 0), sent.next)
        assertEquals(1 to 3, sent.done to sent.total)
        // GZCLP A1: squat is the T1, bench the T2; a warm-up rests half a minute.
        assertEquals(listOf(30, 180), sent.exercises[0].sets.map { it.rest })
        assertEquals(listOf(120), sent.exercises[1].sets.map { it.rest })
    }

    @Test
    fun aStoredWorkoutEndsSavedAndADiscardedOneNot() = runTest {
        val rig = Rig(this)
        val states = collecting(rig)
        rig.live.save(workout)
        runCurrent()
        rig.sessions.upsert(Session("2026-11-16T18:30", LocalDateTime(2026, 11, 16, 18, 30), 45, listOf(ExerciseEntry("squat", listOf(SetEntry(0, SetType.WEIGHTED, 100.0, 3)))), Session.MANUAL))
        rig.live.clear()
        runCurrent()
        assertEquals(WatchEnded(startedAt, saved = true), states.last().ended)
        assertNull(states.last().workout)

        val next = workout.copy(startedAtMs = startedAt + 3_600_000)
        rig.live.save(next)
        runCurrent()
        assertNull(states.last().ended, "a new workout forgets the last one's end")
        rig.live.clear()
        runCurrent()
        assertEquals(WatchEnded(next.startedAtMs, saved = false), states.last().ended)
    }

    @Test
    fun aCommandWithNoEditorChangesTheStoredWorkoutWithTheTiersRest() = runTest {
        val rig = Rig(this)
        rig.live.save(workout)
        testScheduler.advanceTimeBy(10_000)
        assertTrue(rig.link.apply(WatchCommand.Complete(startedAt, "squat", 1, reps = "5")))
        val stored = assertNotNull(rig.live.load())
        assertEquals(SetDraft("100", "5", done = true), stored.exercises[0].sets[1])
        // Squat is GZCLP A1's T1: three minutes.
        assertEquals(RestTimer("squat", 10_000 + 180_000, 180), stored.rest)

        assertTrue(rig.link.apply(WatchCommand.SkipRest(startedAt)))
        assertNull(rig.live.load()?.rest)
        assertFalse(rig.link.apply(WatchCommand.SkipRest(startedAt)), "nothing left to skip")
        assertFalse(rig.link.apply(WatchCommand.Complete(startedAt - 1, "bench_press", 0)), "another workout's command")
        rig.live.clear()
        assertFalse(rig.link.apply(WatchCommand.Complete(startedAt, "bench_press", 0)), "no workout running")
    }

    /** Stands in for the workout editor; only the one running the workout takes a command. */
    private class Editor(private val running: Boolean) : ScreenModel(LogErrorReporter) {
        val taken = mutableListOf<WatchCommand>()
        fun applyWatch(command: WatchCommand): Boolean {
            if (running) taken += command
            return running
        }
    }

    private class Requests : NoticeRequests()

    private fun TestScope.root(rig: Rig, commands: MutableSharedFlow<WatchCommand>, navigator: Navigator) {
        val notices = LiveSessionNotices(
            rig.live, LiveSessionNotifier.None, navigator, Requests(), Requests(),
            now = { testScheduler.currentTime },
            watch = rig.link, watchCommands = commands,
            applyWatchIn = { entry, command -> entry.peek(Editor::class)?.applyWatch(command) == true },
        )
        backgroundScope.launch { notices.run() }
        runCurrent()
    }

    @Test
    fun theRootHandsACommandToTheEditorRunningTheWorkout() = runTest {
        val rig = Rig(this)
        rig.live.save(workout)
        val commands = MutableSharedFlow<WatchCommand>(extraBufferCapacity = 4)
        val navigator = Navigator()
        navigator.push(Screen.EditSession(null, day, live = true))
        val editor = navigator.currentEntry.model(Editor::class, emptyList()) { Editor(running = true) }
        root(rig, commands, navigator)

        commands.emit(WatchCommand.Complete(startedAt, "squat", 1))
        runCurrent()
        assertEquals(listOf<WatchCommand>(WatchCommand.Complete(startedAt, "squat", 1)), editor.taken)
        assertFalse(assertNotNull(rig.live.load()).exercises[0].sets[1].done, "the editor's to write, not the root's")
    }

    @Test
    fun withoutTheRunningEditorTheRootChangesTheDatabase() = runTest {
        val rig = Rig(this)
        rig.live.save(workout)
        val commands = MutableSharedFlow<WatchCommand>(extraBufferCapacity = 4)
        val navigator = Navigator()
        // A past workout's editor is open: it must not take the tick.
        navigator.push(Screen.EditSession("2026-11-01T10:00", null))
        val past = navigator.currentEntry.model(Editor::class, emptyList()) { Editor(running = false) }
        root(rig, commands, navigator)

        commands.emit(WatchCommand.Complete(startedAt, "bench_press", 0))
        commands.emit(WatchCommand.Complete(startedAt, "squat", 1))
        runCurrent()
        assertTrue(past.taken.isEmpty())
        val stored = assertNotNull(rig.live.load())
        assertTrue(stored.exercises[1].sets[0].done)
        assertTrue(stored.exercises[0].sets[1].done, "both quick ticks land")
    }
}
