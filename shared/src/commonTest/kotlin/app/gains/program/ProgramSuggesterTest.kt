package app.gains.program

import app.gains.catalogue.ProgramCatalogue
import app.gains.domain.Experience
import app.gains.domain.Goal
import app.gains.domain.GoalProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgramSuggesterTest {
    private val all = ProgramCatalogue.builtIn

    @Test
    fun beginnerStrengthThreeDays() {
        val top = ProgramSuggester.suggest(GoalProfile(Goal.GET_STRONGER, Experience.BEGINNER, 3), all).first()
        assertTrue(top.id in setOf("rfitness_basic", "gzclp"), top.id)
    }

    @Test
    fun intermediateMuscleSixDays() {
        assertEquals("ppl_6", ProgramSuggester.suggest(GoalProfile(Goal.BUILD_MUSCLE, Experience.INTERMEDIATE, 6), all).first().id)
    }

    @Test
    fun fourDaysPrefersUpperLower() {
        assertEquals("upper_lower_4", ProgramSuggester.suggest(GoalProfile(Goal.BUILD_MUSCLE, Experience.INTERMEDIATE, 4), all).first().id)
    }

    @Test
    fun onlyBuiltInsAreSuggested() {
        val custom = all.first().copy(id = "custom_1", isBuiltIn = false)
        val out = ProgramSuggester.suggest(GoalProfile(Goal.GENERAL_FITNESS, Experience.BEGINNER, 3), all + custom)
        assertTrue(out.none { it.id == "custom_1" })
        assertEquals(all.size, out.size)
    }

    @Test
    fun aNewcomerSeesTwoProgramsAndTheRestFolded() {
        val list = ProgramSuggester.shortlist(GoalProfile(Goal.GENERAL_FITNESS, Experience.BEGINNER, 3), all)
        assertEquals(ProgramSuggester.SUGGESTED, list.suggested.size)
        assertEquals("full_body_basics", list.suggested.first().id)
        // The heavy and many-day programs wait under "More", in order of fit, none lost.
        assertTrue(list.more.any { it.id == "ppl_6" } && list.more.any { it.id == "531_beginners" })
        assertEquals(all.map { it.id }.toSet(), (list.suggested + list.more).map { it.id }.toSet())
    }

    @Test
    fun theActiveProgramIsNeverFoldedAway() {
        val list = ProgramSuggester.shortlist(GoalProfile(Goal.GENERAL_FITNESS, Experience.BEGINNER, 3), all, keep = "ppl_6")
        assertTrue(list.suggested.any { it.id == "ppl_6" })
        assertTrue(list.more.none { it.id == "ppl_6" })
    }

    @Test
    fun anExperiencedLifterSeesTheirOwnKindFirst() {
        val list = ProgramSuggester.shortlist(GoalProfile(Goal.BUILD_MUSCLE, Experience.INTERMEDIATE, 6), all)
        assertEquals("ppl_6", list.suggested.first().id)
        assertTrue(list.more.any { it.id == "full_body_basics" })
    }
}
