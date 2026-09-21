package app.gains.analysis

import app.gains.analysis.Dates.minusDays
import app.gains.analysis.Dates.plusDays
import app.gains.domain.GoalProfile
import app.gains.domain.Session
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber

/**
 * Where the streak stands this week. The wording on the card follows from this and nothing else,
 * so the card can never claim a week is at risk when it is not.
 */
enum class StreakStatus {
    /** Nothing at stake: no sessions yet, or the streak is already at zero and this week is empty. */
    NONE,
    /** This week has a session. Whatever happens next, the streak holds. */
    SAFE,
    /** This week is empty, but three or more days are left. */
    OPEN,
    /** This week is empty and two days are left. */
    AT_RISK,
    /** This week is empty and today is the last day of it. */
    LAST_CHANCE,
}

/**
 * The whole streak picture, derived from the session history alone.
 *
 * @property weeks consecutive weeks with at least one session, rest weeks holding but not adding
 * @property best the longest such run there has ever been
 * @property restWeeksInHand rest weeks banked, 0..[StreakEngine.MAX_REST_WEEKS]
 * @property sessionsThisWeek sessions logged since Monday
 * @property goalPerWeek sessions a week the lifter is aiming for; it fills the ring, never breaks the streak
 * @property daysLeftInWeek today included: 7 on Monday, 1 on Sunday
 * @property thisWeekSessions sessions per day, Monday first, for the week strip
 * @property restWeeksUsed the Mondays of weeks a rest week was spent on, for the calendar
 * @property heldLastWeek last week was missed and a rest week covered it
 * @property nextMilestone the streak length that earns the next celebration
 * @property atMilestone [weeks] is itself a milestone, reached this week
 */
data class Streak(
    val weeks: Int = 0,
    val best: Int = 0,
    val restWeeksInHand: Int = 0,
    val sessionsThisWeek: Int = 0,
    val goalPerWeek: Int = StreakEngine.DEFAULT_GOAL,
    val daysLeftInWeek: Int = 7,
    val status: StreakStatus = StreakStatus.NONE,
    val thisWeekSessions: List<Int> = List(7) { 0 },
    val restWeeksUsed: Set<LocalDate> = emptySet(),
    val heldLastWeek: Boolean = false,
    val nextMilestone: Int = StreakEngine.MILESTONES.first(),
    val atMilestone: Boolean = false,
) {
    /** The goal is met: a full week. */
    val fullWeek: Boolean get() = sessionsThisWeek >= goalPerWeek
    /** Sessions still to go for a full week. */
    val shortOfGoal: Int get() = (goalPerWeek - sessionsThisWeek).coerceAtLeast(0)
    /** A missed week would spend a rest week rather than end the run. */
    val protectedByRestWeek: Boolean get() = restWeeksInHand > 0
}

/** A reminder worth sending, with the moment to send it. The words are put on it by the UI layer. */
data class StreakNudge(val kind: Kind, val at: LocalDateTime, val streak: Streak) {
    enum class Kind {
        /** Two days left in an empty week. */
        KEEP_ALIVE,
        /** The last day of an empty week. */
        LAST_DAY,
    }

    /** Stable across a re-plan, so a platform can replace a reminder rather than stack another on it. */
    val id: String get() = "streak-${kind.name.lowercase()}-${at.date}"
}

/**
 * The week streak, its rest weeks and the reminders worth sending, all worked out from the session
 * history in one forward pass. Nothing is stored: re-importing years of history, editing a session's
 * date or deleting one all give the streak that history deserves, with no state to migrate or repair.
 *
 * The bar is one session a week, not one a day. A training log that asked for a session every day
 * would be asking for something no program wants, and the streak would mean nothing. A missed week
 * spends a rest week when one is banked ([EARN_EVERY] kept weeks bank one, [MAX_REST_WEEKS] at most):
 * the run holds but does not advance, because a week off is not a week trained. Rest weeks are spent
 * silently and shown afterwards, on the card and in the calendar.
 */
object StreakEngine {
    /** Kept weeks per rest week banked. */
    const val EARN_EVERY = 4
    /** A safety net with no ceiling stops being a safety net. */
    const val MAX_REST_WEEKS = 2
    /** Sessions a week when there is no program and no answer to the goal question. */
    const val DEFAULT_GOAL = GoalProfile.DEFAULT_DAYS
    /** Days left in the week at which an empty week becomes [StreakStatus.AT_RISK]. */
    const val AT_RISK_DAYS = 2
    /** Streak lengths worth marking. Past the last one, every [MILESTONE_STEP] weeks. */
    val MILESTONES = listOf(4, 8, 12, 26, 52, 104)
    private const val MILESTONE_STEP = 52
    /** The reminder is sent at the hour the lifter usually trains, kept inside civilised hours. */
    val REMINDER_HOURS = 9..20
    private const val DEFAULT_REMINDER_HOUR = 18
    /** How many recent sessions decide that hour. */
    private const val HOUR_SAMPLE = 30

    /**
     * [sessions] need not be sorted. [goalPerWeek] is the active program's days a week, or the
     * onboarding answer, or [DEFAULT_GOAL].
     */
    fun compute(sessions: List<Session>, today: LocalDate, goalPerWeek: Int = DEFAULT_GOAL): Streak =
        computeAt(sessions.map { it.timestamp }, today, goalPerWeek)

