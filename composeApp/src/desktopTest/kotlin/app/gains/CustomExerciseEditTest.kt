package app.gains

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import app.gains.catalogue.ExerciseCatalogue
import app.gains.data.DatabaseDriverFactory
import app.gains.data.DesktopDriverFactory
import app.gains.data.ExerciseRepository
import app.gains.di.initKoin
import app.gains.domain.Exercise
import app.gains.domain.Modality
import app.gains.domain.MuscleGroup
import app.gains.ui.inject
import app.gains.ui.screens.ExerciseDetailScreen
import app.gains.ui.screens.ExerciseDialog
import app.gains.ui.screens.ExercisePickerSheet
import app.gains.ui.screens.SessionEditorScreen
import app.gains.ui.theme.GainsTheme
import kotlinx.coroutines.runBlocking
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A custom exercise made with a guess can be put right: "bouldering" made from a workout's search box
 * came out as weight × reps, and neither its page nor the New exercise form could change that. Now its
 * page edits it, the form offers to edit it when its name is typed again, and the workout's picker asks
 * how a new name is logged before making it.
 */
@OptIn(ExperimentalTestApi::class)
class CustomExerciseEditTest {
    private val outDir = File(System.getProperty("gains.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    @Test
    fun aGuessedCustomExerciseIsPutRight() = runDesktopComposeUiTest(width = 960, height = 1720) {
        val dbFile = File.createTempFile("gains-custom-edit", ".db").apply { delete(); deleteOnExit() }
        stopKoin()
        val reporter = RecordingReporter()
        initKoin(module {
            single<DatabaseDriverFactory> { DesktopDriverFactory(dbFile) }
            single<ErrorReporter> { reporter }
        })
        val exercises = inject<ExerciseRepository>()
        val guessed = ExerciseCatalogue.custom("bouldering", Modality.WEIGHTED, primary = emptySet())
        runBlocking { exercises.seedCatalogue(); exercises.insertIfMissing(listOf(guessed)) }
        fun stored(id: String = guessed.id): Exercise? = runBlocking { exercises.exercises().firstOrNull { it.id == id } }

        /** Which surface is on screen: the exercise's page, the New exercise form, or a workout's picker. */
        var showing by mutableStateOf("page")
        var picked by mutableStateOf<List<Exercise>>(emptyList())
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                GainsTheme(darkTheme = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        when (showing) {
                            "page" -> ExerciseDetailScreen(guessed.id)
                            "form" -> ExerciseDialog(onDismiss = { showing = "" }, onSaved = { showing = "" })
                            "workout" -> SessionEditorScreen(sessionId = null, onDone = {})
                            "picker" -> ExercisePickerSheet(
                                catalogue = runBlocking { exercises.exercises() }, recent = emptyList(), alreadyAdded = emptySet(),
                                onAdd = { picked = it }, onCreated = {}, onDismiss = { showing = "" },
                            )
                        }
                    }
                }
            }
        }

        fun settle(millis: Long = 800) = repeat((millis / 16).toInt()) { mainClock.advanceTimeByFrame() }
        fun text(value: String) = hasText(value, substring = true, ignoreCase = true)
        fun exists(matcher: SemanticsMatcher) = onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        fun require(matcher: SemanticsMatcher) {
            val start = System.nanoTime()
            while (!exists(matcher)) {
                mainClock.advanceTimeByFrame()
                Thread.sleep(2)
                if (System.nanoTime() - start > 30_000_000_000L) {
                    onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { println(onAllNodes(isRoot())[it].printToString()) }
                    error("Gave up waiting for ${matcher.description}")
                }
            }
        }
        // Not the search box, which holds the same words once they are typed.
        fun tap(label: String) {
            val matcher = hasText(label) and hasClickAction() and !hasSetTextAction()
            require(matcher)
            onAllNodes(matcher).onFirst().performClick()
            settle(600)
        }
        fun until(what: String, check: () -> Boolean) {
            val start = System.nanoTime()
            while (!check()) {
                mainClock.advanceTimeByFrame()
                Thread.sleep(2)
                check(System.nanoTime() - start < 30_000_000_000L) { "Gave up waiting for $what" }
            }
            settle()
        }
        fun shot(name: String) {
            settle()
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
        }

        // 1. The page of the guessed exercise has an Edit, which opens the form filled in with it.
        require(text("bouldering"))
        tap("Edit")
        require(text("Edit exercise"))
        tap("Bodyweight")
        require(text("Sets already logged keep their numbers"))
        tap("Lats")
        tap("Forearms")
        tap("Biceps"); tap("Biceps") // twice: a secondary muscle
        shot("custom-edit-1-form")
        tap("Save")
        until("the edit to be stored") { stored()?.modality == Modality.BODYWEIGHT }
        assertEquals(setOf(MuscleGroup.LATS to 1.0, MuscleGroup.FOREARMS to 1.0, MuscleGroup.BICEPS to 0.5), stored()!!.muscleGroups.map { it.group to it.weight }.toSet())
        require(text("Bodyweight"))
        shot("custom-edit-2-page")

        // 2. Typing its name into New exercise again, with another way of logging picked, edits it
        // rather than only pointing at it, and keeps what was picked.
        showing = "form"
        settle()
        require(hasSetTextAction())
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("Bouldering")
        tap("Isometric")
        require(text("You already made"))
        tap("Edit it")
        require(text("Edit exercise"))
        onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Bouldering")
        tap("Save")
        until("the form to close") { showing == "" }
        until("the second edit to be stored") { stored()?.modality == Modality.ISOMETRIC }
        assertEquals("Bouldering", stored()!!.name)
        assertEquals(guessed.id, stored()!!.id)

        // 3. A new name typed in a workout's picker asks how it is logged before it is made, then is ticked.
        showing = "picker"
        settle()
        require(hasSetTextAction())
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("Hangboard repeaters")
        tap("Create \"Hangboard repeaters\"")
        require(text("New exercise"))
        tap("Isometric")
        shot("custom-edit-3-picker-form")
        tap("Create")
        val hangboard = ExerciseCatalogue.custom("Hangboard repeaters", Modality.ISOMETRIC, primary = emptySet()).id
        until("the new exercise to be stored") { stored(hangboard) != null }
        assertEquals(Modality.ISOMETRIC, stored(hangboard)!!.modality)
        tap("Add 1 exercise")
        until("the new exercise to be added") { picked.isNotEmpty() }
        assertNotNull(picked.singleOrNull { it.id == hangboard && it.modality == Modality.ISOMETRIC })

        // 4. In a workout, the card's menu edits it too, and the card follows at once.
        showing = "workout"
        settle()
        tap("+ Add exercise")
        require(hasSetTextAction())
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("Bouldering")
        tap("Bouldering")
        tap("Add 1 exercise")
        require(hasContentDescription("Options for Bouldering"))
        onAllNodes(hasContentDescription("Options for Bouldering") and hasClickAction()).onFirst().performClick()
        settle(600)
        tap("Edit exercise")
        tap("Cardio")
        tap("Save")
        until("the workout's edit to be stored") { stored()?.modality == Modality.CARDIO }
        require(hasText("Cardio"))
        shot("custom-edit-4-workout-card")
        reporter.assertNone()
    }
}
