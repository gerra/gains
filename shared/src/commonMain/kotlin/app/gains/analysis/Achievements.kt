package app.gains.analysis

import app.gains.domain.Exercise
import app.gains.domain.Session
import app.gains.domain.SetType
import app.gains.domain.Units
import app.gains.domain.WeightUnit
import kotlinx.datetime.LocalDate

/**
 * What an achievement is for. Six tracks, each a ladder of tiers, all of them for training that
 * happened and none for using the app: nothing here is earned by opening a screen or writing a note.
 * The ladders keep going, so there is always a next rung; each is worded by the UI.
 */
enum class AchievementTrack {
    /** Workouts on record. */
    SESSIONS,
    /** Longest week streak reached, at the [StreakEngine.MILESTONES]. */
    STREAK,
    /** Weight × reps over every working set ever, in round numbers of the lifter's own unit. */
    TONNAGE,
    /** Personal records set. */
    RECORDS,
    /** Plates a side on one of the big barbell lifts: the 60 kg / 135 lb bench and up. */
    PLATES,
    /** Training again after four weeks or more away. Earned once, and gladly. */
    COMEBACK,
}

/** One rung of a track's ladder. [exerciseId] names the lift for [AchievementTrack.PLATES]. */
data class Achievement(
    val track: AchievementTrack,
    val tier: Int,
    /** Sessions, weeks, kilograms, records, kilograms on the bar or days away, by track. */
    val threshold: Double,
    val exerciseId: String? = null,
) {
    val id: String get() = listOfNotNull(track.name.lowercase(), exerciseId, tier.toString()).joinToString("-")
}

/**
 * An achievement and where the lifter stands on it: [earnedIn] is the session that earned it, null
 * while it is still ahead, and [progress] the current number against its threshold, so a locked
 * one can say how far there is to go.
 */
data class AchievementStatus(
    val achievement: Achievement,
    val earnedIn: SessionRef?,
    val progress: Double,
) {
    val earned: Boolean get() = earnedIn != null
    val fraction: Double get() = (progress / achievement.threshold).coerceIn(0.0, 1.0)
}

/**
 * Achievements, worked out from the session history in one forward pass. Nothing is stored: a
 * deleted workout takes its achievements with it, an imported history brings the ones it earned,
 * and the tiers that depend on the unit (tonnage, plates) follow the unit chosen.
 */
object Achievements {
    val SESSIONS = listOf(1, 10, 25, 50, 100, 250, 500, 1000)
    val STREAK_WEEKS = StreakEngine.MILESTONES
    val RECORDS = listOf(1, 10, 25, 50, 100, 250, 500)
    /** Round numbers in each unit: 10 t, 50 t… or 25 000 lb, 100 000 lb… */
    val TONNAGE_KG = listOf(10_000, 50_000, 100_000, 250_000, 500_000, 1_000_000, 2_500_000, 5_000_000, 10_000_000)
    val TONNAGE_LBS = listOf(25_000, 100_000, 250_000, 500_000, 1_000_000, 2_500_000, 5_000_000, 10_000_000, 25_000_000)
    /** The lifts the plate ladders are kept for. */
    val PLATE_LIFTS = listOf("squat", "bench_press", "deadlift", "overhead_press")
    const val MAX_PLATES = 5
    /** Days without a session after which the next one is a comeback. */
    const val COMEBACK_DAYS = 28

    /** A bar with [plates] a side: a 20 kg bar and 20 kg plates, or a 45 lb bar and 45 lb plates. */
    fun plateThresholdKg(plates: Int, unit: WeightUnit): Double = when (unit) {
        WeightUnit.KG -> 20.0 + plates * 40.0
        WeightUnit.LBS -> Units.lbsToKg(45.0 + plates * 90.0)
    }

    fun tonnageThresholdsKg(unit: WeightUnit): List<Double> = when (unit) {
        WeightUnit.KG -> TONNAGE_KG.map { it.toDouble() }
        WeightUnit.LBS -> TONNAGE_LBS.map { Units.lbsToKg(it.toDouble()) }
    }

    /** Every achievement there is, in ladder order, for a lifter working in [unit]. */
    fun catalogue(unit: WeightUnit): List<Achievement> = buildList {
        SESSIONS.forEachIndexed { i, n -> add(Achievement(AchievementTrack.SESSIONS, i + 1, n.toDouble())) }
        STREAK_WEEKS.forEachIndexed { i, n -> add(Achievement(AchievementTrack.STREAK, i + 1, n.toDouble())) }
        tonnageThresholdsKg(unit).forEachIndexed { i, kg -> add(Achievement(AchievementTrack.TONNAGE, i + 1, kg)) }
        RECORDS.forEachIndexed { i, n -> add(Achievement(AchievementTrack.RECORDS, i + 1, n.toDouble())) }
        for (lift in PLATE_LIFTS) for (plates in 1..MAX_PLATES) add(Achievement(AchievementTrack.PLATES, plates, plateThresholdKg(plates, unit), lift))
        add(Achievement(AchievementTrack.COMEBACK, 1, COMEBACK_DAYS.toDouble()))
    }

