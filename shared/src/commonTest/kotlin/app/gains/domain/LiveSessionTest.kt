package app.gains.domain

import app.gains.analysis.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveSessionTest {
    private val hour = 60L * 60 * 1000

    @Test
    fun elapsedCountsFromTheStartAndNeverGoesNegative() {
        val live = LiveSession(startedAtMs = 10_000, title = "A1")
        assertEquals(0L, live.elapsedMs(9_000))
        assertEquals(0L, live.elapsedMs(10_000))
        assertEquals(42_000L, live.elapsedMs(52_000))
    }

    @Test
    fun threeHoursIsTheLongSessionLine() {
        assertFalse(LiveSession.isLong(3 * hour))
        assertTrue(LiveSession.isLong(3 * hour + 1))
        val live = LiveSession(startedAtMs = 0, title = "A1")
        assertFalse(live.hasRunLong(45 * 60_000L))
        assertTrue(live.hasRunLong(4 * hour))
    }

    @Test
    fun storedDurationIsWholeMinutesAndAtLeastOne() {
        assertEquals(1, LiveSession.durationMinutes(0))
        assertEquals(1, LiveSession.durationMinutes(20_000))
        assertEquals(1, LiveSession.durationMinutes(89_999))
        assertEquals(2, LiveSession.durationMinutes(90_000))
        assertEquals(45, LiveSession.durationMinutes(45 * 60_000L + 12_000))
        assertEquals(222, LiveSession.durationMinutes(3 * hour + 42 * 60_000L))
    }

    @Test
    fun restTimerRoundsRemainingUpAndClampsToItsLength() {
        val rest = RestTimer("squat", endsAtMs = 100_000, totalSeconds = 90)
        assertEquals(90, rest.remainingSeconds(0))
        assertEquals(90, rest.remainingSeconds(10_000))
        assertEquals(60, rest.remainingSeconds(40_000))
        assertEquals(1, rest.remainingSeconds(99_500))
        assertEquals(0, rest.remainingSeconds(100_000))
        assertEquals(0, rest.remainingSeconds(150_000))
        assertFalse(rest.isOver(99_999))
        assertTrue(rest.isOver(100_000))
    }

    @Test
    fun setDraftBecomesTheRightKindOfSet() {
        assertEquals(SetEntry(0, SetType.WEIGHTED, 60.0, 5), SetDraft(weight = "60", reps = "5").toSet(0, WeightUnit.KG))
        assertEquals(SetEntry(1, SetType.BODYWEIGHT, null, 8), SetDraft(reps = "8").toSet(1, WeightUnit.KG))
        assertEquals(SetEntry(2, SetType.ISOMETRIC, null, null, 30), SetDraft(seconds = "30").toSet(2, WeightUnit.KG))
        assertEquals(SetEntry(3, SetType.CARDIO, null, null, 600, 2.5), SetDraft(distanceKm = "2,5", seconds = "600").toSet(3, WeightUnit.KG))
        assertNull(SetDraft().toSet(0, WeightUnit.KG))
        assertNull(SetDraft(weight = "60.", reps = "0").toSet(0, WeightUnit.KG)?.reps)
        // The tick is editor state and is not carried into the stored set; the warm-up flag is.
        assertTrue(SetDraft(weight = "20", reps = "10", isWarmup = true, done = true).toSet(0, WeightUnit.KG)!!.isWarmup)
        // A stored set comes back in the display unit, and the round trip keeps the values.
        val lbs = SetDraft.from(SetEntry(0, SetType.WEIGHTED, 60.0, 5), WeightUnit.LBS)
        assertEquals("132.3", lbs.weight)
        assertEquals(SetEntry(0, SetType.WEIGHTED, 60.0, 5), lbs.toSet(0, WeightUnit.LBS))
    }

    @Test
    fun clockAndMinuteLabels() {
        assertEquals("0:00", Format.clock(0))
        assertEquals("0:42", Format.clock(42))
        assertEquals("12:05", Format.clock(12 * 60 + 5))
        assertEquals("1:02:34", Format.clock(3600 + 2 * 60 + 34))
        assertEquals("0:00", Format.clock(-5))
        assertEquals("45 min", Format.minutes(45))
        assertEquals("1 h", Format.minutes(60))
        assertEquals("3 h 42 min", Format.minutes(222))
    }
}
