package app.gains.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the platform shows outside the app while a workout runs: an ongoing notification in the
 * tray on Android, one in Notification Centre on iOS, a tray icon on the desktop. It names the
 * workout, counts up from [startedAtMs] where the platform can, and gets the lifter back to the
 * workout with a tap. While a rest countdown runs, [restEndsAtMs] is set so the tray can show it
 * and offer to skip it.
 */
data class LiveSessionNotice(val title: String, val startedAtMs: Long, val restEndsAtMs: Long? = null)

/** Shown and cleared by the shared UI as the running workout comes and goes. Always called on the main thread. */
fun interface LiveSessionNotifier {
    /** Show or refresh the notice, or clear it when [notice] is null. */
    fun update(notice: LiveSessionNotice?)

    companion object {
        /** For platforms (and tests) with nothing to show. */
        val None = LiveSessionNotifier {}
    }
}

/**
 * Something asked for from the platform's notice, to be carried out by the shared UI. Counted, so
 * the UI can key on [pending] and call [consume] once it has acted.
 */
abstract class NoticeRequests {
    private val _pending = MutableStateFlow(0)
    /** Bumped on every request; zero once the UI has acted. */
    val pending: StateFlow<Int> = _pending

    /** True while the shared UI is collecting [pending] and will act on a request. */
    val attended: Boolean get() = _pending.subscriptionCount.value > 0

    fun request() { _pending.value = _pending.value + 1 }
    fun consume() { _pending.value = 0 }
}

/**
 * A tap on the platform's notice asking for the running workout. The shared UI opens it once the
 * database has said whether one is running, so a tap that launches the app is honoured too.
 */
object ResumeRequests : NoticeRequests()

/**
 * "Skip rest" on the platform's notice while a rest counts down. The shared UI drops the rest from
 * the running workout: in its editor when one is open, otherwise straight in the database.
 */
object SkipRestRequests : NoticeRequests()
