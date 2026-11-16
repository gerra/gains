package app.gains

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import app.gains.data.DatabaseDriverFactory
import app.gains.data.DesktopDriverFactory
import app.gains.data.ExerciseRepository
import app.gains.data.SessionRepository
import app.gains.di.initKoin
import app.gains.domain.ExerciseEntry
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import app.gains.platform.applyAppLanguage
import app.gains.ui.components.LocalNumberPadOpen
import app.gains.ui.components.NUMBER_PAD_TAG
import app.gains.ui.components.PadInput
import app.gains.ui.components.PadKey
import app.gains.ui.inject
import app.gains.ui.screens.SessionEditorScreen
import app.gains.ui.theme.GainsTheme
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The workout's own number pad: what its keys do to a value, and, in a logged squat's set table,
 * that a reps cell opens it, the first digit replaces the planned reps, Next walks the table, and
 * the hide key and Done put it away. Rendered dark, light and in Russian on a 390 dp phone.
 */
@OptIn(ExperimentalTestApi::class)
class NumberPadTest {
    private val outDir = File(System.getProperty("gains.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    @AfterTest
    fun giveTheDeviceLanguageBack() = applyAppLanguage(null)

    private fun type(value: String, vararg keys: PadKey, replace: Boolean = false, decimal: Boolean = false, maxLength: Int = 3): String {
        var v = value
        var fresh = replace
        for (key in keys) { v = PadInput.apply(v, key, fresh, decimal, maxLength); fresh = false }
        return v
    }

    private fun digits(s: String) = s.map { if (it == '.') PadKey.Point else PadKey.Digit(it - '0') }.toTypedArray()

    @Test
    fun theFirstKeyReplacesWhatWasThere() {
        assertEquals("10", type("12", *digits("10"), replace = true))
        assertEquals("121", type("12", *digits("1")))
        assertEquals("", type("12", PadKey.Backspace, replace = true))
        assertEquals("1", type("12", PadKey.Backspace))
    }

    @Test
    fun plusAndMinusStepByOne() {
        assertEquals("13", type("12", PadKey.Plus, replace = true))
        assertEquals("11", type("12", PadKey.Minus))
        assertEquals("0", type("0", PadKey.Minus))
        assertEquals("1", type("", PadKey.Plus))
        assertEquals("", type("", PadKey.Minus))
        // A distance keeps its decimals as typed.
        assertEquals("6.25", type("5.25", PadKey.Plus, decimal = true, maxLength = 6))
    }

    @Test
    fun aValueStaysAValue() {
        // Capped in length, one point at most and only where a value can have one, no leading zero.
        assertEquals("999", type("999", *digits("9")))
        assertEquals("12", type("12", PadKey.Point))
        assertEquals("5.2", type("5.2", PadKey.Point, decimal = true, maxLength = 6))
        assertEquals("0.5", type("", *digits(".5"), decimal = true, maxLength = 6))
        assertEquals("7", type("0", *digits("7")))
    }

    private fun squat(at: LocalDateTime, vararg sets: Pair<Double, Int>) = Session(
        id = at.toString(), timestamp = at, durationMinutes = 45, source = Session.MANUAL,
        exercises = listOf(ExerciseEntry("squat", sets.mapIndexed { i, (kg, reps) -> SetEntry(i, SetType.WEIGHTED, kg, reps) })),
    )

    private fun editor(dark: Boolean = true, language: String? = null, test: ComposeUiTest.(padOpen: () -> Boolean) -> Unit) = runDesktopComposeUiTest(width = 780, height = 1720) {
        val dbFile = File.createTempFile("gains-pad", ".db").apply { delete(); deleteOnExit() }
        stopKoin()
        initKoin(module {
            single<DatabaseDriverFactory> { DesktopDriverFactory(dbFile) }
            single<ErrorReporter> { RecordingReporter() }
        })
        applyAppLanguage(language)
        val workout = squat(LocalDateTime(2026, 10, 24, 18, 0), 60.0 to 12, 60.0 to 8, 60.0 to 9)
        runBlocking {
            inject<ExerciseRepository>().seedCatalogue()
            inject<SessionRepository>().upsertAll(listOf(workout))
        }
        val padOpen = mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f), LocalNumberPadOpen provides padOpen) {
                GainsTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        SessionEditorScreen(sessionId = workout.id, onDone = {})
                    }
                }
            }
        }
        test { padOpen.value }
    }

    private fun ComposeUiTest.await(matcher: SemanticsMatcher) {
        val start = System.nanoTime()
        while (onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            mainClock.advanceTimeByFrame()
            if (System.nanoTime() - start > 30_000_000_000L) {
                println(onAllNodes(isRoot()).onFirst().printToString())
                error("Gave up waiting for ${matcher.description}")
            }
        }
    }

    private fun ComposeUiTest.padShown() = onAllNodes(hasTestTag(NUMBER_PAD_TAG)).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.key(label: String) =
        onAllNodes((hasText(label) or hasContentDescription(label)) and hasClickAction() and hasAnyAncestor(hasTestTag(NUMBER_PAD_TAG))).onFirst()
    private fun reps(set: Int, value: String) = hasContentDescription("Reps for set $set, $value") and hasClickAction()

    @Test
    fun repsAreTypedOnThePad() = editor { padOpen ->
        await(reps(1, "12"))
        assertFalse(padShown())
        onAllNodes(reps(1, "12")).onFirst().performClick()
        waitForIdle()
        assertTrue(padShown())
        assertTrue(padOpen(), "the frame is told to fold the tabs")
        onAllNodes(reps(1, "12")).onFirst().assertIsFocused()
        // The planned 12 is replaced, not appended to.
        key("1").performClick()
        key("0").performClick()
        await(reps(1, "10"))
        key("One more").performClick()
        await(reps(1, "11"))
        // Next walks down the table.
        key("Next").performClick()
        waitForIdle()
        onAllNodes(reps(2, "8")).onFirst().assertIsFocused()
        key("6").performClick()
        await(reps(2, "6"))
        key("Next").performClick()
        waitForIdle()
        // The last cell's key puts the pad away.
        key("Done").performClick()
        waitForIdle()
        assertFalse(padShown())
        assertFalse(padOpen(), "the tabs come back")
        // And so does the key that hides it.
        onAllNodes(reps(3, "9")).onFirst().performClick()
        waitForIdle()
        assertTrue(padShown())
        key("Hide keyboard").performClick()
        waitForIdle()
        assertFalse(padShown())
    }

    private fun shot(name: String, dark: Boolean, language: String?, setDescription: String) = editor(dark, language) { _ ->
        mainClock.autoAdvance = false
        val cell = hasContentDescription(setDescription) and hasClickAction()
        await(cell)
        onAllNodes(cell).onFirst().performClick()
        repeat(60) { mainClock.advanceTimeByFrame() }
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
    }

    @Test
    fun itReadsOnTheDarkTheme() = shot("14f-number-pad", dark = true, language = null, "Reps for set 2, 8")

    @Test
    fun itReadsOnTheLightTheme() = shot("14g-number-pad-light", dark = false, language = null, "Reps for set 2, 8")

    @Test
    fun itReadsInRussian() = shot("14h-number-pad-ru", dark = true, language = "ru", "Повторы подхода 2, 8")
}
