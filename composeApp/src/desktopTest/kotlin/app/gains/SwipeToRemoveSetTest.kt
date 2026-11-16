package app.gains

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
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
import app.gains.ui.inject
import app.gains.ui.screens.SessionEditorScreen
import app.gains.ui.theme.GainsTheme
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A set row swiped to the left goes, and only that set: the rows below move up and renumber, and the
 * one that takes its place is not left swiped. A short drag springs back and removes nothing.
 */
@OptIn(ExperimentalTestApi::class)
class SwipeToRemoveSetTest {
    private val outDir = File(System.getProperty("gains.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    private fun set(n: Int): SemanticsMatcher = hasContentDescription("Set $n options", substring = true)

    private fun ComposeUiTest.setCount() = (1..9).count { onAllNodes(set(it)).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.dragRow(n: Int, fraction: Float) {
        val y = onAllNodes(set(n)).onFirst().fetchSemanticsNode().boundsInRoot.center.y
        val root = onAllNodes(isRoot()).onFirst()
        val width = root.fetchSemanticsNode().boundsInRoot.width
        root.performTouchInput { swipe(Offset(width * 0.85f, y), Offset(width * (0.85f - fraction), y), durationMillis = 400) }
        repeat(60) { mainClock.advanceTimeByFrame() }
    }

    private fun workout(dark: Boolean = true, test: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(width = 780, height = 1720) {
        val dbFile = File.createTempFile("gains-swipe", ".db").apply { delete(); deleteOnExit() }
        stopKoin()
        initKoin(module {
            single<DatabaseDriverFactory> { DesktopDriverFactory(dbFile) }
            single<ErrorReporter> { RecordingReporter() }
        })
        val at = LocalDateTime(2026, 10, 24, 18, 0)
        val squat = Session(
            id = at.toString(), timestamp = at, durationMinutes = 45, source = Session.MANUAL,
            exercises = listOf(ExerciseEntry("squat", listOf(60.0 to 5, 70.0 to 5, 80.0 to 5).mapIndexed { i, (kg, reps) -> SetEntry(i, SetType.WEIGHTED, kg, reps) })),
        )
        runBlocking {
            inject<ExerciseRepository>().seedCatalogue()
            inject<SessionRepository>().upsertAll(listOf(squat))
        }
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                GainsTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        SessionEditorScreen(sessionId = squat.id, onDone = {})
                    }
                }
            }
        }
        val start = System.nanoTime()
        while (onAllNodes(set(3)).fetchSemanticsNodes().isEmpty()) {
            mainClock.advanceTimeByFrame()
            check(System.nanoTime() - start < 30_000_000_000L) { "The workout never showed three sets" }
        }
        repeat(30) { mainClock.advanceTimeByFrame() }
        test()
    }

    @Test
    fun aSwipeLeftRemovesThatSetAlone() = workout {
        dragRow(2, fraction = 0.7f)
        assertEquals(2, setCount())
        // The set that moved up into the gap can itself be swiped away: it did not inherit the swipe.
        dragRow(2, fraction = 0.7f)
        assertEquals(1, setCount())
    }

    /** Mid-swipe, for the screenshots: the red with "Remove" behind the row being dragged. */
    private fun midSwipe(name: String, dark: Boolean) = workout(dark) {
        val y = onAllNodes(set(2)).onFirst().fetchSemanticsNode().boundsInRoot.center.y
        val root = onAllNodes(isRoot()).onFirst()
        val width = root.fetchSemanticsNode().boundsInRoot.width
        root.performTouchInput { down(Offset(width * 0.6f, y)); repeat(10) { moveBy(Offset(-width * 0.035f, 0f)) } }
        repeat(10) { mainClock.advanceTimeByFrame() }
        ImageIO.write(root.captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
        root.performTouchInput { up() }
    }

    @Test
    fun midSwipeDark() = midSwipe("14f-set-swipe", dark = true)

    @Test
    fun midSwipeLight() = midSwipe("14g-set-swipe-light", dark = false)

    @Test
    fun aShortDragSpringsBack() = workout {
        dragRow(2, fraction = 0.15f)
        assertEquals(3, setCount())
    }
}
