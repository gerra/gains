package app.gains.program

import app.gains.analysis.Format
import app.gains.analysis.TestData
import app.gains.analysis.TrainingSnapshot
import app.gains.catalogue.ProgramCatalogue
import app.gains.domain.ExerciseEntry
import app.gains.domain.ExerciseSlot
import app.gains.domain.ProgramDayRef
import app.gains.domain.ProgressionRule
import app.gains.domain.RepTarget
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import app.gains.domain.SetsReps
import app.gains.domain.WeightUnit
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressionTest {
    private val bench = TestData.bench
    private val kg = WeightUnit.KG
    private fun entry(weight: Double, vararg reps: Int, warmup: Boolean = false) =
        ExerciseEntry(bench.id, reps.mapIndexed { i, r -> TestData.weighted(weight, r, i, warmup) })

    @Test
    fun noHistoryPrefillsTargetsOnly() {
        val s = Progression.suggest(ExerciseSlot("bench_press", 3, RepTarget.Amrap(5), progression = ProgressionRule.Linear(2.5)), bench, null, kg)
        assertEquals(Progression.Suggestion(null, 3, 5, null, SetsReps(3, RepTarget.Amrap(5))), s)
    }

    @Test
    fun linearHitAddsStep() {
        val slot = ExerciseSlot("bench_press", 3, RepTarget.Amrap(5), progression = ProgressionRule.Linear(2.5))
        val s = Progression.suggest(slot, bench, entry(60.0, 5, 5, 6), kg)
        assertEquals(62.5, s.weightKg)
        assertEquals("Last: ${Format.weight(60.0, kg)} × 5,5,6 → try ${Format.weight(62.5, kg)}", s.hint)
    }

    @Test
    fun linearMissRepeats() {
        val slot = ExerciseSlot("bench_press", 3, RepTarget.Fixed(5), progression = ProgressionRule.Linear(2.5))
        val s = Progression.suggest(slot, bench, entry(60.0, 5, 5, 4), kg)
        assertEquals(60.0, s.weightKg)
        assertEquals("Last: ${Format.weight(60.0, kg)} × 5,5,4 → repeat ${Format.weight(60.0, kg)}", s.hint)
    }

    @Test
    fun linearInLbsUsesLbsStep() {
        val slot = ExerciseSlot("bench_press", 3, RepTarget.Fixed(5), progression = ProgressionRule.Linear(2.5, 5.0))
        val lastKg = app.gains.domain.Units.lbsToKg(135.0)
        val s = Progression.suggest(slot, bench, entry(lastKg, 5, 5, 5), WeightUnit.LBS)
        assertEquals(140.0, app.gains.domain.Units.kgToLbs(s.weightKg!!), 0.6)
    }

    @Test
    fun warmupSetsAreIgnored() {
        val slot = ExerciseSlot("bench_press", 3, RepTarget.Fixed(5), progression = ProgressionRule.Linear(2.5))
        val sets = listOf(TestData.weighted(40.0, 8, 0, warmup = true)) + (1..3).map { TestData.weighted(60.0, 5, it) }
        val s = Progression.suggest(slot, bench, ExerciseEntry(bench.id, sets), kg)
        assertEquals(62.5, s.weightKg)
    }

    @Test
    fun doubleProgressionClimbsRepsThenWeight() {
        val slot = ExerciseSlot("bench_press", 3, RepTarget.Range(8, 12), progression = ProgressionRule.DoubleProgression(8, 12, 2.5))
        val mid = Progression.suggest(slot, bench, entry(40.0, 10, 9, 8), kg)
        assertEquals(40.0, mid.weightKg)
        assertEquals(9, mid.reps)
        val top = Progression.suggest(slot, bench, entry(40.0, 12, 12, 12), kg)
        assertEquals(42.5, top.weightKg)
        assertEquals(8, top.reps)
    }

    @Test
    fun bodyweightDoubleProgressionNeverSuggestsWeight() {
        val pullUp = TestData.pullUp
        val slot = ExerciseSlot(pullUp.id, 3, RepTarget.Range(5, 8), progression = ProgressionRule.DoubleProgression(5, 8, 0.0))
        val sets = (0..2).map { SetEntry(it, SetType.BODYWEIGHT, reps = 8) }
        val s = Progression.suggest(slot, pullUp, ExerciseEntry(pullUp.id, sets), kg)
        assertNull(s.weightKg)
        assertEquals("Last: 8,8,8 → all sets at 8: move to the next progression", s.hint)
    }

    private val t1 = ProgressionRule.StageLadder(listOf(SetsReps(5, RepTarget.Amrap(3)), SetsReps(6, RepTarget.Amrap(2)), SetsReps(10, RepTarget.Amrap(1))), 5.0)
    private val t1Slot = ExerciseSlot("bench_press", 5, RepTarget.Amrap(3), progression = t1)

    @Test
    fun ladderSuccessAddsStepOnSameStage() {
        val s = Progression.suggest(t1Slot, bench, entry(60.0, 3, 3, 3, 3, 5), kg)
        assertEquals(65.0, s.weightKg)
        assertEquals(5, s.sets)
        assertEquals(3, s.reps)
    }

    @Test
    fun ladderFailureMovesToNextStageAtSameWeight() {
        val s = Progression.suggest(t1Slot, bench, entry(60.0, 3, 3, 3, 2, 2), kg)
        assertEquals(60.0, s.weightKg)
        assertEquals(6, s.sets)
        assertEquals(2, s.reps)
        assertEquals(SetsReps(6, RepTarget.Amrap(2)), s.target)
        assertEquals("Last: ${Format.weight(60.0, kg)} × 3,3,3,2,2 → missed reps: 6×2+ at ${Format.weight(60.0, kg)}", s.hint)
    }

    private val gzclp = ProgramCatalogue.byId("gzclp")!!
    private val a1 = gzclp.days.first { it.name == "A1" }
    /** A squat session, logged against the GZCLP day named [day] or free when null. */
    private fun squats(date: LocalDate, weight: Double, vararg reps: Int, day: String? = null) =
        TestData.session(date, TestData.entry(TestData.squat, *reps.mapIndexed { i, r -> TestData.weighted(weight, r, i) }.toTypedArray()))
            .copy(program = day?.let { name -> ProgramDayRef(gzclp.id, gzclp.days.first { it.name == name }.id) })
    private fun planSquat(vararg history: app.gains.domain.Session) =
        DayPlanner.plan(gzclp, a1, TrainingSnapshot(history.toList(), TestData.exercises), kg).exercises.first { it.exercise.id == "squat" }

    @Test
    fun plannerLabelsTheStageTheSetsWereBuiltFor() {
        // Missed the 5×3+ stage on this day last time: today is 6×2+ at the same weight, and the label must say so.
        val squat = planSquat(squats(LocalDate(2026, 3, 1), 80.0, 3, 3, 3, 2, 2, day = "A1"))
        assertEquals(6, squat.sets.size)
        assertEquals(2, squat.sets.first().reps)
        assertEquals(80.0, squat.sets.first().weightKg)
        assertEquals("6 × 2+", squat.targetLabel)
    }

    @Test
    fun freeSessionsSeedTheWeightButNotTheStage() {
        // Three sets of eight in a free session is not a failed 5×3+: start the ladder at its first stage.
        val squat = planSquat(squats(LocalDate(2026, 3, 1), 80.0, 8, 8, 8))
        assertEquals(5, squat.sets.size)
        assertEquals(3, squat.sets.first().reps)
        assertEquals(80.0, squat.sets.first().weightKg)
        assertEquals("5 × 3+", squat.targetLabel)
        assertEquals("Last: ${Format.weight(80.0, kg)} × 8,8,8 (different scheme) → start 5×3+ at ${Format.weight(80.0, kg)}", squat.hint)
    }

    @Test
    fun anotherSlotOfTheSameProgramDoesNotDriveTheLadder() {
        // A1 squat 5×3+ at 100, then A2's T2 squat 3×10 at 70. The next A1 continues from the T1 session, not the T2 one.
        val squat = planSquat(
            squats(LocalDate(2026, 3, 1), 100.0, 3, 3, 3, 3, 4, day = "A1"),
            squats(LocalDate(2026, 3, 5), 70.0, 10, 10, 10, day = "A2"),
        )
        assertEquals(5, squat.sets.size)
        assertEquals(105.0, squat.sets.first().weightKg)
        assertEquals("5 × 3+", squat.targetLabel)
    }

    @Test
    fun sameSchemeOnAnotherDayCountsTowardsProgression() {
        val ppl = ProgramCatalogue.byId("ppl_6")!!
        val legsA = ppl.days.first { it.name == "Legs A" }
        val legsB = ppl.days.first { it.name == "Legs B" }
        assertTrue(legsA.slots.first { it.exerciseId == "squat" }.sameScheme(legsB.slots.first { it.exerciseId == "squat" }))
        val history = listOf(
            TestData.session(LocalDate(2026, 3, 1), TestData.entry(TestData.squat, *(0..2).map { TestData.weighted(100.0, 5, it) }.toTypedArray()))
                .copy(program = ProgramDayRef(ppl.id, legsB.id)),
        )
        val squat = DayPlanner.plan(ppl, legsA, TrainingSnapshot(history, TestData.exercises), kg).exercises.first { it.exercise.id == "squat" }
        assertEquals(102.5, squat.sets.first().weightKg)
    }

    @Test
    fun ladderFailureOnLastStageResets() {
        val s = Progression.suggest(t1Slot, bench, entry(100.0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0), kg)
        assertEquals(90.0, s.weightKg)
        assertEquals(5, s.sets)
        assertEquals(3, s.reps)
    }

    @Test
    fun stageInferenceUsesFirstSetRepsWhenSetCountsTie() {
        val t2 = listOf(SetsReps(3, RepTarget.Fixed(10)), SetsReps(3, RepTarget.Fixed(8)), SetsReps(3, RepTarget.Fixed(6)))
        assertEquals(1, Progression.inferStage(t2, 3, 8))
        assertEquals(2, Progression.inferStage(t2, 3, 6))
        assertEquals(0, Progression.inferStage(t2, 4, 6))
    }

    @Test
    fun plannerBuildsSetsFromHistoryAndSkipsUnknownExercises() {
        val history = listOf(
            squats(LocalDate(2026, 3, 1), 80.0, 3, 3, 3, 3, 3, day = "A1"),
            squats(LocalDate(2026, 3, 3), 85.0, 3, 3, 3, 3, 3, day = "A1"),
        )
        val snapshot = TrainingSnapshot(history, TestData.exercises.filter { it.id != "lat_pulldown" })
        val plan = DayPlanner.plan(gzclp, a1, snapshot, kg)
        assertEquals(listOf("squat", "bench_press"), plan.exercises.map { it.exercise.id })
        val squat = plan.exercises.first()
        assertEquals(5, squat.sets.size)
        assertEquals(90.0, squat.sets.first().weightKg)
        assertEquals(3, squat.sets.first().reps)
        assertEquals("5 × 3+", squat.targetLabel)
        val benchPlan = plan.exercises[1]
        assertNull(benchPlan.sets.first().weightKg)
        assertEquals(10, benchPlan.sets.first().reps)
        assertNull(benchPlan.hint)
    }

    @Test
    fun plannerPrefillsSecondsForIsometrics() {
        val program = ProgramCatalogue.byId("upper_lower_4")!!
        val day = program.days.first { it.name == "Lower A" }
        val plan = DayPlanner.plan(program, day, TrainingSnapshot(emptyList(), TestData.exercises), kg)
        val plank = plan.exercises.first { it.exercise.id == "plank" }
        assertEquals(45, plank.sets.first().seconds)
        assertNull(plank.sets.first().reps)
    }

    @Test
    fun describesEveryRuleInTheDisplayUnit() {
        assertNull(Progression.describe(ProgressionRule.None, kg))
        assertEquals(
            "Add 2.5 kg every session you hit every set. Miss the reps and the weight repeats.",
            Progression.describe(ProgressionRule.Linear(2.5), kg),
        )
        assertEquals(
            "Add 5 lbs every session you hit every set. Miss the reps and the weight repeats.",
            Progression.describe(ProgressionRule.Linear(2.5), WeightUnit.LBS),
        )
        assertEquals(
            "Reps climb from 15 to 25 at one weight. Once every set reaches 25, add 2.5 kg and drop back to 15.",
            Progression.describe(ProgressionRule.DoubleProgression(15, 25, 2.5), kg),
        )
        assertEquals(
            "Reps climb from 5 to 8 at one weight. Once every set reaches 8, move on to the harder variation.",
            Progression.describe(ProgressionRule.DoubleProgression(5, 8, 0.0), kg),
        )
        val ladder = ProgressionRule.StageLadder(listOf(SetsReps(5, RepTarget.Amrap(3)), SetsReps(6, RepTarget.Amrap(2)), SetsReps(10, RepTarget.Amrap(1))), 5.0)
        assertEquals(
            "Stages: 5×3+ → 6×2+ → 10×1+. Hit the reps: add 5 kg and stay on the stage. Miss: next stage at the same weight. Miss the last stage: drop about 10% and start over at 5×3+.",
            Progression.describe(ladder, kg),
        )
    }
}
