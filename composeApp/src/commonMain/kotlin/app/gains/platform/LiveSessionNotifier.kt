package app.gains.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the platform shows outside the app while a workout runs: an ongoing notification in the
 * tray on Android, one in Notification Centre on iOS, a tray icon on the desktop. It names the
 * workout, counts up from [startedAtMs] where the platform can, and gets the lifter back to the
 * workout with a tap. While a rest countdown runs, [restEndsAtMs] is set so the tray can show it.
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
 * A tap on the platform's notice asking for the running workout. The shared UI opens it once the
 * database has said whether one is running, so a tap that launches the app is honoured too.
 */
object ResumeRequests {
    private val _pending = MutableStateFlow(0)
    /** Bumped on every request; the UI keys on it and calls [consume] when it has acted. */
    val pending: StateFlow<Int> = _pending

    fun request() { _pending.value = _pending.value + 1 }
    fun consume() { _pending.value = 0 }
}
