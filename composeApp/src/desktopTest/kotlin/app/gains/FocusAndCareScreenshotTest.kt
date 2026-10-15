package app.gains

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import app.gains.auth.AccountRepository
import app.gains.data.AppLanguage
import app.gains.data.DatabaseDriverFactory
import app.gains.data.DesktopDriverFactory
import app.gains.data.ProgramRepository
import app.gains.data.SettingsRepository
import app.gains.data.ThemeMode
import app.gains.di.initKoin
import app.gains.domain.Caution
import app.gains.domain.FocusArea
import app.gains.platform.CsvFilePicker
import app.gains.platform.applyAppLanguage
import app.gains.ui.inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The newcomer's path through the app: the questions answered the way a regularly active person new
 * to the gym would (fitter, three days, a beginner, glutes and abs, careful with knees and the lower
 * back), the program they are offered, and the workout it turns into. Rendered on a narrow phone
 * (360 dp), in English and then in Russian, which runs about a third longer. Also checks that what
 * was answered is what was stored.
 */
@OptIn(ExperimentalTestApi::class)
class FocusAndCareScreenshotTest {
    private val outDir = File(System.getProperty("gains.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    /** The run switches the app to Russian, which sets the process's locale: give the device's back to the tests after it. */
    @AfterTest
    fun giveTheDeviceLanguageBack() = applyAppLanguage(null)

    @Test
    fun aNewcomerAnswersAndGetsAFittedPlan() = runDesktopComposeUiTest(width = 720, height = 1600) {
        val dbFile = File.createTempFile("gains-focus", ".db").apply { delete(); deleteOnExit() }
        stopKoin()
        val reporter = RecordingReporter()
        initKoin(module {
            single<DatabaseDriverFactory> { DesktopDriverFactory(dbFile) }
            single<ErrorReporter> { reporter }
        })
        runBlocking { inject<AccountRepository>().continueAsGuest() }
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                App(filePicker = CsvFilePicker { it(emptyList()) })
            }
        }

        fun settle(millis: Long = 800) = repeat((millis / 16).toInt()) { mainClock.advanceTimeByFrame() }
        fun text(value: String) = hasText(value, substring = true, ignoreCase = true)
        fun exists(matcher: SemanticsMatcher) = onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        fun require(matcher: SemanticsMatcher, timeoutMillis: Long = 30_000) {
            val start = System.nanoTime()
            while (!exists(matcher)) {
                mainClock.advanceTimeByFrame()
                if (System.nanoTime() - start > timeoutMillis * 1_000_000L) {
                    println(onRoot().printToString())
                    error("Gave up waiting for ${matcher.description}")
                }
            }
        }
        fun tap(label: String) {
            require(text(label) and hasClickAction())
            onAllNodes(text(label) and hasClickAction()).onFirst().performClick()
            settle(600)
        }
        fun shot(name: String) {
            settle()
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
            println("screenshot: $name")
        }
        /** The scrollable list around the first node matching [inList]. */
        fun listAround(inList: SemanticsMatcher): SemanticsNode {
            var node: SemanticsNode? = onAllNodes(inList).onFirst().fetchSemanticsNode()
            while (node != null && SemanticsActions.ScrollBy !in node.config) node = node.parent
            return checkNotNull(node) { "${inList.description} is not in a scrollable list" }
        }
        fun scrollBy(list: SemanticsNode, dy: Float) {
            runOnUiThread { list.config[SemanticsActions.ScrollBy].action?.invoke(0f, dy) }
            settle(1_200)
        }
        /** Pages the scrollable around [inList] by [dy] pixels and lets it land. */
        fun scrollBy(inList: SemanticsMatcher, dy: Float) = scrollBy(listAround(inList), dy)
        /** Scrolls the list holding [target] so that it sits near the top of the screen. */
        fun scrollTo(target: SemanticsMatcher) {
            val node = onAllNodes(target).onFirst().fetchSemanticsNode()
            val list = listAround(target)
            scrollBy(list, node.positionInRoot.y - list.positionInRoot.y - 160f)
        }
        /** Pages the list around [inList] down until [target] is composed. */
        fun scrollUntil(inList: SemanticsMatcher, target: SemanticsMatcher) {
            val list = listAround(inList)
            repeat(12) { if (exists(target)) return; scrollBy(list, 500f) }
            require(target)
        }

        fun walkOnboarding(lang: String, s: Words) {
            require(text(s.generalFitness))
            tap(s.generalFitness); tap(s.next)
            tap(s.next) // three days, the default
            tap(s.beginner); tap(s.next)
            // Focus: two areas, then a third that is refused and says why.
            tap(s.glutes); tap(s.core); tap(s.arms)
            require(text(s.capNote))
            shot("30-focus-$lang")
            tap(s.next)
            // Careful with: knees and the lower back, and what that means.
            tap(s.knees); tap(s.lowerBack)
            shot("31-careful-$lang")
            scrollBy(text(s.knees), 900f)
            shot("31b-careful-guide-$lang")
            tap(s.next)
            require(text(s.fullBody))
            shot("32-programs-fit-$lang")
        }

        // English.
        walkOnboarding("en", english)
        val programs = inject<ProgramRepository>()
        tap("Use this program")
        require(text("Up next"))
        runBlocking {
            assertEquals(setOf(FocusArea.GLUTES, FocusArea.CORE), programs.observeFocus().first())
            assertEquals(setOf(Caution.KNEES, Caution.LOWER_BACK), programs.observeCautions().first())
            assertEquals("full_body_basics", programs.observeActiveProgramId().first())
        }
        shot("33-home-plan-en")
        tap("Up next")
        require(text("Full-body basics"))
        shot("34-program-fit-en")
        scrollUntil(text("Full-body basics"), text("Workout A") and hasClickAction())
        shot("34b-program-days-en")
        tap("Workout A")
        require(hasContentDescription("Start workout") and hasClickAction())
        shot("35-day-fitted-en")
        tap("Training tips")
        shot("35c-guide-dialog-en")
        tap("Done")
        scrollUntil(text("Exercises"), text("For your glutes"))
        scrollBy(text("For your glutes"), 400f)
        shot("35b-day-fitted-extras-en")
        onNode(hasContentDescription("Back") and hasClickAction()).performClick(); settle()
        onNode(hasContentDescription("Back") and hasClickAction()).performClick(); settle()

        // A swap, and the two cautions that come with guidance of their own, on the Focus and health page.
        runBlocking { programs.setCautions(setOf(Caution.KNEES, Caution.PREGNANCY, Caution.BLOOD_PRESSURE)) }
        settle(1_000)
        tap("Change")
        require(text("Focus and health"))
        shot("36-focus-and-health-en")
        scrollUntil(text("Focus and health"), text("Training while pregnant"))
        scrollTo(text("Training while pregnant"))
        tap("Training while pregnant")
        scrollTo(text("Training with high blood pressure"))
        tap("Training with high blood pressure")
        scrollTo(text("Training while pregnant"))
        shot("36b-focus-and-health-guides-en")
        scrollTo(text("Training with high blood pressure"))
        shot("36c-focus-and-health-guides-en")
        // After a baby: lying flat is fine again, crunches and jumps still wait.
        runBlocking { programs.setCautions(setOf(Caution.POSTPARTUM)) }
        settle(1_000)
        scrollUntil(text("Focus and health"), text("Training after a baby"))
        scrollTo(text("Training after a baby"))
        tap("Training after a baby")
        shot("36d-after-a-baby-en")
        runBlocking { programs.setCautions(setOf(Caution.KNEES, Caution.PREGNANCY, Caution.BLOOD_PRESSURE)) }
        settle(1_000)
        onNode(hasContentDescription("Back") and hasClickAction()).performClick(); settle()
        runBlocking { programs.setActive("gzclp") }
        settle(1_000)
        tap("Up next")
        require(text("Switch to Full-body basics"))
        shot("37-gzclp-knees-en")
        // The heavy sets made fixed and cued, and the squat swapped, in the day list.
        scrollUntil(text("GZCLP"), hasText("Up next"))
        scrollTo(hasText("Up next"))
        shot("37c-gzclp-days-en")

        // Light theme.
        runBlocking { inject<SettingsRepository>().setThemeMode(ThemeMode.LIGHT) }
        settle(1_000)
        shot("37b-gzclp-knees-light-en")
        onNode(hasContentDescription("Back") and hasClickAction()).performClick(); settle()

        // Russian, from the start of the questions again.
        runBlocking {
            programs.setFocus(emptySet()); programs.setCautions(emptySet())
            inject<SettingsRepository>().setThemeMode(ThemeMode.DARK)
            inject<SettingsRepository>().setLanguage(AppLanguage.RUSSIAN)
            programs.resetOnboarding()
        }
        settle(1_500)
        walkOnboarding("ru", russian)
        tap("Выбрать эту программу")
        require(text("Дальше"))
        shot("33-home-plan-ru")
        tap("Дальше")
        require(text("Всё тело с нуля"))
        scrollUntil(text("Всё тело с нуля"), text("Тренировка A") and hasClickAction())
        shot("34b-program-days-ru")
        tap("Тренировка A")
        require(text("Упражнения"))
        settle(1_000)
        shot("35-day-fitted-ru")
        scrollUntil(text("Тренировка A"), text("Для ягодиц"))
        scrollBy(text("Для ягодиц"), 400f)
        shot("35b-day-fitted-extras-ru")
        reporter.assertNone()
    }

    private class Words(
        val generalFitness: String, val next: String, val beginner: String,
        val glutes: String, val core: String, val arms: String, val capNote: String,
        val knees: String, val lowerBack: String, val fullBody: String,
    )

    private val english = Words("General fitness", "Next", "Beginner", "Glutes", "Abs and core", "Arms", "Two at most", "Knees", "Lower back", "Full-body basics")
    private val russian = Words("Общая форма", "Далее", "Новичок", "Ягодицы", "Пресс и кор", "Руки", "Не больше двух", "Колени", "Поясница", "Всё тело с нуля")
}
