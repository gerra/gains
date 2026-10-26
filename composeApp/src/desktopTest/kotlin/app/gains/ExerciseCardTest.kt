package app.gains

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
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

/**
 * An exercise card with every set ticked, two of them records: the Done and Record pills at its
 * head, a trophy in place of a record set's number and a green dot beside each set that beat last
 * time. Opened as a logged workout, whose sets load ticked, after an older one to compare with.
 * Rendered dark, light and in Russian on a 390 dp phone.
 */
@OptIn(ExperimentalTestApi::class)
class ExerciseCardTest {
    private val outDir = File(System.getProperty("gains.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    @AfterTest
    fun giveTheDeviceLanguageBack() = applyAppLanguage(null)

    private fun squat(at: LocalDateTime, vararg sets: Pair<Double, Int>) = Session(
        id = at.toString(), timestamp = at, durationMinutes = 45, source = Session.MANUAL,
        exercises = listOf(ExerciseEntry("squat", sets.mapIndexed { i, (kg, reps) -> SetEntry(i, SetType.WEIGHTED, kg, reps) })),
    )

    private fun render(name: String, dark: Boolean, language: String?, vararg expected: SemanticsMatcher) = runDesktopComposeUiTest(width = 780, height = 1720) {
        val dbFile = File.createTempFile("gains-card", ".db").apply { delete(); deleteOnExit() }
        stopKoin()
        initKoin(module {
            single<DatabaseDriverFactory> { DesktopDriverFactory(dbFile) }
            single<ErrorReporter> { RecordingReporter() }
        })
        applyAppLanguage(language)
        val today = squat(LocalDateTime(2026, 10, 24, 18, 0), 55.0 to 12, 60.0 to 8, 60.0 to 9)
        runBlocking {
            inject<ExerciseRepository>().seedCatalogue()
            inject<SessionRepository>().upsertAll(listOf(squat(LocalDateTime(2026, 10, 20, 18, 0), 50.0 to 10, 55.0 to 10, 55.0 to 11), today))
        }
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                GainsTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        SessionEditorScreen(sessionId = today.id, onDone = {})
                    }
                }
            }
        }
        for (matcher in expected) {
            val start = System.nanoTime()
            while (onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
                mainClock.advanceTimeByFrame()
                if (System.nanoTime() - start > 30_000_000_000L) {
                    println(onAllNodes(isRoot()).onFirst().printToString())
                    error("Gave up waiting for ${matcher.description}")
                }
            }
        }
        repeat(60) { mainClock.advanceTimeByFrame() }
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
    }

    @Test
    fun aFinishedLiftSaysSoAtItsHead() = render(
        "14c-exercise-done", dark = true, language = null,
        hasText("Done"), hasText("Record"),
        hasContentDescription("Set 1, a record", substring = true),
        hasContentDescription("Set 3, a record", substring = true),
        hasContentDescription("more than last time", substring = true),
    )

    @Test
    fun itReadsOnTheLightTheme() = render("14d-exercise-done-light", dark = false, language = null, hasText("Record"))

    @Test
    fun itReadsInRussian() = render("14e-exercise-done-ru", dark = true, language = "ru", hasText("Рекорд"))
}
