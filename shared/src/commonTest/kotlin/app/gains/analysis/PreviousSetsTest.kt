package app.gains.analysis

import app.gains.catalogue.ProgramCatalogue
import app.gains.domain.ExerciseEntry
import app.gains.domain.Modality
import app.gains.domain.ProgramDay
import app.gains.domain.ProgramDayRef
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import app.gains.domain.WeightUnit
import app.gains.program.DayPlanner
import app.gains.program.Gzclp
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The PREV column of the set table: which session counts as last time, which set lines up with which, and how it reads. */
class PreviousSetsTest {
    private val bench = TestData.bench
    private val squat = TestData.squat
    private val kg = WeightUnit.KG

    private fun entry(weight: Double, vararg reps: Int) = ExerciseEntry(bench.id, reps.mapIndexed { i, r -> TestData.weighted(weight, r, i) })

    private val older = TestData.session(LocalDate(2025, 3, 1), entry(60.0, 5, 5, 5))
    private val recent = TestData.session(LocalDate(2025, 3, 8), entry(62.5, 5, 5, 4))
    private val squatOnly = TestData.session(LocalDate(2025, 3, 10), TestData.entry(squat, TestData.weighted(100.0, 5)))
    private val snapshot = TrainingSnapshot(listOf(older, squatOnly, recent), TestData.exercises)

    @Test
    fun lastEntryIsTheMostRecentSessionOfTheExercise() {
        assertEquals(recent.exercises.single(), PreviousSets.lastEntry(snapshot, bench.id))
        assertNull(PreviousSets.lastEntry(snapshot, TestData.plank.id))
    }

    @Test
    fun editingAnOldWorkoutLooksBeforeIt() {
        assertEquals(older.exercises.single(), PreviousSets.lastEntry(snapshot, bench.id, before = recent.timestamp, excludeSessionId = recent.id))
        assertNull(PreviousSets.lastEntry(snapshot, bench.id, before = older.timestamp, excludeSessionId = older.id))
    }

    @Test
    fun aGzclpLiftComparesWithItsOwnTier() {
        val gzclp = ProgramCatalogue.byId("gzclp")!!
        val (a1, a2) = gzclp.days[0] to gzclp.days[2]
        fun tagged(date: LocalDate, day: ProgramDay, weight: Double, vararg reps: Int) =
            TestData.session(date, ExerciseEntry(squat.id, reps.mapIndexed { i, r -> TestData.weighted(weight, r, i) }))
                .copy(program = ProgramDayRef(gzclp.id, day.id))
        val t1 = tagged(LocalDate(2025, 3, 3), a1, 75.0, 3, 3, 3, 3, 3, 10)
        val t2 = tagged(LocalDate(2025, 3, 7), a2, 70.0, 10, 10, 10)
        val history = TrainingSnapshot(listOf(t1, t2), TestData.exercises)
        val t1Slot = a1.slots.first { it.exerciseId == squat.id }

        assertEquals(t2, PreviousSets.lastSession(history, squat.id))
        assertEquals(t1, PreviousSets.lastSession(history, squat.id) { DayPlanner.onSameScheme(gzclp, t1Slot, it) })
        assertEquals(Gzclp.Tier.T2, DayPlanner.loggedSlot(listOf(gzclp), t2, squat.id)?.let { Gzclp.tierOf(it) })
        assertNull(DayPlanner.loggedSlot(listOf(gzclp), squatOnly, squat.id))
    }

    @Test
    fun warmupsOnlyDoNotCountAsLastTime() {
        val warmupsOnly = TestData.session(LocalDate(2025, 3, 12), ExerciseEntry(bench.id, listOf(TestData.weighted(40.0, 5, warmup = true))))
        assertEquals(recent.exercises.single(), PreviousSets.lastEntry(TrainingSnapshot(listOf(recent, warmupsOnly), TestData.exercises), bench.id))
    }

    @Test
    fun rowsLineUpBySetNumberWithinWarmupsAndWorkSets() {
        val previous = ExerciseEntry(bench.id, listOf(
            TestData.weighted(40.0, 5, 0, warmup = true),
            TestData.weighted(60.0, 5, 1),
            TestData.weighted(60.0, 4, 2),
        ))
        assertEquals(40.0, PreviousSets.matching(previous, isWarmup = true, ordinal = 1)?.weightKg)
        assertNull(PreviousSets.matching(previous, isWarmup = true, ordinal = 2))
        assertEquals(4, PreviousSets.matching(previous, isWarmup = false, ordinal = 2)?.reps)
        assertNull(PreviousSets.matching(previous, isWarmup = false, ordinal = 3))
        assertNull(PreviousSets.matching(null, isWarmup = false, ordinal = 1))
    }

    @Test
    fun labelsAreShort() {
        assertEquals("62.5×5", PreviousSets.label(TestData.weighted(62.5, 5), Modality.WEIGHTED, kg, app.gains.englishLabels))
        assertEquals("×8", PreviousSets.label(SetEntry(0, SetType.BODYWEIGHT, reps = 8), Modality.BODYWEIGHT, kg, app.gains.englishLabels))
        assertEquals("10×8", PreviousSets.label(SetEntry(0, SetType.WEIGHTED, weightKg = 10.0, reps = 8), Modality.BODYWEIGHT, kg, app.gains.englishLabels))
        assertEquals("45 s", PreviousSets.label(SetEntry(0, SetType.ISOMETRIC, seconds = 45), Modality.ISOMETRIC, kg, app.gains.englishLabels))
        assertEquals("10×1:30", PreviousSets.label(SetEntry(0, SetType.ISOMETRIC, weightKg = 10.0, seconds = 90), Modality.ISOMETRIC, kg, app.gains.englishLabels))
        assertEquals("5 km · 25:00", PreviousSets.label(SetEntry(0, SetType.CARDIO, distanceKm = 5.0, seconds = 1500), Modality.CARDIO, kg, app.gains.englishLabels))
        assertEquals("135×5", PreviousSets.label(TestData.weighted(app.gains.domain.Units.lbsToKg(135.0), 5), Modality.WEIGHTED, WeightUnit.LBS, app.gains.englishLabels))
        assertNull(PreviousSets.label(SetEntry(0, SetType.WEIGHTED), Modality.WEIGHTED, kg, app.gains.englishLabels))
    }
}
