package app.gains.program

import app.gains.catalogue.ProgramCatalogue
import app.gains.domain.ProgramDayRef
import app.gains.domain.ProgramLink
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class RotationTest {
    private val gzclp = ProgramCatalogue.byId("gzclp")!!
    private fun link(programId: String, dayId: String, day: Int, hour: Int = 18) =
        ProgramLink("$day-$hour", LocalDateTime(LocalDate(2026, 3, day), LocalTime(hour, 0)), ProgramDayRef(programId, dayId))

    @Test
    fun startsAtFirstDay() {
        assertEquals("gzclp/a1", Rotation.nextDay(gzclp, emptyList())!!.id)
    }

    @Test
    fun advancesAndWraps() {
        assertEquals("gzclp/b1", Rotation.nextDay(gzclp, listOf(link("gzclp", "gzclp/a1", 1)))!!.id)
        val all = listOf(link("gzclp", "gzclp/a1", 1), link("gzclp", "gzclp/b1", 3), link("gzclp", "gzclp/a2", 5), link("gzclp", "gzclp/b2", 7))
        assertEquals("gzclp/a1", Rotation.nextDay(gzclp, all)!!.id)
    }

    @Test
    fun ordersByTimestampNotListOrder() {
        val links = listOf(link("gzclp", "gzclp/b2", 9), link("gzclp", "gzclp/a1", 2))
        assertEquals("gzclp/a1", Rotation.nextDay(gzclp, links)!!.id)
    }

    @Test
    fun ignoresOtherProgramsAndUnknownDays() {
        assertEquals("gzclp/a1", Rotation.nextDay(gzclp, listOf(link("ppl_6", "ppl_6/pull_a", 1)))!!.id)
        assertEquals("gzclp/a1", Rotation.nextDay(gzclp, listOf(link("gzclp", "gzclp/deleted", 1)))!!.id)
    }

    @Test
    fun pplVariantsRotateIndependently() {
        val six = ProgramCatalogue.byId("ppl_6")!!
        val three = ProgramCatalogue.byId("ppl_3")!!
        val links = listOf(link("ppl_6", "ppl_6/pull_a", 1), link("ppl_6", "ppl_6/push_a", 2))
        assertEquals("ppl_6/legs_a", Rotation.nextDay(six, links)!!.id)
        assertEquals("ppl_6/pull_a", Rotation.nextDay(three, links)!!.id)
    }

    @Test
    fun lastCompletedByDay() {
        val links = listOf(link("gzclp", "gzclp/a1", 1), link("gzclp", "gzclp/a1", 8), link("gzclp", "gzclp/b1", 3))
        val last = Rotation.lastCompletedByDay(gzclp, links)
        assertEquals(LocalDate(2026, 3, 8), last["gzclp/a1"])
        assertEquals(LocalDate(2026, 3, 3), last["gzclp/b1"])
        assertEquals(null, last["gzclp/a2"])
        assertEquals(2, Rotation.completedSince(gzclp, links, LocalDate(2026, 3, 3)))
    }
}
