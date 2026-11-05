package app.gains.program

import app.gains.analysis.InsightEngine
import app.gains.analysis.InsightKind
import app.gains.analysis.TestData
import app.gains.analysis.TrainingSnapshot
import app.gains.catalogue.ProgramCatalogue
import app.gains.domain.ExerciseSlot
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.ProgramDayRef
import app.gains.domain.ProgressionRule
import app.gains.domain.RepTarget
import app.gains.domain.Session
import app.gains.domain.WeightUnit
import app.gains.program.Progression.Hint
import app.gains.program.Progression.LastTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComebackTest {
    private val kg = WeightUnit.KG
    private val bench = TestData.bench

    /** The last workout before the break, then 16 days off: back on 21 October. */
    private val lastBefore = LocalDate(2026, 10, 5)
    private val after = LocalDateTime(lastBefore, LocalTime(18, 0))
    private val back = LocalDate(2026, 10, 21)
    private val easing = Comeback.Answer(after, 16, back, easing = true)

    // ---- The offer ----------------------------------------------------------------------

    @Test
    fun aBreakOfTenDaysOrMoreIsAskedAboutOnce() {
        val sessions = listOf(TestData.session(lastBefore, TestData.entry(bench, TestData.weighted(60.0, 5))))
        assertEquals(Comeback.Gap(after, 16), Comeback.gap(sessions, back, emptyList()))
        assertNull(Comeback.gap(sessions, LocalDate(2026, 10, 14), emptyList()), "nine days is an ordinary week off")
        assertNull(Comeback.gap(sessions, back, listOf(easing)), "answered: not asked again")
        assertNull(Comeback.gap(sessions, back, listOf(easing.copy(easing = false))), "declined: not asked again either")
        assertNull(Comeback.gap(emptyList(), back, emptyList()))
    }

    @Test
    fun theEasingLastsSixWeeksFromTheAnswer() {
        assertEquals(easing, Comeback.active(listOf(easing), back))
        assertEquals(easing, Comeback.active(listOf(easing), LocalDate(2026, 12, 2)))
        assertNull(Comeback.active(listOf(easing), LocalDate(2026, 12, 3)))
        assertNull(Comeback.active(listOf(easing.copy(easing = false)), back))
        // Only the latest break counts: declining a later one ends an earlier easing.
        val later = Comeback.Answer(LocalDateTime(2026, 11, 1, 18, 0), 12, LocalDate(2026, 11, 13), easing = false)
        assertNull(Comeback.active(listOf(easing, later), LocalDate(2026, 11, 13)))
    }

    @Test
    fun answersRoundTripOnePerLineAndReplaceTheSameBreak() {
        val declined = Comeback.Answer(LocalDateTime(2026, 6, 1, 7, 30), 30, LocalDate(2026, 7, 1), easing = false)
        val raw = Comeback.encode(listOf(declined, easing))
        assertEquals(listOf(declined, easing), Comeback.decode(raw))
        assertEquals(listOf(easing), Comeback.decode("garbage\n" + easing.encode()))
        assertEquals(emptyList(), Comeback.decode(null))
        assertEquals(listOf(declined, easing.copy(easing = false)), Comeback.answering(listOf(declined, easing), easing.copy(easing = false)))
    }

    // ---- How much lighter -----------------------------------------------------------------

    @Test
    fun theRampStartsLowerTheLongerTheBreak() {
        assertEquals(listOf(0.9, 0.95, 1.0), (0..2).map { Comeback.fraction(16, it)!! })
        assertNull(Comeback.fraction(16, 3))
        assertEquals(0.85, Comeback.fraction(25, 0)!!, 1e-9)
        assertEquals(0.8, Comeback.fraction(40, 0)!!, 1e-9)
    }

    @Test
    fun theWeightRoundsDownToWhatCanBeLoaded() {
        assertEquals(52.5, Comeback.weight(60.0, 0.9, 2.5, kg))
        assertEquals(95.0, Comeback.weight(100.0, 0.95, 5.0, kg))
        assertEquals(62.5, Comeback.weight(62.5, 1.0, 2.5, kg))
        // Never below one plate's step, however light.
        assertEquals(2.5, Comeback.weight(2.5, 0.8, 2.5, kg))
        // 135 lb × 0.9 = 121.5 → 120 lb on 5 lb steps, stored to the quarter kilogram.
        assertEquals(120.0, app.gains.domain.Units.kgToLbs(Comeback.weight(app.gains.domain.Units.lbsToKg(135.0), 0.9, 5.0, WeightUnit.LBS)), 0.6)
    }

    // ---- A program day --------------------------------------------------------------------

    private val linearDay = ProgramDay("d", "Day", listOf(ExerciseSlot("bench_press", 3, RepTarget.Fixed(5), progression = ProgressionRule.Linear(2.5))))
    private val linear = Program("p", "Linear", "", emptySet(), app.gains.domain.Experience.BEGINNER, daysPerWeek = 3, days = listOf(linearDay), isBuiltIn = false)

    private fun benches(date: LocalDate, weight: Double, vararg reps: Int, program: Program = linear, day: ProgramDay = linearDay): Session =
        TestData.session(date, TestData.entry(bench, *reps.mapIndexed { i, r -> TestData.weighted(weight, r, i) }.toTypedArray()))
            .copy(program = ProgramDayRef(program.id, day.id))

    private fun planBench(history: List<Session>, program: Program = linear, day: ProgramDay = linearDay, easing: Comeback.Answer? = this.easing, comebacks: List<Comeback.Answer> = listOf(this.easing)) =
        DayPlanner.plan(program, day, TrainingSnapshot(history, TestData.exercises), kg, easing = easing, comebacks = comebacks)
            .exercises.first { it.exercise.id == "bench_press" }

    @Test
    fun aLinearLiftClimbsBackOverThreeSessionsThenProgresses() {
        val before = listOf(benches(LocalDate(2026, 10, 1), 57.5, 5, 5, 5), benches(lastBefore, 60.0, 5, 5, 5))
        // Without the easing the rule would try 62.5 kg. Back at 90% of the 60 kg lifted last: 54 → 52.5.
        val first = planBench(before)
        assertEquals(52.5, first.workSets.first().weightKg)
        assertEquals(3, first.workSets.size)
        assertEquals(Hint.EaseBack(LastTime(60.0, listOf(5, 5, 5)), 52.5, 60.0, 16), first.hint)

        val one = before + benches(back, 52.5, 5, 5, 5)
        assertEquals(55.0, planBench(one).workSets.first().weightKg)
        val two = one + benches(LocalDate(2026, 10, 23), 55.0, 5, 5, 5)
        val third = planBench(two)
        assertEquals(60.0, third.workSets.first().weightKg)
        assertEquals(Hint.EaseBack(LastTime(55.0, listOf(5, 5, 5)), 60.0, 60.0, 16), third.hint)

        // Back where it was: the rule takes over.
        val three = two + benches(LocalDate(2026, 10, 26), 60.0, 5, 5, 5)
        val after = planBench(three)
        assertEquals(62.5, after.workSets.first().weightKg)
        assertEquals(Hint.Try(LastTime(60.0, listOf(5, 5, 5)), 62.5), after.hint)
    }

    @Test
    fun withoutAnAnswerNothingEases() {
        val before = listOf(benches(lastBefore, 60.0, 5, 5, 5))
        assertEquals(62.5, planBench(before, easing = null, comebacks = emptyList()).workSets.first().weightKg)
    }

    @Test
    fun aLiftAlreadyBackToItsWeightIsNotHeldBack() {
        // Went straight back to 60 kg on the first day back: the ramp has nothing left to do.
        val history = listOf(benches(lastBefore, 60.0, 5, 5, 5), benches(back, 60.0, 5, 5, 5))
        assertEquals(62.5, planBench(history).workSets.first().weightKg)
    }

    @Test
    fun theWeightToComeBackToIsWhatWasLiftedNotTheNextStep() {
        // Missed reps at 60 before the break: the rule would repeat 60, so the ramp builds to 60.
        val history = listOf(benches(lastBefore, 60.0, 5, 5, 3))
        val first = planBench(history)
        assertEquals(52.5, first.workSets.first().weightKg)
        assertEquals(60.0, (first.hint as Hint.EaseBack).toKg)
    }

    private val gzclp = ProgramCatalogue.byId("gzclp")!!
    private val a1 = gzclp.days.first { it.name == "A1" }

    @Test
    fun aStageLadderKeepsItsStageThroughTheRampAndAfter() {
        // A1's T2 bench is 3 × 10, then 3 × 8, then 3 × 6. Hit 3 × 10 at 40 before the break.
        fun t2(date: LocalDate, weight: Double, vararg reps: Int) = benches(date, weight, *reps, program = gzclp, day = a1)
        val before = listOf(t2(LocalDate(2026, 10, 1), 37.5, 10, 10, 10), t2(lastBefore, 40.0, 10, 10, 10))
        val first = planBench(before, gzclp, a1)
        assertEquals(35.0, first.workSets.first().weightKg)
        assertEquals("3 × 10", first.targetLabel)

        // The first day back misses a rep at 35. Lighter on purpose: still 3 × 10, not 3 × 8.
        val one = before + t2(back, 35.0, 10, 10, 8)
        val second = planBench(one, gzclp, a1)
        assertEquals(37.5, second.workSets.first().weightKg)
        assertEquals("3 × 10", second.targetLabel)

        val three = one + t2(LocalDate(2026, 10, 23), 37.5, 10, 10, 10) + t2(LocalDate(2026, 10, 26), 40.0, 10, 10, 10)
        val after = planBench(three, gzclp, a1)
        assertEquals(42.5, after.workSets.first().weightKg)
        assertEquals("3 × 10", after.targetLabel)
        // Long after the easing ended, the replay still skips the light sessions.
        val later = planBench(three, gzclp, a1, easing = null)
        assertEquals("3 × 10", later.targetLabel)
        assertEquals(42.5, later.workSets.first().weightKg)
    }

    @Test
    fun aSlotsFirstSessionEasesTheWeightItBorrows() {
        // Never on A1 before; a free 60 × 5 before the break would seed A1's T2 bench. It eases too.
        val free = TestData.session(lastBefore, TestData.entry(bench, *(0..2).map { TestData.weighted(60.0, 5, it) }.toTypedArray()))
        val plain = planBench(listOf(free), gzclp, a1, easing = null, comebacks = emptyList())
        val eased = planBench(listOf(free), gzclp, a1)
        val start = plain.workSets.first().weightKg!!
        val hint = eased.hint as Hint.EaseBack
        assertEquals(start, hint.toKg)
        assertTrue(eased.workSets.first().weightKg!! < start)
        assertEquals(Comeback.weight(start, 0.9, 2.5, kg), eased.workSets.first().weightKg)
    }

    // ---- A free workout -------------------------------------------------------------------

    @Test
    fun aFreeWorkoutStartsFromLastTimesSetsLighter() {
        val before = TestData.session(lastBefore, TestData.entry(bench, TestData.weighted(60.0, 8), TestData.weighted(60.0, 8, 1)))
        val first = Comeback.freeCard(TrainingSnapshot(listOf(before), TestData.exercises), bench, easing, kg)!!
        assertEquals(52.5, first.weightKg)
        assertEquals(listOf(8, 8), first.reps)
        assertEquals(Hint.EaseBack(LastTime(60.0, listOf(8, 8)), 52.5, 60.0, 16), first.hint)

        val one = TestData.session(back, TestData.entry(bench, TestData.weighted(52.5, 8)))
        assertEquals(55.0, Comeback.freeCard(TrainingSnapshot(listOf(before, one), TestData.exercises), bench, easing, kg)!!.weightKg)
        val reached = TestData.session(back, TestData.entry(bench, TestData.weighted(60.0, 8)))
        assertNull(Comeback.freeCard(TrainingSnapshot(listOf(before, reached), TestData.exercises), bench, easing, kg))
        // Not a loaded lift: nothing to make lighter.
        assertNull(Comeback.freeCard(TrainingSnapshot(listOf(before), TestData.exercises), TestData.pullUp, easing, kg))
    }

    // ---- What's moving ----------------------------------------------------------------------

    @Test
    fun aLiftTrainedOnlySinceEasingBackInIsNotARegression() {
        val sessions = listOf(
            TestData.session(LocalDate(2026, 9, 20), TestData.entry(bench, TestData.weighted(60.0, 5))),
            TestData.session(LocalDate(2026, 9, 15), TestData.entry(bench, TestData.weighted(60.0, 5))),
            TestData.session(back, TestData.entry(bench, TestData.weighted(52.5, 5))),
            TestData.session(LocalDate(2026, 11, 20), TestData.entry(bench, TestData.weighted(55.0, 5))),
        )
        val today = LocalDate(2026, 11, 21)
        val engine = InsightEngine()
        assertTrue(engine.generate(sessions, TestData.exercises, today).any { it.kind == InsightKind.REGRESSION })
        assertTrue(engine.generate(sessions, TestData.exercises, today, easingSince = back).none { it.kind == InsightKind.REGRESSION })
    }
}
