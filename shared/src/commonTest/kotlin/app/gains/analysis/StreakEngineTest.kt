package app.gains.analysis

import app.gains.analysis.Dates.minusDays
import app.gains.analysis.Dates.plusDays
import app.gains.analysis.TestData.entry
import app.gains.analysis.TestData.session
import app.gains.analysis.TestData.weighted
import app.gains.domain.Session
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreakEngineTest {
    /** A Wednesday, so five days of the week are left, today included, unless a test says otherwise. */
    private val wednesday = LocalDate(2026, 9, 2)
    private val saturday = LocalDate(2026, 9, 5)
    private val sunday = LocalDate(2026, 9, 6)
    private val thisMonday = Dates.weekStart(wednesday)

    /** One session in each of the weeks [weeksAgo], counted back from the week of [wednesday]. */
    private fun weeks(vararg weeksAgo: Int, perWeek: Int = 1, hour: Int = 18): List<Session> =
        weeksAgo.flatMap { ago ->
            val monday = thisMonday.minusDays(ago * 7)
            (0 until perWeek).map { session(monday.plusDays(it), entry(TestData.bench, weighted(60.0, 8)), hour = hour) }
        }

    // ---- The run ------------------------------------------------------------------------------

    @Test
    fun noSessionsMeansNoStreakAndNothingAtStake() {
        val streak = StreakEngine.compute(emptyList(), wednesday)
        assertEquals(0, streak.weeks)
        assertEquals(0, streak.best)
        assertEquals(StreakStatus.NONE, streak.status)
        assertEquals(4, streak.nextMilestone)
        assertEquals(List(7) { 0 }, streak.thisWeekSessions)
    }

    @Test
    fun consecutiveWeeksCountIncludingTheOneRunning() {
        val streak = StreakEngine.compute(weeks(2, 1, 0), wednesday)
        assertEquals(3, streak.weeks)
        assertEquals(3, streak.best)
        assertEquals(StreakStatus.SAFE, streak.status)
    }

    @Test
    fun theWeekRunningIsPendingNotMissed() {
        // Trained the last two weeks, nothing yet this week: the run still stands at two.
        val streak = StreakEngine.compute(weeks(2, 1), wednesday)
        assertEquals(2, streak.weeks)
        assertEquals(StreakStatus.OPEN, streak.status)
        assertEquals(5, streak.daysLeftInWeek)
    }

    @Test
    fun aMissedWeekWithNothingBankedEndsTheRun() {
        // Weeks 3 and 2 trained, week 1 missed, this week trained: the run is one.
        val streak = StreakEngine.compute(weeks(3, 2, 0), wednesday)
        assertEquals(1, streak.weeks)
        assertEquals(2, streak.best)
        assertTrue(streak.restWeeksUsed.isEmpty())
    }

    @Test
    fun multipleSessionsInAWeekCountOnceTowardsTheRun() {
        val streak = StreakEngine.compute(weeks(1, 0, perWeek = 4), wednesday)
        assertEquals(2, streak.weeks)
        assertEquals(4, streak.sessionsThisWeek)
    }

    // ---- Rest weeks ---------------------------------------------------------------------------

    @Test
    fun fourKeptWeeksBankARestWeekWhichHoldsTheRunButDoesNotAdvanceIt() {
        // Four weeks trained (5..2), week 1 missed, this week trained.
        val streak = StreakEngine.compute(weeks(5, 4, 3, 2, 0), wednesday)
        assertEquals(setOf(thisMonday.minusDays(7)), streak.restWeeksUsed)
        // Four kept, one held, one more kept: five, not six.
        assertEquals(5, streak.weeks)
        assertEquals(0, streak.restWeeksInHand)
        assertTrue(streak.heldLastWeek)
    }

    @Test
    fun restWeeksAreCappedAtTwo() {
        // Twelve kept weeks would earn three; only two can be held.
        val kept = (12 downTo 1).toList().toIntArray()
        val streak = StreakEngine.compute(weeks(*kept), wednesday)
        assertEquals(StreakEngine.MAX_REST_WEEKS, streak.restWeeksInHand)
        assertEquals(12, streak.weeks)
        assertTrue(streak.protectedByRestWeek)
    }

    @Test
    fun aThirdMissedWeekEndsTheRunAndClearsTheBank() {
        // Eight kept weeks (10..3) bank two; weeks 2 and 1 are held; this week starts again.
        val streak = StreakEngine.compute(weeks(10, 9, 8, 7, 6, 5, 4, 3, 0), wednesday)
        assertEquals(setOf(thisMonday.minusDays(14), thisMonday.minusDays(7)), streak.restWeeksUsed)
        assertEquals(9, streak.weeks)

        // The same history with one more missed week in it: nothing left to hold with.
        val broken = StreakEngine.compute(weeks(11, 10, 9, 8, 7, 6, 5, 4, 0), wednesday)
        assertEquals(1, broken.weeks)
        assertEquals(8, broken.best)
        assertEquals(0, broken.restWeeksInHand)
    }

    @Test
    fun aBrokenRunStartsEarningFromScratch() {
        // Three kept, one missed, three kept: neither run reaches four, so nothing is banked.
        val streak = StreakEngine.compute(weeks(6, 5, 4, 2, 1, 0), wednesday)
        assertEquals(3, streak.weeks)
        assertEquals(0, streak.restWeeksInHand)
    }

    // ---- Status and the week strip ------------------------------------------------------------

    @Test
    fun anEmptyWeekIsAtRiskOnSaturdayAndLastChanceOnSunday() {
        val history = weeks(2, 1)
        assertEquals(StreakStatus.OPEN, StreakEngine.compute(history, wednesday).status)
        assertEquals(StreakStatus.AT_RISK, StreakEngine.compute(history, saturday).status)
        assertEquals(StreakStatus.LAST_CHANCE, StreakEngine.compute(history, sunday).status)
        assertEquals(2, StreakEngine.compute(history, saturday).daysLeftInWeek)
        assertEquals(1, StreakEngine.compute(history, sunday).daysLeftInWeek)
    }

    @Test
    fun withNoRunToLoseAnEmptyWeekIsNotAtRisk() {
        // Trained five weeks ago and never since: there is nothing left to protect.
        assertEquals(StreakStatus.NONE, StreakEngine.compute(weeks(5), sunday).status)
    }

    @Test
    fun theWeekStripCountsSessionsPerDayFromMonday() {
        val sessions = listOf(
            session(thisMonday, entry(TestData.bench, weighted(60.0, 8))),
            session(thisMonday.plusDays(2), entry(TestData.bench, weighted(60.0, 8))),
            session(thisMonday.plusDays(2), entry(TestData.squat, weighted(60.0, 8))),
        )
        assertEquals(listOf(1, 0, 2, 0, 0, 0, 0), StreakEngine.compute(sessions, wednesday).thisWeekSessions)
    }

    @Test
    fun theGoalFillsTheWeekAndNeverBreaksTheRun() {
        val streak = StreakEngine.compute(weeks(1, 0, perWeek = 2), wednesday, goalPerWeek = 4)
        assertEquals(4, streak.goalPerWeek)
        assertEquals(2, streak.shortOfGoal)
        assertFalse(streak.fullWeek)
        // Two of four is still a kept week.
        assertEquals(2, streak.weeks)
        assertEquals(StreakStatus.SAFE, streak.status)
    }

    // ---- Milestones ---------------------------------------------------------------------------

    @Test
    fun milestonesAreRareThenYearly() {
        assertEquals(4, StreakEngine.nextMilestone(0))
        assertEquals(8, StreakEngine.nextMilestone(4))
        assertEquals(26, StreakEngine.nextMilestone(12))
        assertEquals(156, StreakEngine.nextMilestone(104))
        assertEquals(156, StreakEngine.nextMilestone(110))
        assertTrue(StreakEngine.isMilestone(52))
        assertTrue(StreakEngine.isMilestone(156))
        assertFalse(StreakEngine.isMilestone(0))
        assertFalse(StreakEngine.isMilestone(13))
    }

    @Test
    fun aMilestoneIsMarkedOnlyOnTheWeekItIsReached() {
        val reached = StreakEngine.compute(weeks(3, 2, 1, 0), wednesday)
        assertEquals(4, reached.weeks)
        assertTrue(reached.atMilestone)
        assertEquals(8, reached.nextMilestone)
        // The same four weeks with this one still empty: not yet.
        assertFalse(StreakEngine.compute(weeks(4, 3, 2, 1), wednesday).atMilestone)
    }

    // ---- The reminder -------------------------------------------------------------------------

    @Test
    fun theReminderHourIsTheHourTheyUsuallyTrain() {
        assertEquals(18, StreakEngine.usualHour(emptyList()))
        assertEquals(12, StreakEngine.usualHour(weeks(2, 1, 0, hour = 12)))
        // Outside civilised hours it is pulled back inside them: nobody wants a reminder at midnight.
        assertEquals(20, StreakEngine.usualHour(weeks(1, 0, hour = 22)))
        assertEquals(9, StreakEngine.usualHour(weeks(1, 0, hour = 5)))
    }

    @Test
    fun aWeekWithASessionInItIsNeverNudged() {
        val safe = StreakEngine.compute(weeks(1, 0), wednesday)
        assertEquals(emptyList<StreakNudge>(), StreakEngine.plan(safe, LocalDateTime(wednesday, LocalTime(12, 0))))
    }

    @Test
    fun anEmptyWeekIsNudgedOnSaturdayAndSunday() {
        val open = StreakEngine.compute(weeks(2, 1), wednesday)
        val planned = StreakEngine.plan(open, LocalDateTime(wednesday, LocalTime(12, 0)), hour = 19)
        assertEquals(listOf(StreakNudge.Kind.KEEP_ALIVE, StreakNudge.Kind.LAST_DAY), planned.map { it.kind })
        assertEquals(listOf(saturday, sunday), planned.map { it.at.date })
        assertTrue(planned.all { it.at.hour == 19 })
        assertEquals("streak-keep_alive-$saturday", planned.first().id)
    }

    @Test
    fun aReminderWhoseMomentHasPassedIsNotPlanned() {
        val open = StreakEngine.compute(weeks(2, 1), saturday)
        val evening = StreakEngine.plan(open, LocalDateTime(saturday, LocalTime(20, 30)), hour = 19)
        assertEquals(listOf(StreakNudge.Kind.LAST_DAY), evening.map { it.kind })
        val lastDay = StreakEngine.plan(StreakEngine.compute(weeks(2, 1), sunday), LocalDateTime(sunday, LocalTime(21, 0)), hour = 19)
        assertEquals(emptyList<StreakNudge>(), lastDay)
    }

    @Test
    fun nothingIsPlannedWhenThereIsNoRunToLose() {
        val none = StreakEngine.compute(weeks(5), saturday)
        assertEquals(emptyList<StreakNudge>(), StreakEngine.plan(none, LocalDateTime(saturday, LocalTime(9, 0))))
    }

    @Test
    fun aPlannedReminderCarriesTheStreakItIsProtecting() {
        val protectedRun = StreakEngine.compute(weeks(4, 3, 2, 1), saturday)
        val nudge = StreakEngine.plan(protectedRun, LocalDateTime(saturday, LocalTime(9, 0))).first()
        assertEquals(4, nudge.streak.weeks)
        assertTrue(nudge.streak.protectedByRestWeek)
    }
}
