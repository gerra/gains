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

    @Test
    fun cycleIsTheCurrentOneWithTheNextDayInItsPlace() {
        // Four days at three a week: four weeks until A1 opens a week again. One session in, B1 is second.
        val weeks = Rotation.cycle(gzclp, listOf(link("gzclp", "gzclp/a1", 1)))
        assertEquals(4, weeks.size)
        assertEquals(listOf("A1", "B1", "A2"), weeks[0].map { it.name })
        assertEquals(listOf("B2", "A1", "B1"), weeks[1].map { it.name })
        assertEquals(listOf("A2", "B2", "A1"), weeks[2].map { it.name })
        assertEquals(listOf("B1", "A2", "B2"), weeks[3].map { it.name })
    }

    @Test
    fun cycleFollowsADayStartedOutOfTurn() {
        // A1 then A2: B2 is next, in the third slot of week 1.
        val weeks = Rotation.cycle(gzclp, listOf(link("gzclp", "gzclp/a1", 1), link("gzclp", "gzclp/a2", 3)))
        assertEquals("B2", weeks[0][2].name)
        assertEquals(listOf("B1", "A2", "B2"), weeks[0].map { it.name })
    }

    @Test
    fun positionCountsSessionsNotCalendarWeeks() {
        assertEquals(Rotation.Position(done = 0, cycle = 1, week = 1, weeks = 4, index = 0), Rotation.position(gzclp, emptyList()))
        // Three sessions a month apart are still one week's worth.
        val three = listOf(link("gzclp", "gzclp/a1", 1), link("gzclp", "gzclp/b1", 30), link("gzclp", "gzclp/a2", 31))
        assertEquals(Rotation.Position(done = 3, cycle = 1, week = 2, weeks = 4, index = 3), Rotation.position(gzclp, three))
        // Twelve make a whole cycle: the thirteenth is week 1 of the second.
        val twelve = List(12) { link("gzclp", gzclp.days[it % 4].id, it + 1) }
        assertEquals(Rotation.Position(done = 12, cycle = 2, week = 1, weeks = 4, index = 0), Rotation.position(gzclp, twelve))
        assertEquals(Rotation.Position(done = 0, cycle = 1, week = 1, weeks = 4, index = 0), Rotation.position(gzclp, listOf(link("ppl_6", "ppl_6/pull_a", 1))))
        assertEquals(null, Rotation.position(gzclp.copy(days = emptyList()), emptyList()))
    }

    @Test
    fun aRunStartsWhereTheProgramWasLastStartedOver() {
        val links = listOf(link("gzclp", "gzclp/a1", 1), link("gzclp", "gzclp/b1", 3), link("ppl_6", "ppl_6/pull_a", 4), link("gzclp", "gzclp/a1", 10))
        assertEquals(3, Rotation.run(gzclp, links, null).size)
        val run = Rotation.run(gzclp, links, LocalDateTime(LocalDate(2026, 3, 5), LocalTime(9, 0)))
        assertEquals(listOf("10-18"), run.map { it.sessionId })
        assertEquals("gzclp/b1", Rotation.nextDay(gzclp, run)!!.id)
        assertEquals(1, Rotation.position(gzclp, run)!!.done)
        // Started over and nothing done since: day one of week one.
        val fresh = Rotation.run(gzclp, links, LocalDateTime(LocalDate(2026, 3, 20), LocalTime(9, 0)))
        assertEquals("gzclp/a1", Rotation.nextDay(gzclp, fresh)!!.id)
        assertEquals(1, Rotation.position(gzclp, fresh)!!.week)
    }

    @Test
    fun cycleIsOneWeekWhenDaysMatchTheWeek() {
        val six = ProgramCatalogue.byId("ppl_6")!!
        val weeks = Rotation.cycle(six, emptyList())
        assertEquals(1, weeks.size)
        assertEquals(six.days.map { it.id }, weeks.single().map { it.id })
        assertEquals(emptyList(), Rotation.cycle(gzclp.copy(days = emptyList()), emptyList()))
    }
}