    /**
     * Where the lifter stands on every achievement. [goalPerWeek] is the streak's goal and only
     * fills its ring; it never decides a tier.
     */
    fun evaluate(
        sessions: List<Session>,
        exercisesById: Map<String, Exercise>,
        unit: WeightUnit,
        goalPerWeek: Int = StreakEngine.DEFAULT_GOAL,
    ): List<AchievementStatus> {
        val catalogue = catalogue(unit)
        val ordered = sessions.sortedWith(compareBy({ it.timestamp }, { it.id }))
        val recordsBySession = Records.bySession(ordered, exercisesById)
        val earned = HashMap<String, SessionRef>()
        val ladders = catalogue.groupBy { it.track to it.exerciseId }.mapValues { (_, rungs) -> rungs.sortedBy { it.tier } }

        // Climbs the ladder for [key] as far as [value] reaches, crediting [session] with each rung passed.
        fun climb(key: Pair<AchievementTrack, String?>, value: Double, session: Session) {
            for (rung in ladders[key].orEmpty()) {
                if (rung.id in earned) continue
                if (value >= rung.threshold - 1e-9) earned[rung.id] = SessionRef(session.id, session.date) else break
            }
        }

        var count = 0
        var tonnage = 0.0
        var records = 0
        var longestStreak = 0
        val plates = HashMap<String, Double>()
        val weeksSeen = HashSet<LocalDate>()
        val times = ArrayList<kotlinx.datetime.LocalDateTime>()
        var lastDate: LocalDate? = null
        for (session in ordered) {
            count++
            climb(AchievementTrack.SESSIONS to null, count.toDouble(), session)

            tonnage += tonnage(session)
            climb(AchievementTrack.TONNAGE to null, tonnage, session)

            records += recordsBySession[session.id]?.size ?: 0
            climb(AchievementTrack.RECORDS to null, records.toDouble(), session)

            for (entry in session.exercises) {
                if (entry.exerciseId !in PLATE_LIFTS) continue
                val top = entry.workingSets.filter { it.type == SetType.WEIGHTED && (it.reps ?: 0) > 0 }.maxOfOrNull { it.weightKg ?: 0.0 } ?: continue
                if (top > (plates[entry.exerciseId] ?: 0.0)) plates[entry.exerciseId] = top
                climb(AchievementTrack.PLATES to entry.exerciseId, top, session)
            }

            // The streak only moves on the first session of a week, so it is only recomputed then.
            times += session.timestamp
            if (weeksSeen.add(Dates.weekStart(session.date))) {
                val weeks = StreakEngine.computeAt(times, session.date, goalPerWeek).weeks
                longestStreak = maxOf(longestStreak, weeks)
                climb(AchievementTrack.STREAK to null, weeks.toDouble(), session)
            }

            val away = lastDate?.let { Dates.daysBetween(it, session.date) } ?: 0
            if (away >= COMEBACK_DAYS) climb(AchievementTrack.COMEBACK to null, away.toDouble(), session)
            lastDate = session.date
        }

        val current = ordered.lastOrNull()?.let { StreakEngine.computeAt(times, it.date, goalPerWeek).weeks } ?: 0
        return catalogue.map { a ->
            val progress = when (a.track) {
                AchievementTrack.SESSIONS -> count.toDouble()
                AchievementTrack.STREAK -> maxOf(current, if (a.id in earned) a.threshold.toInt() else 0).toDouble()
                AchievementTrack.TONNAGE -> tonnage
                AchievementTrack.RECORDS -> records.toDouble()
                AchievementTrack.PLATES -> plates[a.exerciseId] ?: 0.0
                AchievementTrack.COMEBACK -> if (a.id in earned) a.threshold else 0.0
            }
            AchievementStatus(a, earned[a.id], progress)
        }
    }

    /** Weight × reps over the session's working sets: what the tonnage ladder counts. */
    fun tonnage(session: Session): Double = session.exercises.sumOf { e -> e.workingSets.sumOf { it.volumeKg } }

    /** The achievements [sessionId] earned, in ladder order. */
    fun earnedIn(statuses: List<AchievementStatus>, sessionId: String): List<AchievementStatus> =
        statuses.filter { it.earnedIn?.id == sessionId }
}
