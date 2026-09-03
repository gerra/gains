package app.gains.catalogue

import app.gains.domain.Equipment
import app.gains.domain.GoalProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgramCatalogueTest {
    @Test
    fun everySlotResolvesToACatalogueExercise() {
        for (p in ProgramCatalogue.builtIn) for (d in p.days) for (s in d.slots) {
            assertTrue(ExerciseCatalogue.byId(s.exerciseId) != null, "${p.id}/${d.name}: unknown exercise ${s.exerciseId}")
        }
    }

    @Test
    fun programsAreWellFormed() {
        val ids = ProgramCatalogue.builtIn.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "program ids must be unique")
        for (p in ProgramCatalogue.builtIn) {
            assertTrue(p.isBuiltIn)
            assertTrue(p.days.size >= 2, "${p.id} needs at least two days")
            assertTrue(p.daysPerWeek in GoalProfile.MIN_DAYS..GoalProfile.MAX_DAYS, "${p.id} daysPerWeek")
            assertTrue(p.goals.isNotEmpty())
            val dayIds = p.days.map { it.id }
            assertEquals(dayIds.size, dayIds.toSet().size, "${p.id} day ids must be unique")
            for (d in p.days) {
                assertTrue(d.slots.isNotEmpty(), "${p.id}/${d.name} has no exercises")
                val ex = d.slots.map { it.exerciseId }
                assertEquals(ex.size, ex.toSet().size, "${p.id}/${d.name} lists an exercise twice")
                for (s in d.slots) assertTrue(s.sets in 1..10, "${p.id}/${d.name}/${s.exerciseId} sets")
            }
        }
    }

    @Test
    fun pplVariantsShareDays() {
        val six = ProgramCatalogue.byId("ppl_6")!!
        val three = ProgramCatalogue.byId("ppl_3")!!
        assertEquals(six.days, three.days)
    }

    @Test
    fun fiveThreeOneSlotsCarryTheWaveInNotes() {
        val p = ProgramCatalogue.byId("531_beginners")!!
        for (d in p.days) for (s in d.slots) assertTrue(!s.note.isNullOrBlank(), "${d.name}/${s.exerciseId} needs a note")
    }

    @Test
    fun catalogueHasEquipmentAndUniqueIds() {
        val ids = ExerciseCatalogue.builtIn.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "exercise ids must be unique")
        for (e in ExerciseCatalogue.builtIn) {
            assertTrue(e.equipment.isNotEmpty(), "${e.id} has no equipment")
        }
        assertEquals(setOf(Equipment.BARBELL), ExerciseCatalogue.byId("bench_press")!!.equipment)
        assertEquals(setOf(Equipment.CABLE), ExerciseCatalogue.byId("lat_pulldown")!!.equipment)
        assertEquals(setOf(Equipment.DUMBBELL), ExerciseCatalogue.byId("db_row")!!.equipment)
    }
}
