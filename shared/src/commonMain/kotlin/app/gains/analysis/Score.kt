package app.gains.analysis

import app.gains.domain.Exercise
import app.gains.domain.Session
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * What one workout scored, and from what. Every point is for something that happened in the gym,
 * and the three parts are shown as they are, so the number is never a mystery: showing up is most
 * of it, the work done adds a little, and a record adds a little more. The caps keep it honest — a
 * thirtieth set is not training, and no amount of tiny records outweighs turning up next week.
 */
data class SessionScore(
    val sessionId: String,
    val showedUp: Int,
    val work: Int,
    val records: Int,
) {
    val total: Int get() = showedUp + work + records
}

/**
 * Where the running total stands: [level], the points into it and the points to the next one. The
 * levels come quickly at first and then further apart, like anything worth keeping at.
 */
data class Level(
    val level: Int,
    val points: Int,
    val floor: Int,
    val ceiling: Int,
) {
    val into: Int get() = points - floor
    val toNext: Int get() = ceiling - points
    val fraction: Double get() = if (ceiling > floor) into.toDouble() / (ceiling - floor) else 1.0
}

/**
 * The cumulative score: a few points a workout, added up over everything on record. It is not a
 * measure of strength — the records and the e1RM chart are — but of the training done, which is
 * the thing a lifter controls. Nothing is stored; the total is the history's, recomputed as it changes.
 */
object Scoring {
    /** For the workout itself. Most of the score, because most of training is turning up. */
    const val SHOW_UP = 10
    /** Per working set, up to [MAX_WORK]. */
    const val PER_SET = 1
    const val MAX_WORK = 20
    /** Per record set in the workout, up to [MAX_RECORDS]. */
    const val PER_RECORD = 5
    const val MAX_RECORDS = 20

    /** Level [n] starts at this many points: 0, 100, 300, 600, 1000, 1500… */
    fun floorOf(n: Int): Int = 50 * n * (n - 1)

    fun session(session: Session, records: List<Record>): SessionScore = SessionScore(
        sessionId = session.id,
        showedUp = SHOW_UP,
        work = (session.workingSetCount * PER_SET).coerceAtMost(MAX_WORK),
        records = (records.size * PER_RECORD).coerceAtMost(MAX_RECORDS),
    )

    /** Every session's score, by session id. */
    fun sessions(sessions: List<Session>, exercisesById: Map<String, Exercise>): Map<String, SessionScore> {
        val records = Records.bySession(sessions, exercisesById)
        return sessions.associate { it.id to session(it, records[it.id].orEmpty()) }
    }

    fun total(scores: Collection<SessionScore>): Int = scores.sumOf { it.total }

    fun level(points: Int): Level {
        // The largest n with 50·n·(n−1) ≤ points, from the quadratic, then corrected for rounding.
        var n = floor((1 + sqrt(1 + points / 12.5)) / 2).toInt().coerceAtLeast(1)
        while (floorOf(n + 1) <= points) n++
        while (n > 1 && floorOf(n) > points) n--
        return Level(n, points, floorOf(n), floorOf(n + 1))
    }
}
