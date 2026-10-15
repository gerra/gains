package app.gains.program

import app.gains.catalogue.ExerciseCatalogue
import app.gains.catalogue.ProgramCatalogue
import app.gains.domain.Caution
import app.gains.domain.Experience
import app.gains.domain.FocusArea
import app.gains.domain.Goal
import app.gains.domain.GoalProfile
import app.gains.domain.ProgressionRule
import app.gains.domain.RepTarget
import app.gains.domain.SlotReason
import app.gains.domain.SwapWhy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TailorTest {
    private fun program(id: String) = ProgramCatalogue.byId(id)!!
    private fun ids(day: app.gains.domain.ProgramDay) = day.slots.map { it.exerciseId }

    @Test
    fun everyNamedExerciseIsInTheCatalogue() {
        for (id in Tailor.namedExercises) assertTrue(ExerciseCatalogue.byId(id) != null, id)
    }

    @Test
    fun nothingPickedLeavesTheProgramAsWritten() {
        val gzclp = program("gzclp")
        assertSame(gzclp, Tailor.tailor(gzclp, emptySet(), emptySet()))
    }

    @Test
    fun customProgramsAreNeverChanged() {
        val custom = program("gzclp").copy(id = "custom_1", isBuiltIn = false)
        assertSame(custom, Tailor.tailor(custom, setOf(FocusArea.GLUTES), setOf(Caution.KNEES)))
    }

    @Test
    fun focusAddsOneExercisePerAreaToEveryWorkout() {
        val tailored = Tailor.tailor(program("full_body_basics"), setOf(FocusArea.GLUTES, FocusArea.CORE), emptySet())
        for ((before, after) in program("full_body_basics").days.zip(tailored.days)) {
            val extras = after.slots.drop(before.slots.size)
            assertEquals(before.slots, after.slots.take(before.slots.size))
            assertEquals(listOf(FocusArea.GLUTES, FocusArea.CORE), extras.map { (it.reason as SlotReason.Focus).area })
            // Never twice in a day: Workout B already has hip thrusts and a dead bug.
            assertEquals(after.slots.size, ids(after).toSet().size)
        }
        assertEquals("hip_thrust", tailored.days[0].slots[5].exerciseId)
        assertEquals("hip_abduction", tailored.days[1].slots[5].exerciseId)
    }

    @Test
    fun aBodyweightProgramGetsBodyweightExtras() {
        val tailored = Tailor.tailor(program("bwf_rr"), setOf(FocusArea.GLUTES), emptySet())
        assertEquals("bodyweight_glute_bridge", tailored.days[0].slots.last().exerciseId)
    }

    @Test
    fun kneesSwapTheSquatForABoxSquatAndKeepItsScheme() {
        val original = program("gzclp").days[0].slots[0]
        val swapped = Tailor.tailor(program("gzclp"), emptySet(), setOf(Caution.KNEES)).days[0].slots[0]
        assertEquals("box_squat", swapped.exerciseId)
        assertEquals(SlotReason.Swapped("squat", Caution.KNEES, SwapWhy.GENTLER), swapped.reason)
        assertEquals(original.copy(exerciseId = "box_squat", reason = swapped.reason), swapped)
    }

    @Test
    fun aSwapSuitsEveryCaution() {
        // A box squat is easier on knees but still loads the back: both together give a goblet squat.
        val slot = Tailor.tailor(program("gzclp"), emptySet(), setOf(Caution.KNEES, Caution.LOWER_BACK)).days[0].slots[0]
        assertEquals("goblet_squat", slot.exerciseId)
    }

    @Test
    fun lowerBackSwapsTheBentOverRowAndTheDeadlift() {
        val tailored = Tailor.tailor(program("rfitness_basic"), emptySet(), setOf(Caution.LOWER_BACK))
        assertEquals(listOf("chest_supported_dumbbell_row", "bench_press", "goblet_squat"), ids(tailored.days[0]))
        assertEquals("trap_bar_deadlift", tailored.days[1].slots[2].exerciseId)
    }

    @Test
    fun keptExercisesCarryACue() {
        val tailored = Tailor.tailor(program("full_body_basics"), emptySet(), setOf(Caution.KNEES))
        assertEquals("goblet_squat", tailored.days[0].slots[0].exerciseId)
        assertEquals(SlotReason.Careful(Caution.KNEES), tailored.days[0].slots[0].reason)
        assertNull(tailored.days[0].slots[1].reason)
    }

    @Test
    fun focusNeverAddsWhatACautionSwapsOut() {
        val tailored = Tailor.tailor(program("full_body_basics"), setOf(FocusArea.SHOULDERS), setOf(Caution.SHOULDERS))
        // Workout B's seated press goes; the lateral raise added for shoulders carries the shoulder cue.
        assertEquals("landmine_press", tailored.days[1].slots[2].exerciseId)
        val extra = tailored.days[0].slots.last()
        assertEquals("lateral_raise", extra.exerciseId)
        assertEquals(SlotReason.Focus(FocusArea.SHOULDERS, Caution.SHOULDERS), extra.reason)
    }

    @Test
    fun pregnancySwapsCrunchesAndLyingPresses() {
        val tailored = Tailor.tailor(program("531_beginners"), emptySet(), setOf(Caution.PREGNANCY))
        val day1 = ids(tailored.days[0])
        assertTrue("bench_press" !in day1 && "db_incline_bench_press" in day1, day1.toString())
        assertTrue("ab_wheel" !in ids(tailored.days[2]))
    }

    @Test
    fun bloodPressureChangesNoExerciseAndMakesTheHardSetsModerate() {
        val before = program("gzclp")
        val after = Tailor.tailor(before, emptySet(), setOf(Caution.BLOOD_PRESSURE))
        assertEquals(before.days.map(::ids), after.days.map(::ids))
        // The T1 5 × 3+ becomes 3 × 8–12, adding the T1's own step once every set reaches 12.
        val t1 = after.days[0].slots[0]
        assertEquals(SlotReason.Careful(Caution.BLOOD_PRESSURE), t1.reason)
        assertEquals(3, t1.sets)
        assertEquals(RepTarget.Range(8, 12), t1.reps)
        assertEquals(ProgressionRule.DoubleProgression(8, 12, 5.0, 10.0), t1.progression)
        // The T2 3 × 10 stays, its ladder's later stages (3 × 8, 3 × 6) included: not "as many as possible".
        assertEquals(before.days[0].slots[1].copy(), after.days[0].slots[1])
        // 3 × 15+ becomes 3 × 15, and says why.
        assertEquals(RepTarget.Fixed(15), after.days[0].slots[2].reps)
        assertEquals(SlotReason.Careful(Caution.BLOOD_PRESSURE), after.days[0].slots[2].reason)
    }

    @Test
    fun aSwappedHeavyLiftCarriesBothReasons() {
        val squat = Tailor.tailor(program("gzclp"), emptySet(), setOf(Caution.KNEES, Caution.PREGNANCY)).days[0].slots[0]
        assertEquals("box_squat", squat.exerciseId)
        assertEquals(SlotReason.Swapped("squat", Caution.KNEES, SwapWhy.GENTLER, also = Caution.PREGNANCY), squat.reason)
        assertEquals(RepTarget.Range(8, 12), squat.reps)
    }

    @Test
    fun pregnancySaysWhatEachSwapAvoids() {
        val day = Tailor.tailor(program("531_beginners"), emptySet(), setOf(Caution.PREGNANCY)).days[0]
        val bench = day.slots.first { it.exerciseId == "db_incline_bench_press" }
        assertEquals(SlotReason.Swapped("bench_press", Caution.PREGNANCY, SwapWhy.LYING_FLAT), bench.reason)
        val core = day.slots.first { (it.reason as? SlotReason.Swapped)?.fromExerciseId == "leg_raise" }
        assertEquals(SwapWhy.BELLY, (core.reason as SlotReason.Swapped).why)
    }

    @Test
    fun afterABabyLyingFlatIsFineButCrunchesWait() {
        val day = Tailor.tailor(program("531_beginners"), emptySet(), setOf(Caution.POSTPARTUM)).days[0]
        assertTrue("bench_press" in ids(day))
        assertTrue("leg_raise" !in ids(day) && "dead_bug" in ids(day), ids(day).toString())
    }

    @Test
    fun heavyProgramsGoLastForPregnancyAndBloodPressure() {
        val profile = GoalProfile(Goal.GET_STRONGER, Experience.BEGINNER, 3)
        val ranked = ProgramSuggester.suggest(profile, ProgramCatalogue.builtIn, setOf(Caution.BLOOD_PRESSURE))
        assertTrue(!Tailor.isHeavy(ranked.first()), ranked.first().id)
        assertTrue(Tailor.isHeavy(program("gzclp")))
        assertTrue(!Tailor.isHeavy(program("full_body_basics")))
    }

    @Test
    fun theSameAnswersGiveTheSameDays() {
        val a = Tailor.tailor(program("ppl_6"), setOf(FocusArea.ARMS, FocusArea.CHEST), setOf(Caution.WRISTS))
        val b = Tailor.tailor(program("ppl_6"), setOf(FocusArea.CHEST, FocusArea.ARMS), setOf(Caution.WRISTS))
        assertEquals(a, b)
        assertNotEquals(program("ppl_6"), a)
    }

    @Test
    fun focusAreasAreCappedAndSurviveUnknownNames() {
        assertEquals(setOf(FocusArea.GLUTES, FocusArea.CORE), FocusArea.decode("GLUTES,CORE,ARMS"))
        assertEquals(setOf(FocusArea.CORE), FocusArea.decode("NECK,CORE"))
        assertEquals(emptySet(), FocusArea.decode(null))
        assertEquals("GLUTES,CORE", FocusArea.encode(setOf(FocusArea.CORE, FocusArea.GLUTES)))
        assertEquals(setOf(Caution.KNEES, Caution.PREGNANCY), Caution.decode(Caution.encode(setOf(Caution.PREGNANCY, Caution.KNEES))))
    }

    @Test
    fun aNewcomerIsSuggestedFullBodyBasics() {
        for (goal in listOf(Goal.GENERAL_FITNESS, Goal.LOSE_FAT, Goal.BUILD_MUSCLE)) for (days in 2..3) {
            assertEquals("full_body_basics", ProgramSuggester.suggest(GoalProfile(goal, Experience.BEGINNER, days), ProgramCatalogue.builtIn).first().id, "$goal $days")
        }
    }

    @Test
    fun anEstimateShowsWhatTheExtrasCost() {
        val plain = program("full_body_basics").days[0]
        val fitted = Tailor.tailor(program("full_body_basics"), setOf(FocusArea.GLUTES, FocusArea.CORE), emptySet()).days[0]
        assertEquals(35, DayEstimate.minutes(plain))
        assertEquals(45, DayEstimate.minutes(fitted))
    }
}
