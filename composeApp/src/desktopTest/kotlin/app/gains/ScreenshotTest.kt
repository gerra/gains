package app.gains

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.gains.analysis.Dates
import app.gains.analysis.Dates.minusDays
import app.gains.analysis.Dates.plusDays
import app.gains.analysis.Streak
import app.gains.analysis.StreakStatus
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
import app.gains.ui.charts.BodyMapModel
import app.gains.ui.components.GainsLogo
import app.gains.ui.components.StreakCard
import app.gains.ui.theme.GainsTheme
import app.gains.ui.inject
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
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
        val csv = endingYesterday(sampleCsv.readText())

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
            // A bottom sheet or dialog is a root of its own; the scene is rendered whole and cropped to the first
            // root's bounds, which are the full window, so onRoot() (exactly one root) is not used here.
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
            println("screenshot: $name")
        }
        fun tab(label: String) {
            onNode(hasContentDescription(label) and hasClickAction()).performClick()
            settle()
        }
        /**
         * Scrolls the first node matching [matcher] into its list's viewport. performScrollTo() is no use
         * here: it repeats the list's scroll action until the node is in view, but a LazyColumn scrolls
         * with an animation and the clock is manual, so that loop never ends. Scroll once, then run frames.
         */
        fun scrollIntoView(matcher: SemanticsMatcher) {
            val target = onAllNodes(matcher).onFirst().fetchSemanticsNode("Nothing to scroll to: ${matcher.description}")
            var scrollable: SemanticsNode? = target.parent
            while (scrollable != null && SemanticsActions.ScrollBy !in scrollable.config) scrollable = scrollable.parent
            val list = checkNotNull(scrollable) { "${matcher.description} is not inside a scrollable list" }
            val viewport = list.boundsInRoot
            val top = target.positionInRoot.y
            val bottom = top + target.size.height
            // Room for a row to appear above the target (ticking a set inserts the rest countdown).
            val margin = 240f
            val dy = when {
                bottom > viewport.bottom -> bottom - viewport.bottom + margin
                top < viewport.top -> top - viewport.top - margin
                else -> return
            }
            runOnUiThread { list.config[SemanticsActions.ScrollBy].action?.invoke(0f, dy) }
            settle(1_500)
        }
        /**
         * Pages the list containing [inList] down half a viewport at a time until a node matches [target].
         * A LazyColumn only composes the rows in view, so a row below the fold cannot be found, let
         * alone scrolled to with [scrollIntoView]; the list is paged until the row appears. Half a
         * viewport per step so that no row can pass through the viewport between two looks.
         */
        fun scrollUntil(inList: SemanticsMatcher, target: SemanticsMatcher, pages: Int = 12) {
            val inside = onAllNodes(inList).onFirst().fetchSemanticsNode("Nothing to page from: ${inList.description}")
            var scrollable: SemanticsNode? = inside
            while (scrollable != null && SemanticsActions.ScrollBy !in scrollable.config) scrollable = scrollable.parent
            val list = checkNotNull(scrollable) { "${inList.description} is not inside a scrollable list" }
            val page = list.size.height / 2f
            repeat(pages) {
                if (exists(target)) return
                runOnUiThread { list.config[SemanticsActions.ScrollBy].action?.invoke(0f, page) }
                settle(1_500)
            }
            check(exists(target)) { "Paged $pages half-screens without finding ${target.description}" }
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
        // 6b. Tap the left pec on the muscle map: the chest is outlined and the list narrows to it.
        val map = onNode(hasContentDescription("Muscle map"))
        val mapScale = map.fetchSemanticsNode().size.width / BodyMapModel.TOTAL_WIDTH
        map.performTouchInput { click(Offset(310f * mapScale, 375f * mapScale)) }
        require(text("Show all"))
        require(text("Chest"))
        settle(1_500)
        shot("07b-volume-muscle")
        onNode(text("Show all") and hasClickAction()).performClick()
        settle()
        check(!exists(text("Show all"))) { "Show all did not clear the muscle-map selection" }

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
        // A day opens ready, with the plan to look over; nothing runs until Start is pressed.
        val startButton = hasContentDescription("Start workout") and hasClickAction()
        require(startButton)
        settle(1_000)
        shot("14-program-day-ready")
        onNode(startButton).performClick()
        // Start runs it as a timed workout: the clock is pinned on top and ticking a set starts the rest countdown.
        require(text("Total"))
        // The clock and day notes push the first work set below the fold, so bring it into view before tapping.
        val firstSet = hasContentDescription("Set 1 not done") and hasClickAction()
        scrollIntoView(firstSet)
        onAllNodes(firstSet).onFirst().performClick()
        require(hasContentDescription("Set 1 done"))
        // Ticking inserted the rest countdown above the row, so bring the ticked row back into view.
        scrollIntoView(hasContentDescription("Set 1 done"))
        settle(1_000)
        shot("14-program-day")
        // The weight of a set is picked on a wheel rather than typed.
        onAllNodes(hasContentDescription("Weight for set 1,", substring = true) and hasClickAction()).onFirst().performClick()
        require(text("No weight"))
        settle(1_000)
        shot("14b-weight-picker")
        onNode(hasText("Done") and hasClickAction()).performClick()
        settle(1_500)
        require(hasText("End") and hasClickAction())
        // Leaving with Back keeps the workout running; every other screen shows it above the tabs.
        onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        settle()
        tab("Home")
        require(text("Up next"), 60_000)
        require(text("Resume"))
        settle(1_000)
        shot("15-home-program")
        // 9. Logging a past workout: the date, time and duration are chosen, not typed.
        onNode(hasContentDescription("Add") and hasClickAction()).performClick()
        require(text("Log past workout"))
        onNode(text("Log past workout") and hasClickAction()).performClick()
        require(text("Not timed"))
        settle(1_000)
        shot("16-log-workout")
        onNode(hasContentDescription("Date:", substring = true) and hasClickAction()).performClick()
        require(hasText("Done") and hasClickAction())
        settle(1_000)
        shot("16b-date-picker")
        onNode(hasText("Done") and hasClickAction()).performClick()
        settle(1_500)
        onNode(hasText("Cancel") and hasClickAction()).performClick()
        require(text("Up next"), 60_000)
        // Back into the workout through the bar, then end it: the stored session takes the timed duration.
        onNode(text("Resume") and hasClickAction()).performClick()
        require(text("Total"))
        require(hasContentDescription("Set 1 done"))
        onNode(hasText("End") and hasClickAction()).performClick()
        // Only one set was ticked, so ending asks about the rest; keep them all.
        require(text("aren't ticked off"))
        onNode(text("Save all") and hasClickAction()).performClick()
        require(text("What's moving"), 60_000)
        settle(1_000)
        check(!exists(text("Resume"))) { "The resume bar is still showing after the session was ended" }
        // The ended workout is an ordinary logged session in history, tagged with its day. The session
        // rows sit below the calendar and the weekly chart, past the end of the list's first viewport.
        tab("History")
        require(text("Last 26 weeks"))
        scrollUntil(text("Last 26 weeks"), text("logged"))
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

    /**
     * The streak card in every state it has, side by side: what the nudge looks like on the two days
     * it fires, what it says instead when a rest week would cover the miss, and what a safe, a full
     * and an empty week look like. The end-to-end run above can only ever show one of these, because
     * it can only show the week it happens to run in.
     */
    @Test
    fun captureTheStreakCardInEveryState() = runDesktopComposeUiTest(width = 960, height = 5200) {
        mainClock.autoAdvance = false
        // Saturday and Sunday of a week with nothing in it: the ring around today is breathing.
        val atRisk = Streak(weeks = 12, best = 12, sessionsThisWeek = 0, goalPerWeek = 3, daysLeftInWeek = 2, status = StreakStatus.AT_RISK, nextMilestone = 26)
        val covered = atRisk.copy(daysLeftInWeek = 1, status = StreakStatus.LAST_CHANCE, restWeeksInHand = 2)
        val states = listOf(
            "At risk — Saturday, nothing logged" to atRisk,
            "Last day, and a rest week would cover it" to covered,
            "Safe, one short of the week's goal" to Streak(
                weeks = 13, best = 13, sessionsThisWeek = 2, goalPerWeek = 3, daysLeftInWeek = 3,
                status = StreakStatus.SAFE, thisWeekSessions = listOf(1, 0, 1, 0, 0, 0, 0), restWeeksInHand = 1, nextMilestone = 26,
            ),
            "A full week, and a milestone with it" to Streak(
                weeks = 26, best = 26, sessionsThisWeek = 4, goalPerWeek = 4, daysLeftInWeek = 2,
                status = StreakStatus.SAFE, thisWeekSessions = listOf(1, 0, 1, 0, 2, 0, 0), atMilestone = true, nextMilestone = 52,
            ),
            "A rest week carried last week" to Streak(
                weeks = 9, best = 14, sessionsThisWeek = 1, goalPerWeek = 3, daysLeftInWeek = 4,
                status = StreakStatus.SAFE, thisWeekSessions = listOf(0, 0, 0, 1, 0, 0, 0), heldLastWeek = true, nextMilestone = 12,
            ),
            "Nothing to protect yet" to Streak(goalPerWeek = 3, daysLeftInWeek = 5),
        )
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                GainsTheme(darkTheme = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for ((caption, streak) in states) {
                                Text(caption.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                StreakCard(streak, Modifier.fillMaxWidth())
                            }
                            // The same card in the light theme, to show it is not a dark-only design.
                            Text("THE SAME CARD, LIGHT THEME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            GainsTheme(darkTheme = false) {
                                Surface(color = MaterialTheme.colorScheme.background) { StreakCard(atRisk, Modifier.fillMaxWidth()) }
                            }
                        }
                    }
                }
            }
        }
        // The at-risk ring is a slow pulse; land on a frame where it is drawn close to full.
        repeat(90) { mainClock.advanceTimeByFrame() }
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(outDir, "17-streak-states.png"))
    }

    /**
     * The sample export ends whenever it was generated, which after a few weeks is a lifter who has
     * stopped training: every picture would show an empty week and a streak of zero. Each row is
     * moved forward by the same number of days so the history ends yesterday, which leaves the shape
     * of the eight months exactly as it was and makes the screenshots look like a log in use.
     */
    private fun endingYesterday(csv: String): String {
        val rows = csv.lines()
        fun dateOf(row: String) = row.take(10).takeIf { it.length == 10 && it[4] == '-' }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val last = rows.drop(1).mapNotNull(::dateOf).maxOrNull() ?: return csv
        val shift = last.daysUntil(Dates.today().minusDays(1))
        if (shift == 0) return csv
        return rows.joinToString("\n") { row ->
            val date = dateOf(row) ?: return@joinToString row
            (if (shift > 0) date.plusDays(shift) else date.minusDays(-shift)).toString() + row.drop(10)
        }
    }

    @Test
    fun renderLogo() = runDesktopComposeUiTest(width = 1024, height = 1024) {
        mainClock.autoAdvance = false
        setContent { GainsLogo(size = 1024.dp) }
        repeat(5) { mainClock.advanceTimeByFrame() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "logo.png"))
    }
}
