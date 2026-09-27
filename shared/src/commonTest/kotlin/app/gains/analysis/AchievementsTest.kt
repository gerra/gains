package app.gains.analysis

import app.gains.analysis.Dates.plusDays
import app.gains.analysis.TestData.bench
import app.gains.analysis.TestData.entry
import app.gains.analysis.TestData.exercises
import app.gains.analysis.TestData.session
import app.gains.analysis.TestData.squat
import app.gains.analysis.TestData.weighted
import app.gains.domain.Session
import app.gains.domain.Units
import app.gains.domain.WeightUnit
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AchievementsTest {
    private val byId = exercises.associateBy { it.id }
    /** A Monday. */
    private val day1 = LocalDate(2026, 3, 2)
    private fun day(n: Int) = day1.plusDays(n)

    private fun List<AchievementStatus>.on(track: AchievementTrack, tier: Int, exerciseId: String? = null) =
        single { it.achievement.track == track && it.achievement.tier == tier && it.achievement.exerciseId == exerciseId }

    @Test
    fun nothingOnRecordEarnsNothingAndEveryLadderIsThere() {
        val statuses = Achievements.evaluate(emptyList(), byId, WeightUnit.KG)
        assertTrue(statuses.none { it.earned })
        assertEquals(Achievements.catalogue(WeightUnit.KG), statuses.map { it.achievement })
        assertEquals(0.0, statuses.on(AchievementTrack.SESSIONS, 1).progress)
    }

    @Test
    fun theFirstWorkoutIsTheFirstRungAndTheTenthTheSecond() {
        val sessions = (0 until 10).map { session(day(it * 2), entry(bench, weighted(60.0, 8))) }
        val statuses = Achievements.evaluate(sessions, byId, WeightUnit.KG)
        assertEquals(SessionRef(sessions[0].id, sessions[0].date), statuses.on(AchievementTrack.SESSIONS, 1).earnedIn)
        assertEquals(SessionRef(sessions[9].id, sessions[9].date), statuses.on(AchievementTrack.SESSIONS, 2).earnedIn)
        assertNull(statuses.on(AchievementTrack.SESSIONS, 3).earnedIn)
        assertEquals(10.0, statuses.on(AchievementTrack.SESSIONS, 3).progress)
        assertEquals(0.4, statuses.on(AchievementTrack.SESSIONS, 3).fraction)
    }

    @Test
    fun theStreakRungIsEarnedByTheSessionThatMakesTheFourthWeek() {
        val sessions = (0 until 4).map { session(day(it * 7 + 2), entry(bench, weighted(60.0, 8))) }
        val statuses = Achievements.evaluate(sessions, byId, WeightUnit.KG)
        assertEquals(sessions[3].id, statuses.on(AchievementTrack.STREAK, 1).earnedIn?.id)
        assertNull(statuses.on(AchievementTrack.STREAK, 2).earnedIn)
        assertEquals(4.0, statuses.on(AchievementTrack.STREAK, 2).progress)
    }

    @Test
    fun aBrokenStreakKeepsWhatItEarnedAndShowsTheCurrentRun() {
        val kept = (0 until 4).map { session(day(it * 7 + 2), entry(bench, weighted(60.0, 8))) }
        // Six weeks off, then one session: the run is back to one.
        val later = session(day(4 * 7 + 6 * 7 + 2), entry(bench, weighted(60.0, 8)))
        val statuses = Achievements.evaluate(kept + later, byId, WeightUnit.KG)
        assertTrue(statuses.on(AchievementTrack.STREAK, 1).earned)
        assertEquals(1.0, statuses.on(AchievementTrack.STREAK, 2).progress)
        // And the return after more than four weeks away is the comeback.
        assertEquals(later.id, statuses.on(AchievementTrack.COMEBACK, 1).earnedIn?.id)
    }

    @Test
    fun aShortBreakIsNotAComeback() {
        val sessions = listOf(session(day(0), entry(bench, weighted(60.0, 8))), session(day(20), entry(bench, weighted(60.0, 8))))
        assertFalse(Achievements.evaluate(sessions, byId, WeightUnit.KG).on(AchievementTrack.COMEBACK, 1).earned)
    }

    @Test
    fun tonnageAddsUpOverWorkingSetsInTheLiftersUnit() {
        // 100 kg × 10 × 5 sets = 5 000 kg a session.
        val heavy = { d: LocalDate -> session(d, entry(squat, *(0 until 5).map { weighted(100.0, 10, order = it) }.toTypedArray())) }
        val sessions = (0 until 3).map { heavy(day(it * 2)) }
        val kg = Achievements.evaluate(sessions, byId, WeightUnit.KG)
        assertEquals(sessions[1].id, kg.on(AchievementTrack.TONNAGE, 1).earnedIn?.id)
        assertEquals(15_000.0, kg.on(AchievementTrack.TONNAGE, 2).progress)
        // In pounds the first rung is 25 000 lb, about 11 340 kg: the third session reaches it.
        val lbs = Achievements.evaluate(sessions, byId, WeightUnit.LBS)
        assertEquals(sessions[2].id, lbs.on(AchievementTrack.TONNAGE, 1).earnedIn?.id)
        assertEquals(Units.lbsToKg(25_000.0), lbs.on(AchievementTrack.TONNAGE, 1).achievement.threshold)
    }

    @Test
    fun platesFollowTheUnitAndEachLiftHasItsOwnLadder() {
        val sessions = listOf(
            session(day(0), entry(bench, weighted(60.0, 5)), entry(squat, weighted(100.0, 5))),
            session(day(3), entry(bench, weighted(102.5, 1))),
        )
        val kg = Achievements.evaluate(sessions, byId, WeightUnit.KG)
        assertEquals(sessions[0].id, kg.on(AchievementTrack.PLATES, 1, bench.id).earnedIn?.id)
        assertEquals(sessions[1].id, kg.on(AchievementTrack.PLATES, 2, bench.id).earnedIn?.id)
        assertEquals(sessions[0].id, kg.on(AchievementTrack.PLATES, 2, squat.id).earnedIn?.id)
        assertNull(kg.on(AchievementTrack.PLATES, 1, "deadlift").earnedIn)
        assertEquals(102.5, kg.on(AchievementTrack.PLATES, 3, bench.id).progress)
        // 135 lb is 61.2 kg, so 60 kg is not a plate in pounds; 102.5 kg clears 225 lb.
        val lbs = Achievements.evaluate(sessions, byId, WeightUnit.LBS)
        assertEquals(sessions[1].id, lbs.on(AchievementTrack.PLATES, 1, bench.id).earnedIn?.id)
        assertEquals(sessions[1].id, lbs.on(AchievementTrack.PLATES, 2, bench.id).earnedIn?.id)
    }

    @Test
    fun recordsCountTowardsTheirLadder() {
        val sessions = listOf(
            session(day(0), entry(bench, weighted(60.0, 8))),
            session(day(3), entry(bench, weighted(62.5, 8))),
        )
        val statuses = Achievements.evaluate(sessions, byId, WeightUnit.KG)
        assertEquals(sessions[1].id, statuses.on(AchievementTrack.RECORDS, 1).earnedIn?.id)
        assertEquals(4.0, statuses.on(AchievementTrack.RECORDS, 2).progress)
        assertEquals(listOf(AchievementTrack.RECORDS), Achievements.earnedIn(statuses, sessions[1].id).map { it.achievement.track })
        // The first workout: the first rung of the sessions ladder, and 60 kg on the bench is a plate a side.
        assertEquals(listOf(AchievementTrack.SESSIONS, AchievementTrack.PLATES), Achievements.earnedIn(statuses, sessions[0].id).map { it.achievement.track })
    }

    @Test
    fun theOrderTheSessionsArriveInDoesNotMatter() {
        val sessions: List<Session> = (0 until 12).map { session(day(it * 3), entry(bench, weighted(60.0 + it, 5))) }
        val forward = Achievements.evaluate(sessions, byId, WeightUnit.KG)
        val backward = Achievements.evaluate(sessions.reversed(), byId, WeightUnit.KG)
        assertEquals(forward, backward)
    }
}
