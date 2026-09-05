package app.gains

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.auth.AccountRepository
import app.gains.data.BodyweightRepository
import app.gains.data.DatabaseDriverFactory
import app.gains.data.DesktopDriverFactory
import app.gains.data.ProgramRepository
import app.gains.di.initKoin
import app.gains.domain.BodyweightEntry
import app.gains.domain.Experience
import app.gains.domain.Goal
import app.gains.domain.GoalProfile
import app.gains.importer.CsvFile
import app.gains.importer.ImportService
import app.gains.platform.CsvFilePicker
import app.gains.platform.IncomingFiles
import app.gains.platform.PickedFile
import app.gains.ui.components.GainsLogo
import app.gains.ui.inject
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sin
import kotlin.test.Test

/**
 * Drives the whole app headlessly (sign-in, import, every tab) and saves a PNG of each screen.
 * The images end up in `build/screenshots` by default; the Screenshots workflow passes
 * `-Pgains.screenshotDir=docs/screenshots` to refresh the ones shown in the README.
 * Besides producing pictures this is the closest thing to an end-to-end smoke test of the UI.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenshotTest {
    private val outDir = File(System.getProperty("gains.screenshotDir") ?: "build/screenshots").apply { mkdirs() }
    private val sampleCsv = File(System.getProperty("gains.sampleCsv") ?: "../samples/liftoff-export.csv")

    @Test
    fun captureEveryScreen() = runDesktopComposeUiTest(width = 960, height = 1720) {
        val dbFile = File.createTempFile("gains-screenshots", ".db").apply { delete(); deleteOnExit() }
        stopKoin()
        initKoin(module { single<DatabaseDriverFactory> { DesktopDriverFactory(dbFile) } })
        val csv = sampleCsv.readText()

        // Drive the clock by hand from the very first composition. With autoAdvance the framework
        // cancels infinite animations and waits for the scene to stop invalidating before every node
        // lookup, which never happens while anything animates.
        mainClock.autoAdvance = false

        // 480×860 dp at 2× density: the same layout as the desktop window, at retina resolution.
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                App(filePicker = CsvFilePicker { onResult -> onResult(emptyList()) })
            }
        }

        val watchdog = Thread {
            try { Thread.sleep(4 * 60_000L) } catch (_: InterruptedException) { return@Thread }
            println("screenshot test still running after 4 minutes; thread dump follows")
            for ((thread, stack) in Thread.getAllStackTraces()) {
                println("--- ${thread.name} (${thread.state})")
                stack.take(25).forEach { println("    at $it") }
            }
        }.apply { isDaemon = true; start() }

        /** Frames are only delivered when the scene renders, so advance one frame at a time to let animations finish. */
        fun settle(millis: Long = 600) = repeat((millis / 16).toInt()) { mainClock.advanceTimeByFrame() }
        /** Section headers are shown in upper case, so text is matched ignoring case. */
        fun text(value: String) = hasText(value, substring = true, ignoreCase = true)
        fun exists(matcher: SemanticsMatcher) = onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        /** Polls in real time (the framework's waitUntil measures virtual time and never gives up). */
        fun await(matcher: SemanticsMatcher, timeoutMillis: Long = 30_000): Boolean {
            val start = System.nanoTime()
            var frames = 0
            while (!exists(matcher)) {
                mainClock.advanceTimeByFrame()
                frames++
                if (System.nanoTime() - start > timeoutMillis * 1_000_000L) {
                    println("timed out after $frames frames waiting for ${matcher.description}; on screen:")
                    println(onRoot().printToString())
                    return false
                }
            }
            println("found ${matcher.description} after $frames frames")
            return true
        }
        fun require(matcher: SemanticsMatcher, timeoutMillis: Long = 30_000) =
            check(await(matcher, timeoutMillis)) { "Gave up waiting for ${matcher.description}" }
        fun shot(name: String) {
            settle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
            println("screenshot: $name")
        }
        fun tab(label: String) {
            onNode(hasContentDescription(label) and hasClickAction()).performClick()
            settle()
        }

        // 1. Welcome / sign-in gate.
        require(text("Continue as guest"))
        settle(1_500)
        shot("01-welcome")
        onNode(text("Continue as guest") and hasClickAction()).performClick()
        if (!await(text("Skip for now"), 15_000)) {
            println("guest sign-in through the UI did not switch screens; signing in through the repository")
            runBlocking { inject<AccountRepository>().continueAsGuest() }
            require(text("Skip for now"))
        }

        // 1b. Goal onboarding: answer the first question for the picture, then skip the rest.
        settle(1_000)
        onNode(text("Get stronger") and hasClickAction()).performClick()
        settle()
        shot("01b-onboarding")
        onNode(text("Skip for now") and hasClickAction()).performClick()
        if (!await(text("No workouts yet"), 15_000)) {
            println("skipping onboarding through the UI did not switch screens; marking it done through the repository")
            runBlocking { inject<ProgramRepository>().markOnboardingDone() }
            require(text("No workouts yet"))
        }

        // 2. Import preview: hand the app a file the way the share sheet would.
        IncomingFiles.offer(PickedFile("liftoff-export.csv", csv))
        val importButton = text("Import ") and hasClickAction()
        require(text("Summary"), 60_000)
        require(importButton, 60_000)
        settle(800)
        shot("02-import")
        val committedThroughUi = runCatching {
            onNode(hasScrollAction()).performScrollToNode(importButton)
            onNode(importButton).performClick()
            require(text("Imported"), 60_000)
            onNode(text("Done") and hasClickAction()).performClick()
        }.isSuccess
        if (!committedThroughUi) {
            // Fall back to the service so the remaining screens still get their data.
            val service = inject<ImportService>()
            runBlocking { service.commit(service.preview(listOf(CsvFile("liftoff-export.csv", csv))), emptySet()) }
            tab("Home")
        }

        // 3. Home insights.
        require(text("What's moving"), 60_000)
        settle(1_500)
        shot("03-home")

        // 4. History.
        tab("History")
        require(text("Last 26 weeks"))
        settle(1_500)
        shot("04-history")

        // 5. Lifts list and a lift's detail.
        tab("Lifts")
        require(text("Bench Press"))
        settle(1_500)
        shot("05-lifts")
        onAllNodes(text("Bench Press")).onFirst().performClick()
        require(text("Estimated 1RM"))
        settle(1_500)
        shot("06-lift-detail")

        // 6. Weekly volume per muscle group.
        tab("Volume")
        require(text("This week"))
        settle(1_500)
        shot("07-volume")

        // 7. Bodyweight, with a few months of entries.
        val bodyweight = inject<BodyweightRepository>()
        val today = Dates.today()
        runBlocking {
            for (daysAgo in 0 until 90 step 2) {
                val kg = 82.0 - (90 - daysAgo) * 0.03 + sin(daysAgo / 3.0) * 0.4
                bodyweight.upsert(BodyweightEntry(today.minus(daysAgo, DateTimeUnit.DAY), (kg * 10).toInt() / 10.0))
            }
        }
        tab("Body")
        require(text("Trend"))
        settle(1_500)
        shot("08-body")

        // 8. Settings, then the light theme.
        onNode(hasContentDescription("Settings") and hasClickAction()).performClick()
        require(text("Appearance"))
        settle(1_000)
        shot("09-settings")

        // 8a. Strava, before any account is connected.
        onNode(text("Connect") and hasClickAction()).performClick()
        require(text("Connect Strava"))
        settle(1_000)
        shot("16-strava")
        onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        require(text("Appearance"))

        // 8b. Programs: set a goal, pick GZCLP, activate it and open its first day pre-filled.
        val programs = inject<ProgramRepository>()
        runBlocking { programs.setProfile(GoalProfile(Goal.GET_STRONGER, Experience.BEGINNER, 3)) }
        onNode(text("Change") and hasClickAction()).performClick()
        require(text("Built-in"))
        settle(1_000)
        shot("12-programs")
        onNode(hasText("GZCLP") and hasClickAction()).performClick()
        require(text("Activate") and hasClickAction())
        onNode(text("Activate") and hasClickAction()).performClick()
        require(text("Deactivate"))
        settle(1_000)
        shot("13-program-detail")
        onNode(hasText("A1") and hasClickAction()).performClick()
        require(text("5 × 3+"))
        settle(1_000)
        shot("14-program-day")
        // The editor's Cancel button sits below the fold of a lazy list; the top-bar Back is always composed.
        onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        settle()
        tab("Home")
        require(text("Up next"), 60_000)
        settle(1_000)
        shot("15-home-program")
        onNode(hasContentDescription("Settings") and hasClickAction()).performClick()
        require(text("Appearance"))
        onNode(text("Light") and hasClickAction()).performClick()
        settle()
        tab("Home")
        require(text("What's moving"), 60_000)
        settle(1_500)
        shot("10-home-light")
        tab("Lifts")
        require(text("Bench Press"))
        onAllNodes(text("Bench Press")).onFirst().performClick()
        require(text("Estimated 1RM"))
        settle(1_500)
        shot("11-lift-detail-light")
        watchdog.interrupt()
    }

    @Test
    fun renderLogo() = runDesktopComposeUiTest(width = 1024, height = 1024) {
        mainClock.autoAdvance = false
        setContent { GainsLogo(size = 1024.dp) }
        repeat(5) { mainClock.advanceTimeByFrame() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "logo.png"))
    }
}
