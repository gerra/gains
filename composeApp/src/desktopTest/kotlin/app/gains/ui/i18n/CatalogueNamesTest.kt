package app.gains.ui.i18n

import app.gains.catalogue.ExerciseCatalogue
import app.gains.catalogue.ProgramCatalogue
import app.gains.resources.Res
import app.gains.resources.allStringResources
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The catalogues' English names come back from the resources unchanged (the test JVM runs in English). */
@OptIn(ExperimentalResourceApi::class)
class CatalogueNamesTest {
    @Test
    fun builtInExercisesProgramsAndDaysResolveToTheirCatalogueNames() = runBlocking {
        for (exercise in ExerciseCatalogue.builtIn) assertEquals(exercise.name, exercise.resolvedName())
        for (program in ProgramCatalogue.builtIn) {
            assertEquals(program.name, program.resolvedName())
            for (day in program.days) assertEquals(day.name, day.resolvedName())
        }
    }

    @Test
    fun everySlotNoteHasAKeyThatReadsBackAsTheNote() = runBlocking {
        val notes = ProgramCatalogue.builtIn.flatMap { p -> p.days.flatMap { d -> d.slots.mapNotNull { it.note } } }.toSet()
        for (note in notes) {
            val key = assertNotNull(SlotNoteKeys.keys[note], "no key for \"$note\"")
            val resource = assertNotNull(Res.allStringResources[key], "no resource $key")
            assertEquals(note, getString(resource))
        }
    }
}
