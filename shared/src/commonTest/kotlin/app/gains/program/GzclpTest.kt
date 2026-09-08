package app.gains.program

import app.gains.analysis.TestData
import app.gains.catalogue.ExerciseCatalogue
import app.gains.catalogue.ProgramCatalogue
import app.gains.domain.ExerciseEntry
import app.gains.domain.ExerciseSlot
import app.gains.domain.ProgressionRule
import app.gains.domain.RepTarget
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import app.gains.domain.SetsReps
import app.gains.domain.Units
import app.gains.domain.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GzclpTest {
    private val kg = WeightUnit.KG
    private fun sets(weight: Double, vararg reps: Int) = reps.mapIndexed { i, r -> TestData.weighted(weight, r, i) }

    private val gzclp = ProgramCatalogue.byId("gzclp")!!
    private fun slot(day: String, exerciseId: String) = gzclp.days.first { it.name == day }.slots.first { it.exerciseId == exerciseId }

    @Test
    fun tiersAreInferredFromTheScheme() {
        assertEquals(Gzclp.Tier.T1, Gzclp.tierOf(slot("A1", "squat")))
        assertEquals(Gzclp.Tier.T2, Gzclp.tierOf(slot("A1", "bench_press")))
        assertEquals(Gzclp.Tier.T3, Gzclp.tierOf(slot("A1", "lat_pulldown")))
        assertNull(Gzclp.tierOf(ExerciseSlot("bench_press", 3, RepTarget.Amrap(5), progression = ProgressionRule.Linear(2.5))))
        assertNull(Gzclp.tierOf(ExerciseSlot("bench_press", 3, RepTarget.Range(8, 12), progression = ProgressionRule.DoubleProgression(8, 12, 2.5))))
    }

    @Test
    fun incrementsComeFromTheSlotsRule() {
        assertEquals(5.0, Gzclp.increment(slot("A1", "squat"), kg))
        assertEquals(2.5, Gzclp.increment(slot("A1", "bench_press"), kg))
        assertEquals(10.0, Gzclp.increment(slot("A1", "squat"), WeightUnit.LBS))
        assertEquals(2.5, Gzclp.increment(ExerciseSlot("bench_press", 3, RepTarget.Fixed(10)), kg))
        assertEquals(5.0, Gzclp.increment(ExerciseSlot("bench_press", 3, RepTarget.Fixed(10)), WeightUnit.LBS))
    }

    @Test
    fun t2StartIsSixtyFivePercentOfEpleyRoundedDownAndBelowAFailedWeight() {
        // The motivating case: bench 50 kg × 6, 8, 9 must not become 3 × 10 at 50 kg.
        val e = Gzclp.estimate(Gzclp.Tier.T2, sets(50.0, 6, 8, 9), targetReps = 10, incrementDisplay = 2.5, unit = kg)!!
        assertEquals(50.0 * (1 + 9 / 30.0), e.e1rmKg, 1e-9)
        // 65 × 0.65 = 42.25, rounded down to the 2.5 kg plate: 40, and well under the 50 kg that only managed 9.
        assertEquals(40.0, e.startKg)
        assertTrue(e.startKg!! <= e.e1rmKg * Gzclp.Tier.T2.startFraction + 1e-9)
        assertNull(e.cappedBelowKg)
    }

    @Test
    fun startNeverReachesAWeightFailedForTheTiersReps() {
        // 60 kg × 3 in a free session is a strong set (e1RM 66) but 3 reps is under 10: T2 must stay below 60.
        val strong = sets(60.0, 3) + sets(40.0, 12, 12, 12)
        val e = Gzclp.estimate(Gzclp.Tier.T2, strong, targetReps = 10, incrementDisplay = 2.5, unit = kg)!!
        assertEquals(66.0, e.e1rmKg, 1e-9)
        assertEquals(42.5, e.startKg)
        assertTrue(e.startKg!! < 60.0)
        assertNull(e.cappedBelowKg)
        // Where the fraction alone would land at or above the failed weight, the cap bites and says so.
        val heavy = sets(50.0, 8) + sets(48.0, 9)
        val capped = Gzclp.estimate(Gzclp.Tier.T1, heavy, targetReps = 10, incrementDisplay = 2.5, unit = kg)!!
        assertEquals(48.0, capped.cappedBelowKg)
        assertEquals(45.0, capped.startKg)
    }

    @Test
    fun roundsDownToTheLoadableIncrementInTheDisplayUnit() {
        assertEquals(85.0, Gzclp.roundDown(86.13, 5.0))
        assertEquals(42.5, Gzclp.roundDown(42.5, 2.5), 1e-9)
        assertEquals(40.0, Gzclp.roundDown(42.49, 2.5))
        // Squat 100 × 5 → e1RM 116.7 → T1 99.2 → 95 on 5 kg plates.
        assertEquals(95.0, Gzclp.estimate(Gzclp.Tier.T1, sets(100.0, 5, 5, 5), 3, 5.0, kg)!!.startKg)
        // In lbs the increment is 10 lbs for squats: 220 lbs × 5 → e1RM 256.7 → T1 218 → 210 lbs.
        val lbs = Gzclp.estimate(Gzclp.Tier.T1, sets(Units.lbsToKg(220.0), 5, 5, 5), 3, 10.0, WeightUnit.LBS)!!
        assertEquals(210.0, Units.kgToLbs(lbs.startKg!!), 0.6)
    }

    @Test
    fun bestSetAcrossRecentSessionsDrivesTheEstimate() {
        val older = sets(55.0, 8)   // e1RM 69.7
        val latest = sets(50.0, 6, 8, 9) // e1RM 65
        val e = Gzclp.estimate(Gzclp.Tier.T2, latest + older, 10, 2.5, kg)!!
        assertEquals(55.0 * (1 + 8 / 30.0), e.e1rmKg, 1e-9)
        assertEquals(45.0, e.startKg)
    }

    @Test
    fun t3IsAboutHalfOfTheEstimate() {
        // Lat pulldown 50 × 12, 10, 8 → e1RM 70 → T3 36.75 → 35, and 50 was failed for 15 so the cap agrees.
        val e = Gzclp.estimate(Gzclp.Tier.T3, sets(50.0, 12, 10, 8), 15, 2.5, kg)!!
        assertEquals(35.0, e.startKg)
    }

    @Test
    fun bodyweightLoadIsNeverNegativeAndNothingIsNothing() {
        assertNull(Gzclp.estimate(Gzclp.Tier.T2, listOf(SetEntry(0, SetType.BODYWEIGHT, reps = 8)), 10, 2.5, kg))
        // +2.5 kg × 8 on pull-ups: 65% of a 3.2 kg estimate rounds to nothing → no added load, not a negative one.
        val tiny = Gzclp.estimate(Gzclp.Tier.T2, listOf(SetEntry(0, SetType.BODYWEIGHT, weightKg = 2.5, reps = 8)), 10, 2.5, kg)!!
        assertNull(tiny.startKg)
        // A failed weight that would cap below zero still yields nothing rather than a negative load.
        val failed = Gzclp.estimate(Gzclp.Tier.T2, listOf(SetEntry(0, SetType.BODYWEIGHT, weightKg = 2.5, reps = 4)), 10, 2.5, kg)!!
        assertNull(failed.startKg)
    }

    @Test
    fun warmupsForT1OpenWithTheBarAndClimbToEightyPercent() {
        val squat = TestData.squat
        val w = Gzclp.warmups(Gzclp.Tier.T1, squat, workKg = 100.0, barKg = 20.0, incrementDisplay = 5.0, unit = kg)
        assertEquals(listOf(20.0 to 10, 40.0 to 5, 60.0 to 3, 80.0 to 2), w.map { it.weightKg to it.reps })
    }

    @Test
    fun warmupsSkipSetsOnTheBarAndDuplicates() {
        // 30 kg bench: 40% = 12 → 10 (under the bar), 60% = 18 → 17.5 (under the bar), 80% = 24 → 25: only the bar and 25 remain.
        val w = Gzclp.warmups(Gzclp.Tier.T1, TestData.bench, 30.0, 20.0, 2.5, kg)
        assertEquals(listOf(20.0 to 10, 25.0 to 2), w.map { it.weightKg to it.reps })
        // At the bar itself there is nothing to warm up with.
        assertTrue(Gzclp.warmups(Gzclp.Tier.T2, TestData.bench, 20.0, 20.0, 2.5, kg).isEmpty())
        // Two fractions landing on the same plate load appear once.
        val ohp = Gzclp.warmups(Gzclp.Tier.T1, ExerciseCatalogue.byId("overhead_press")!!, 27.5, 20.0, 2.5, kg)
        assertEquals(listOf(20.0 to 10, 22.5 to 2), ohp.map { it.weightKg to it.reps })
    }

    @Test
    fun t2GetsBarAndSixtyPercentAndT3GetsNothingUnlessOnAMachine() {
        assertEquals(listOf(20.0 to 10, 25.0 to 5), Gzclp.warmups(Gzclp.Tier.T2, TestData.bench, 42.5, 20.0, 2.5, kg).map { it.weightKg to it.reps })
        assertTrue(Gzclp.warmups(Gzclp.Tier.T3, ExerciseCatalogue.byId("db_row")!!, 20.0, 20.0, 2.5, kg).isEmpty())
        assertEquals(listOf(20.0 to 8), Gzclp.warmups(Gzclp.Tier.T3, ExerciseCatalogue.byId("lat_pulldown")!!, 35.0, 20.0, 2.5, kg).map { it.weightKg to it.reps })
    }

    @Test
    fun dumbbellLiftsGetNoEmptyBarSet() {
        val w = Gzclp.warmups(Gzclp.Tier.T2, TestData.dbPress, 20.0, 20.0, 2.5, kg)
        assertEquals(listOf(12.5 to 5), w.map { it.weightKg to it.reps })
    }

    @Test
    fun restLabelsReadNaturally() {
        assertEquals("3–5 min", Gzclp.Tier.T1.restLabel)
        assertEquals("2–3 min", Gzclp.Tier.T2.restLabel)
        assertEquals("60–90 s", Gzclp.Tier.T3.restLabel)
        assertEquals("30–60 s or as needed", Gzclp.WARMUP_REST_LABEL)
        assertEquals(180, Gzclp.Tier.T1.restTimerSeconds)
    }

    @Test
    fun aCustomLadderIsRecognisedAsATier() {
        val t2 = ProgressionRule.StageLadder(listOf(SetsReps(3, RepTarget.Fixed(10)), SetsReps(3, RepTarget.Fixed(8))), 2.5)
        assertEquals(Gzclp.Tier.T2, Gzclp.tierOf(t2))
        val e = Gzclp.estimate(Gzclp.Tier.T2, ExerciseEntry("bench_press", sets(50.0, 6, 8, 9)).workingSets, 10, 2.5, kg)
        assertNotNull(e)
    }
}
