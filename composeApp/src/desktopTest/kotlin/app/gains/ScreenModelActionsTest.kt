package app.gains

import app.cash.sqldelight.db.SqlDriver
import app.gains.data.DesktopDriverFactory
import app.gains.data.ExerciseRepository
import app.gains.data.SessionRepository
import app.gains.db.GainsDatabase
import app.gains.importer.ImportService
import app.gains.platform.PickedFile
import app.gains.ui.ScreenModel
import app.gains.ui.launchAction
import app.gains.ui.reportingHandler
import app.gains.ui.screens.ImportModel
import app.gains.ui.screens.ImportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * How a model's actions fail (launch plan item 34): one that fails for a reason nobody expected
 * shows the screen's failure state and is reported once; one the action expects is the screen's
 * to word and is not reported; a model cleared mid-action reports nothing and writes nothing; and
 * an [Error] is never caught, so it stays a crash.
 */
class ScreenModelActionsTest {
    private val reporter = RecordingReporter()

    @BeforeTest
    fun mainRunsHere() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun mainIsBack() = Dispatchers.resetMain()

    /** A model with one action whose work waits for [gate] without noticing a cancel, as a blocking database call would. */
    private class Probe(reporter: ErrorReporter) : ScreenModel(reporter) {
        var state = "idle"

        fun act(gate: CompletableDeferred<Unit>, work: () -> Unit): Job = scope.launchAction(onFailure = { state = "failed" }) {
            withContext(NonCancellable) {
                gate.await()
                work()
            }
            state = "done"
        }
    }

    /** An import screen's model over a fresh database, and that database's driver for breaking it. */
    private fun importModel(): Pair<ImportModel, SqlDriver> {
        val driver = DesktopDriverFactory(null).createDriver()
        val db = GainsDatabase(driver)
        return ImportModel(ImportService(SessionRepository(db), ExerciseRepository(db)), reporter) to driver
    }

    private suspend fun awaitReports(count: Int) = withTimeout(10_000) {
        while (reporter.reported.size < count) delay(10)
    }

    @Test
    fun anImportWhoseDatabaseFailsSaysSoAndIsReportedOnce() = runBlocking {
        val (model, driver) = importModel()
        // The preview reads what is already stored before it parses; with the table gone that read throws.
        driver.execute(null, "ALTER TABLE session RENAME TO session_gone", 0)
        model.load(listOf(PickedFile("export.csv", "anything")))
        val shown = withTimeout(10_000) { model.state.first { it is ImportState.Error } } as ImportState.Error
        assertNull(shown.problem)
        assertNotNull(shown.cause, "the screen's error card has something to say")
        awaitReports(1)
        delay(100)
        assertEquals(1, reporter.reported.size, "reported once, not by the action and again by the handler")
        assertTrue(model.scope.isActive, "the model carries on: Retry works")
        model.onCleared()
    }

    @Test
    fun aFileNoConnectorReadsIsTheScreensToWordAndNotReported() = runBlocking {
        val (model, _) = importModel()
        model.load(listOf(PickedFile("notes.txt", "not an export of anything")))
        val shown = withTimeout(10_000) { model.state.first { it is ImportState.Error } } as ImportState.Error
        assertNotNull(shown.problem)
        delay(100)
        reporter.assertNone()
        model.onCleared()
    }

    @Test
    fun aModelClearedMidActionReportsNothingAndWritesNothing() = runBlocking {
        val model = Probe(reporter)
        val gate = CompletableDeferred<Unit>()
        // The screen is left while the call is out; the call then fails because of it.
        val job = model.act(gate) { throw IllegalStateException("the connection went with the screen") }
        model.onCleared()
        gate.complete(Unit)
        job.join()
        assertEquals("idle", model.state)
        reporter.assertNone()
    }

    @Test
    fun aPlainLaunchThatEscapesIsReportedAndTheModelCarriesOn() = runBlocking {
        val model = Probe(reporter)
        model.scope.launch { throw IllegalStateException("nobody caught this") }.join()
        assertEquals(1, reporter.reported.size)
        val gate = CompletableDeferred(Unit)
        model.act(gate) {}.join()
        assertEquals("done", model.state, "the next action still runs")
        model.onCleared()
    }

    @Test
    fun anErrorIsNotCaught() = runBlocking {
        val escaped = CompletableDeferred<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> escaped.complete(e) })
        val error = StackOverflowError("deep")
        var failed = false
        scope.launchAction(onFailure = { failed = true }) { throw error }.join()
        assertSame(error, escaped.await())
        assertFalse(failed, "an Error is not a failure the screen shows")
        // And the models' last line passes it on to the platform rather than swallowing it.
        assertFailsWith<StackOverflowError> { reportingHandler(reporter).handleException(EmptyCoroutineContext, error) }
        reporter.assertNone()
        scope.cancel()
    }
}
