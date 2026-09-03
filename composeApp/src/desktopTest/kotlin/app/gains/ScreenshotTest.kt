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
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.data.BodyweightRepository
import app.gains.data.DatabaseDriverFactory
import app.gains.data.DesktopDriverFactory
import app.gains.di.initKoin
import app.gains.domain.BodyweightEntry
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

        // 480×860 dp at 2× density: the same layout as the desktop window, at retina resolution.
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                App(filePicker = CsvFilePicker { onResult -> onResult(emptyList()) })
            }
        }

        fun exists(matcher: SemanticsMatcher) = onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        fun await(matcher: SemanticsMatcher, timeoutMillis: Long = 30_000) = waitUntil(timeoutMillis = timeoutMillis) { exists(matcher) }
        fun shot(name: String) {
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
            println("screenshot: $name")
        }
        fun tab(label: String) {
            onNode(hasContentDescription(label) and hasClickAction()).performClick()
            waitForIdle()
        }

        // 1. Welcome / sign-in gate.
        await(hasText("Continue as guest"))
        mainClock.advanceTimeBy(1_500)
        shot("01-welcome")
        onNode(hasText("Continue as guest") and hasClickAction()).performClick()
        await(hasText("No workouts yet"))

        // 2. Import preview: hand the app a file the way the share sheet would.
        IncomingFiles.offer(PickedFile("liftoff-export.csv", csv))
        val importButton = hasText("Import ", substring = true) and hasClickAction()
        await(hasText("Summary"))
        await(importButton, 60_000)
        mainClock.advanceTimeBy(800)
        shot("02-import")
        val committedThroughUi = runCatching {
            onNode(hasScrollAction()).performScrollToNode(importButton)
            onNode(importButton).performClick()
            await(hasText("Imported"), 60_000)
            onNode(hasText("Done") and hasClickAction()).performClick()
        }.isSuccess
        if (!committedThroughUi) {
            // Fall back to the service so the remaining screens still get their data.
            val service = inject<ImportService>()
            runBlocking { service.commit(service.preview(listOf(CsvFile("liftoff-export.csv", csv))), emptySet()) }
            tab("Home")
        }

        // 3. Home insights.
        await(hasText("What's moving"), 60_000)
        mainClock.advanceTimeBy(1_500)
        shot("03-home")

        // 4. History.
        tab("History")
        await(hasText("Last 26 weeks"))
        mainClock.advanceTimeBy(1_500)
        shot("04-history")

        // 5. Lifts list and a lift's detail.
        tab("Lifts")
        await(hasText("Bench Press"))
        mainClock.advanceTimeBy(1_500)
        shot("05-lifts")
        onAllNodes(hasText("Bench Press")).onFirst().performClick()
        await(hasText("Estimated 1RM", substring = true))
        mainClock.advanceTimeBy(1_500)
        shot("06-lift-detail")

        // 6. Weekly volume per muscle group.
        tab("Volume")
        await(hasText("This week", substring = true))
        mainClock.advanceTimeBy(1_500)
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
        await(hasText("Trend"))
        mainClock.advanceTimeBy(1_500)
        shot("08-body")

        // 8. Settings, then the light theme.
        onNode(hasContentDescription("Settings") and hasClickAction()).performClick()
        await(hasText("Appearance"))
        shot("09-settings")
        onNode(hasText("Light") and hasClickAction()).performClick()
        waitForIdle()
        tab("Home")
        await(hasText("What's moving"), 60_000)
        mainClock.advanceTimeBy(1_500)
        shot("10-home-light")
        tab("Lifts")
        await(hasText("Bench Press"))
        onAllNodes(hasText("Bench Press")).onFirst().performClick()
        await(hasText("Estimated 1RM", substring = true))
        mainClock.advanceTimeBy(1_500)
        shot("11-lift-detail-light")
    }

    @Test
    fun renderLogo() = runDesktopComposeUiTest(width = 1024, height = 1024) {
        setContent { GainsLogo(size = 1024.dp) }
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "logo.png"))
    }
}
