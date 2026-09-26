package app.gains.analysis

import app.gains.analysis.Dates.plusDays
import app.gains.analysis.TestData.bench
import app.gains.analysis.TestData.entry
import app.gains.analysis.TestData.exercises
import app.gains.analysis.TestData.plank
import app.gains.analysis.TestData.pullUp
import app.gains.analysis.TestData.session
import app.gains.analysis.TestData.squat
import app.gains.analysis.TestData.weighted
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordsTest {
    private val byId = exercises.associateBy { it.id }
    private val day1 = LocalDate(2026, 3, 2)
    private fun day(n: Int) = day1.plusDays(n)

    @Test
    fun theFirstSessionOfAnExerciseSetsABaselineNotARecord() {
        val sessions = listOf(session(day(0), entry(bench, weighted(60.0, 8))))
        assertTrue(Records.timeline(sessions, byId).isEmpty())
        assertTrue(Records.forSession(sessions.single(), sessions, byId).isEmpty())
        assertEquals(60.0, Records.standing(sessions, byId).getValue(bench.id).getValue(RecordKind.WEIGHT).value)
    }

    @Test
    fun aHeavierSetIsAWeightRecordAndAnE1rmRecord() {
        val sessions = listOf(
            session(day(0), entry(bench, weighted(60.0, 8))),
            session(day(3), entry(bench, weighted(62.5, 8))),
        )
        val records = Records.forSession(sessions[1], sessions, byId)
        assertEquals(setOf(RecordKind.WEIGHT, RecordKind.E1RM, RecordKind.SET_VOLUME, RecordKind.SESSION_VOLUME), records.map { it.kind }.toSet())
        val weight = records.single { it.kind == RecordKind.WEIGHT }
        assertEquals(62.5, weight.value)
        assertEquals(60.0, weight.previous)
        assertEquals(day(0), weight.previousDate)
        assertEquals(sessions[1].id, weight.sessionId)
    }

    @Test
    fun aRecordMustClearTheOldOneByMoreThanRounding() {
        val sessions = listOf(
            session(day(0), entry(bench, weighted(100.0, 5))),
            session(day(3), entry(bench, weighted(100.0, 5))),
        )
        assertTrue(Records.forSession(sessions[1], sessions, byId).isEmpty())
    }

    @Test
    fun warmUpsNeverCount() {
        val sessions = listOf(
            session(day(0), entry(bench, weighted(60.0, 8))),
            // A "warm-up" heavier than anything before it: a mistake, and not a record.
            session(day(3), entry(bench, weighted(80.0, 1, warmup = true), weighted(60.0, 8))),
        )
        assertTrue(Records.forSession(sessions[1], sessions, byId).isEmpty())
    }

    @Test
    fun highRepSetsSetNoE1rmRecordButStillAWeightOrVolumeOne() {
        val sessions = listOf(
            session(day(0), entry(bench, weighted(60.0, 8))),
            session(day(3), entry(bench, weighted(60.0, 15))),
        )
        val kinds = Records.forSession(sessions[1], sessions, byId).map { it.kind }.toSet()
        assertTrue(RecordKind.E1RM !in kinds)
        assertTrue(RecordKind.SET_VOLUME in kinds)
        assertTrue(RecordKind.SESSION_VOLUME in kinds)
        assertTrue(RecordKind.WEIGHT !in kinds)
    }

    @Test
    fun anOldWorkoutIsJudgedAgainstItsOwnPastNotWhatCameAfter() {
        val sessions = listOf(
            session(day(0), entry(bench, weighted(60.0, 8))),
            session(day(3), entry(bench, weighted(65.0, 8))),
            session(day(6), entry(bench, weighted(70.0, 8))),
        )
        // Logged after the 70 kg session, but dated between the two: it beats 60, not 70.
        val backdated = session(day(4), entry(bench, weighted(67.5, 8)))
        val records = Records.forSession(backdated, sessions + backdated, byId)
        assertEquals(65.0, records.single { it.kind == RecordKind.WEIGHT }.previous)
    }

    @Test
    fun theTimelineListsEveryRecordInOrderAndTheStandingBestsAreTheLatestHolders() {
        val sessions = listOf(
            session(day(0), entry(bench, weighted(60.0, 8)), entry(squat, weighted(80.0, 5))),
            session(day(3), entry(bench, weighted(65.0, 8))),
            session(day(6), entry(squat, weighted(90.0, 5))),
            // Lower than the standing best: nothing set.
            session(day(9), entry(bench, weighted(60.0, 8))),
        )
        val timeline = Records.timeline(sessions, byId)
        assertEquals(listOf(day(3), day(3), day(3), day(3), day(6), day(6), day(6), day(6)), timeline.map { it.date })
        assertEquals(setOf(bench.id, squat.id), timeline.map { it.exerciseId }.toSet())
        val standing = Records.standing(sessions, byId)
        assertEquals(65.0, standing.getValue(bench.id).getValue(RecordKind.WEIGHT).value)
        assertEquals(day(3), standing.getValue(bench.id).getValue(RecordKind.WEIGHT).date)
        assertEquals(90.0, standing.getValue(squat.id).getValue(RecordKind.WEIGHT).value)
        assertEquals(mapOf(sessions[1].id to 4, sessions[2].id to 4), Records.bySession(sessions, byId).mapValues { it.value.size })
    }

    @Test
    fun aTieLeavesTheFirstHolderStanding() {
        val sessions = listOf(
            session(day(0), entry(bench, weighted(60.0, 8))),
            session(day(3), entry(bench, weighted(60.0, 8))),
        )
        assertEquals(day(0), Records.standing(sessions, byId).getValue(bench.id).getValue(RecordKind.WEIGHT).date)
    }

    @Test
    fun bodyweightHoldsAndCardioKeepTheirOwnKinds() {
        val pull = { reps: Int, added: Double? -> SetEntry(0, SetType.BODYWEIGHT, weightKg = added, reps = reps) }
        val hold = { s: Int -> SetEntry(0, SetType.ISOMETRIC, seconds = s) }
        val sessions = listOf(
            session(day(0), entry(pullUp, pull(8, null)), entry(plank, hold(60))),
            session(day(3), entry(pullUp, pull(10, null)), entry(plank, hold(75))),
            session(day(6), entry(pullUp, pull(5, 10.0))),
        )
        val second = Records.forSession(sessions[1], sessions, byId)
        assertEquals(listOf(RecordKind.REPS, RecordKind.HOLD), second.map { it.kind })
        assertEquals(10.0, second[0].value)
        assertEquals(75.0, second[1].value)
        // The first weighted pull-up is a baseline for added load, not a record; fewer reps set nothing.
        assertTrue(Records.forSession(sessions[2], sessions, byId).isEmpty())
        assertEquals(10.0, Records.standing(sessions, byId).getValue(pullUp.id).getValue(RecordKind.WEIGHT).value)
    }

    @Test
    fun anExerciseDoneTwiceInOneWorkoutIsOneExercise() {
        val sessions = listOf(
            session(day(0), entry(bench, weighted(60.0, 5))),
            session(day(3), entry(bench, weighted(60.0, 5)), entry(bench, weighted(62.5, 3))),
        )
        val records = Records.forSession(sessions[1], sessions, byId)
        assertEquals(62.5, records.single { it.kind == RecordKind.WEIGHT }.value)
        // Session volume adds up across both entries: 300 + 187.5.
        assertEquals(487.5, records.single { it.kind == RecordKind.SESSION_VOLUME }.value)
        assertNull(records.single { it.kind == RecordKind.SESSION_VOLUME }.set)
    }

    @Test
    fun aSingleSetIsJudgedLiveAgainstTheStandingRecords() {
        val standing = Records.standing(listOf(session(day(0), entry(bench, weighted(60.0, 8)))), byId).getValue(bench.id)
        assertTrue(Records.beats(weighted(62.5, 5), bench.modality, standing))
        // Fewer reps at the same weight: no kind a single set holds is beaten.
        assertFalse(Records.beats(weighted(60.0, 5), bench.modality, standing))
        // More reps at the same weight: a better e1RM and a bigger set.
        assertTrue(Records.beats(weighted(60.0, 10), bench.modality, standing))
        // A warm-up flag is the editor's to honour; the set itself is what is judged here.
        assertTrue(Records.beats(weighted(62.5, 5, warmup = true), bench.modality, standing))
        // Nothing standing: nothing to beat.
        assertFalse(Records.beats(weighted(100.0, 5), bench.modality, emptyMap()))
    }

    @Test
    fun repMaxesAreTheHeaviestForAtLeastThatManyReps() {
        val sessions = listOf(
            session(day(0), entry(bench, weighted(60.0, 8))),
            session(day(3), entry(bench, weighted(70.0, 5))),
            session(day(6), entry(bench, weighted(80.0, 2))),
        )
        val maxes = Records.repMaxes(sessions, bench.id)
        assertEquals(80.0, maxes.getValue(1).value)
        assertEquals(80.0, maxes.getValue(2).value)
        assertEquals(70.0, maxes.getValue(3).value)
        assertEquals(70.0, maxes.getValue(5).value)
        assertEquals(60.0, maxes.getValue(8).value)
        assertNull(maxes[10])
    }
}
