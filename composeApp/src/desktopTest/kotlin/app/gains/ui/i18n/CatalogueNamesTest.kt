package app.gains.ui.i18n

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.gains.catalogue.ExerciseCatalogue
import app.gains.catalogue.ProgramCatalogue
import app.gains.resources.Res
import app.gains.resources.allStringResources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The catalogues' English names come back from the resources unchanged (the test JVM runs in English). */
@OptIn(ExperimentalTestApi::class)
class CatalogueNamesTest {
    @Test
    fun builtInExercisesProgramsAndDaysResolveToTheirCatalogueNames() = runDesktopComposeUiTest {
        val shown = mutableMapOf<String, String>()
        setContent {
            for (exercise in ExerciseCatalogue.builtIn) shown["exercise ${exercise.id}"] = exercise.displayName()
            for (program in ProgramCatalogue.builtIn) {
                shown["program ${program.id}"] = program.displayName()
                shown["description ${program.id}"] = program.displayDescription()
                for (day in program.days) shown["day ${program.id}/${day.id}"] = day.displayName()
            }
        }
        waitForIdle()
        for (exercise in ExerciseCatalogue.builtIn) assertEquals(exercise.name, shown["exercise ${exercise.id}"])
        for (program in ProgramCatalogue.builtIn) {
            assertEquals(program.name, shown["program ${program.id}"])
            assertEquals(program.description, shown["description ${program.id}"])
            for (day in program.days) assertEquals(day.name, shown["day ${program.id}/${day.id}"])
        }
    }

    @Test
    fun everySlotNoteHasAKeyThatReadsBackAsTheNote() = runDesktopComposeUiTest {
        val notes = ProgramCatalogue.builtIn.flatMap { p -> p.days.flatMap { d -> d.slots.mapNotNull { it.note } } }.toSet()
        for (note in notes) {
            val key = assertNotNull(SlotNoteKeys.keys[note], "no key for \"$note\"")
            assertNotNull(Res.allStringResources[key], "no resource $key")
        }
        val shown = mutableMapOf<String, String>()
        setContent { for (note in notes) shown[note] = slotNoteText(note) }
        waitForIdle()
        for (note in notes) assertEquals(note, shown[note])
    }
}
