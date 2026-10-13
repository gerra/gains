package app.gains

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.gains.auth.AccountRepository
import app.gains.data.DatabaseDriverFactory
import app.gains.data.DesktopDriverFactory
import app.gains.data.ProgramRepository
import app.gains.di.initKoin
import app.gains.importer.CsvFile
import app.gains.importer.ImportService
import app.gains.platform.CsvFilePicker
import app.gains.ui.inject
import kotlinx.coroutines.runBlocking
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A screen covered by another comes back scrolled where it was left, whichever way the lifter goes
 * back: the back arrow, or a swipe in from the edge, which shows it underneath before it is back on
 * top. Driven through the whole app, since what is kept depends on where the frame draws each screen.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenScrollKeptTest {
    @Test
    fun homeKeepsItsScrollPositionBehindSettings() = runDesktopComposeUiTest(width = 480, height = 860) {
        val dbFile = File.createTempFile("gains-scroll", ".db").apply { delete(); deleteOnExit() }
        stopKoin()
        val reporter = RecordingReporter()
        initKoin(module {
            single<DatabaseDriverFactory> { DesktopDriverFactory(dbFile) }
            single<ErrorReporter> { reporter }
        })
        // Enough history for Home to scroll: the sample export, straight through the repositories.
        runBlocking {
            inject<AccountRepository>().continueAsGuest()
            inject<ProgramRepository>().markOnboardingDone()
            val service = inject<ImportService>()
            val csv = File(System.getProperty("gains.sampleCsv") ?: "../samples/liftoff-export.csv").readText()
            service.commit(service.preview(listOf(CsvFile("liftoff-export.csv", csv))), emptySet())
        }
        // The clock is driven by hand, as in the screenshot run: the app always has something animating.
        mainClock.autoAdvance = false
        setContent { App(filePicker = CsvFilePicker { it(emptyList()) }) }

        fun settle(millis: Long = 1_500) = repeat((millis / 16).toInt()) { mainClock.advanceTimeByFrame() }
        fun exists(matcher: SemanticsMatcher) = onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        val start = System.nanoTime()
        while (!exists(hasText("What's moving", substring = true, ignoreCase = true))) {
            mainClock.advanceTimeByFrame()
            check(System.nanoTime() - start < 60_000_000_000L) { "Home never showed its insights" }
        }
        settle()

        /** The vertical lists on screen, in the order they are drawn. */
        fun lists() = onAllNodes(hasScrollAction()).fetchSemanticsNodes().filter { SemanticsProperties.VerticalScrollAxisRange in it.config }
        fun scrolls() = lists().map { it.config[SemanticsProperties.VerticalScrollAxisRange].value() }
        val home = lists().single()
        runOnUiThread { home.config[SemanticsActions.ScrollBy].action?.invoke(0f, 1_200f) }
        settle()
        val scrolled = scrolls().single()
        assertTrue(scrolled > 0f)

        fun openSettings() {
            onAllNodes(hasContentDescription("Settings", substring = true) and hasClickAction()).onFirst().performClick()
            settle()
        }

        // The back arrow.
        openSettings()
        onAllNodes(hasContentDescription("Back") and hasClickAction()).onFirst().performClick()
        settle()
        assertEquals(listOf(scrolled), scrolls())

        // A swipe in from the edge: Home shows underneath as it was left, and stays so once it is back on top.
        openSettings()
        onRoot().performTouchInput {
            down(Offset(4f, 400f))
            repeat(20) { moveBy(Offset(15f, 0f)); advanceEventTime(16) }
        }
        settle(200)
        assertEquals(scrolled, scrolls().first(), "Home underneath the swipe")
        onRoot().performTouchInput { repeat(10) { moveBy(Offset(15f, 0f)); advanceEventTime(16) }; up() }
        settle()
        assertEquals(listOf(scrolled), scrolls())
        reporter.assertNone()
    }
}
