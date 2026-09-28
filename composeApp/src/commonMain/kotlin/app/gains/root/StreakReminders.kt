package app.gains.root

import app.gains.analysis.StreakEngine
import app.gains.analysis.StreakNudge
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.platform.Nudge
import app.gains.platform.NudgeScheduler
import app.gains.resources.Res
import app.gains.resources.nudge_keep_body
import app.gains.resources.nudge_keep_rest_body
import app.gains.resources.nudge_keep_title
import app.gains.resources.nudge_last_body
import app.gains.resources.nudge_last_rest_body
import app.gains.resources.nudge_last_title
import app.gains.resources.weeks
import app.gains.ui.i18n.Texts
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * The streak reminders handed to the platform ([NudgeScheduler]), planned again whenever a session,
 * the weekly goal or the reminder setting changes. Nothing is scheduled until the lifter has asked
 * for it, and nothing in a week they have already trained: the plan comes back empty, which cancels
 * whatever was outstanding. A class of its own rather than an effect in the root, so these rules
 * are tested without composing the app. The root makes one per composition and [run]s it from a
 * `LaunchedEffect` keyed on the texts, so no reminder is planned outside the app's lifetime and a
 * change of language re-words the ones still to come.
 *
 * [clock] is "now" for the streak and the plan; tests pass a fixed one. [timeZone] is read at each
 * plan rather than once, as the device's zone can change while the app runs.
 */
internal class StreakReminders(
    private val sessions: SessionRepository,
    private val programs: ProgramRepository,
    private val settings: SettingsRepository,
    private val nudges: NudgeScheduler,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    /** Keeps the platform's plan in step, worded with [texts], until cancelled. */
    suspend fun run(texts: Texts) {
        combine(sessions.observeSessionTimes(), programs.observeState(), settings.observeStreakReminder()) { times, programState, on ->
            if (on != true) emptyList() else {
                val now = clock.now().toLocalDateTime(timeZone())
                val streak = StreakEngine.computeAt(times, now.date, programState.weeklyGoal)
                StreakEngine.plan(streak, now, StreakEngine.usualHourAt(times))
            }
        }
            .distinctUntilChanged()
            .collectLatest { planned ->
                val zone = timeZone()
                nudges.schedule(
                    planned.map { nudge ->
                        val (title, body) = nudgeWords(texts, nudge)
                        Nudge(nudge.id, nudge.at.toInstant(zone).toEpochMilliseconds(), title, body)
                    },
                )
            }
    }
}

/**
 * The reminder's own words, read outside the composition because the platform is handed finished
 * text to show hours later, when the app may not be running at all.
 */
internal suspend fun nudgeWords(texts: Texts, nudge: StreakNudge): Pair<String, String> {
    val weeks = texts.plural(Res.plurals.weeks, nudge.streak.weeks, nudge.streak.weeks)
    val covered = nudge.streak.protectedByRestWeek
    return when (nudge.kind) {
        StreakNudge.Kind.KEEP_ALIVE -> texts.get(Res.string.nudge_keep_title) to
            texts.get(if (covered) Res.string.nudge_keep_rest_body else Res.string.nudge_keep_body, weeks)
        StreakNudge.Kind.LAST_DAY -> texts.get(Res.string.nudge_last_title) to
            texts.get(if (covered) Res.string.nudge_last_rest_body else Res.string.nudge_last_body, weeks)
    }
}
