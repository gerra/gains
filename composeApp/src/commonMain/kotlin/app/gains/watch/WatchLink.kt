package app.gains.watch

import app.gains.data.ExerciseRepository
import app.gains.data.LiveSessionRepository
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.domain.Exercise
import app.gains.domain.LiveSession
import app.gains.program.Gzclp
import app.gains.ui.nowMs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The phone's half of the Apple Watch link (docs/watch.md), in common code so it is tested on the
 * desktop: [states] is what the watch is sent, [apply] carries out a watch command on the stored
 * workout. The platform part (WatchConnectivity on iOS, `IosWatchBridge`) only moves the JSON.
 *
 * [names] gives a lift its name in the app's language; [now] is the wall clock.
 */
internal class WatchLink(
    private val liveSessions: LiveSessionRepository,
    private val sessions: SessionRepository,
    private val exercises: ExerciseRepository,
    private val settings: SettingsRepository,
    private val programs: ProgramRepository,
    private val names: suspend (Exercise) -> String,
    private val now: () -> Long = ::nowMs,
) {
    /**
     * The watch's view of the workout in progress, a new one each time something on it changes.
     * When a workout stops, the states after it say whether it was stored ([WatchEnded.saved]) or
     * thrown away, until the next one starts.
     */
    fun states(): Flow<WatchState> = flow {
        var running: Long? = null
        var ended: WatchEnded? = null
        combine(liveSessions.observe(), exercises.observeExercises(), settings.observeUnit()) { live, list, unit -> Triple(live, list, unit) }
            .map { (live, list, unit) ->
                live?.let { session ->
                    val byId = list.associateBy { it.id }
                    val inWorkout = session.exercises.mapNotNull { byId[it.exerciseId] }
                    WatchPlan.workout(session, unit, byId, inWorkout.associate { it.id to names(it) })
                }
            }
            .distinctUntilChanged()
            .collect { workout ->
                val was = running
                if (workout == null && was != null) ended = WatchEnded(was, saved = wasStored(was))
                if (workout != null && workout.startedAtMs != was) ended = null
                running = workout?.startedAtMs
                emit(WatchState(sentAtMs = now(), workout = workout, ended = ended))
            }
    }

    /**
     * Carries out [command] on the stored workout, for when no editor holds it (the app is closed,
     * or the workout runs behind another screen). True when it changed something.
     */
    suspend fun apply(command: WatchCommand): Boolean {
        val session = liveSessions.load() ?: return false
        val tiers = tiers(session)
        val changed = WatchPlan.apply(session, command, now()) { id, warmup -> WatchPlan.restSeconds(tiers[id], warmup) } ?: return false
        liveSessions.save(changed)
        return true
    }

    /** Each lift's GZCLP tier on the program day the workout counts towards, as the editor plans it. */
    private suspend fun tiers(session: LiveSession): Map<String, Gzclp.Tier> {
        val ref = session.program ?: return emptyMap()
        val day = programs.observePrograms().first().firstOrNull { it.id == ref.programId }?.day(ref.dayId) ?: return emptyMap()
        return day.slots.mapNotNull { slot -> Gzclp.tierOf(slot)?.let { slot.exerciseId to it } }.toMap()
    }

    /** Whether a workout that started at [startedAtMs] is among the stored ones, under the id the editor gives it. */
    private suspend fun wasStored(startedAtMs: Long): Boolean {
        val at = Instant.fromEpochMilliseconds(startedAtMs).toLocalDateTime(TimeZone.currentSystemDefault())
        val base = LocalDateTime(at.date, LocalTime(at.hour, at.minute)).toString()
        return sessions.ids().any { it == base || it.startsWith("$base-") }
    }
}

/**
 * Commands from the watch for the shared UI to carry out while it runs, so a tick reaches the
 * open editor rather than the database under it. Whoever receives a command from the watch
 * checks [attended] first and, when nothing collects [commands], applies it with [WatchLink.apply].
 */
internal object WatchCommands {
    private val _commands = MutableSharedFlow<WatchCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<WatchCommand> = _commands

    /** True while the shared UI collects [commands]. */
    val attended: Boolean get() = _commands.subscriptionCount.value > 0

    /** False when the command could not be handed over and has to be applied some other way. */
    fun offer(command: WatchCommand): Boolean = attended && _commands.tryEmit(command)
}