    /**
     * The same, from the moments alone. The streak needs nothing but when each session happened, so
     * the code that only wants the streak — the reminder — can read a single column and no sets.
     */
    fun computeAt(sessions: List<LocalDateTime>, today: LocalDate, goalPerWeek: Int = DEFAULT_GOAL): Streak {
        val goal = goalPerWeek.coerceAtLeast(1)
        val thisWeek = Dates.weekStart(today)
        val daysLeft = 8 - today.dayOfWeek.isoDayNumber
        if (sessions.isEmpty()) return Streak(goalPerWeek = goal, daysLeftInWeek = daysLeft)

        val perWeek = sessions.groupingBy { Dates.weekStart(it.date) }.eachCount()
        val restWeeks = LinkedHashSet<LocalDate>()
        var run = 0
        var banked = 0
        var best = 0
        var week = Dates.weekStart(sessions.minOf { it.date })
        while (week <= thisWeek) {
            when {
                (perWeek[week] ?: 0) > 0 -> {
                    run++
                    best = maxOf(best, run)
                    if (run % EARN_EVERY == 0 && banked < MAX_REST_WEEKS) banked++
                }
                // The week still running is neither kept nor missed; it is simply not over.
                week == thisWeek -> Unit
                banked > 0 -> { banked--; restWeeks += week }
                else -> { run = 0; banked = 0 }
            }
            week = week.plusDays(7)
        }

        val thisWeekCount = perWeek[thisWeek] ?: 0
        val status = when {
            thisWeekCount > 0 -> StreakStatus.SAFE
            run == 0 -> StreakStatus.NONE
            daysLeft <= 1 -> StreakStatus.LAST_CHANCE
            daysLeft <= AT_RISK_DAYS -> StreakStatus.AT_RISK
            else -> StreakStatus.OPEN
        }
        val perDay = sessions.filter { it.date >= thisWeek }.groupingBy { it.date }.eachCount()
        return Streak(
            weeks = run,
            best = best,
            restWeeksInHand = banked,
            sessionsThisWeek = thisWeekCount,
            goalPerWeek = goal,
            daysLeftInWeek = daysLeft,
            status = status,
            thisWeekSessions = List(7) { perDay[thisWeek.plusDays(it)] ?: 0 },
            restWeeksUsed = restWeeks,
            heldLastWeek = thisWeek.minusDays(7) in restWeeks,
            nextMilestone = nextMilestone(run),
            atMilestone = thisWeekCount > 0 && isMilestone(run),
        )
    }

    /** The next streak length worth marking after [weeks]; past the listed ones they come yearly. */
    fun nextMilestone(weeks: Int): Int = MILESTONES.firstOrNull { it > weeks }
        ?: (weeks / MILESTONE_STEP + 1) * MILESTONE_STEP

    fun isMilestone(weeks: Int): Boolean = weeks in MILESTONES || (weeks > MILESTONES.last() && weeks % MILESTONE_STEP == 0)

    /**
     * The hour a reminder should arrive: the one the lifter most often starts a session at, from the
     * last [HOUR_SAMPLE] sessions, kept inside [REMINDER_HOURS]. A nudge at the hour they normally
     * train is a nudge they can act on; a fixed hour chosen by the app is the kind people mute.
     */
    fun usualHour(sessions: List<Session>): Int = usualHourAt(sessions.map { it.timestamp })

    /** [usualHour] from the moments alone. */
    fun usualHourAt(sessions: List<LocalDateTime>): Int {
        val recent = sessions.sortedDescending().take(HOUR_SAMPLE)
        if (recent.isEmpty()) return DEFAULT_REMINDER_HOUR
        val counts = recent.groupingBy { it.hour }.eachCount()
        val most = counts.maxOf { it.value }
        // Earliest of the equally common hours: better a nudge with the evening still ahead.
        val hour = counts.filterValues { it == most }.keys.min()
        return hour.coerceIn(REMINDER_HOURS.first, REMINDER_HOURS.last)
    }

    /**
     * The reminders still to come this week: none at all unless the week is empty and there is a run
     * to lose, then one on Saturday and one on Sunday at [hour]. A lifter who has already trained this
     * week hears nothing, which is the whole promise — the app only speaks up when something is
     * actually at stake.
     */
    fun plan(streak: Streak, now: LocalDateTime, hour: Int = DEFAULT_REMINDER_HOUR): List<StreakNudge> {
        if (streak.status == StreakStatus.SAFE || streak.status == StreakStatus.NONE) return emptyList()
        val at = LocalTime(hour.coerceIn(REMINDER_HOURS.first, REMINDER_HOURS.last), 0)
        val monday = Dates.weekStart(now.date)
        return listOf(
            StreakNudge.Kind.KEEP_ALIVE to monday.plusDays(5), // Saturday
            StreakNudge.Kind.LAST_DAY to monday.plusDays(6), // Sunday
        )
            .map { (kind, date) -> StreakNudge(kind, LocalDateTime(date, at), streak) }
            .filter { it.at > now }
    }
}
