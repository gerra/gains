package app.gains

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.gains.data.LiveSessionRepository
import app.gains.platform.LiveSessionNotice
import app.gains.platform.SkipRestRequests
import app.gains.ui.inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Skip rest" on the workout notification. While the shared UI is up (the app is open or merely in
 * the background) it owns the workout, so the tap is handed to it. When the process was started just
 * for this tap, the rest is dropped in the database here and the notification redrawn without it.
 */
class SkipRestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SKIP_REST) return
        if (SkipRestRequests.attended) {
            SkipRestRequests.request()
            return
        }
        val result = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val liveSessions = inject<LiveSessionRepository>()
                liveSessions.clearRest()
                val session = liveSessions.load()
                withContext(Dispatchers.Main) {
                    AndroidLiveSessionNotifier(app).update(session?.let { LiveSessionNotice(it.title, it.startedAtMs) })
                }
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_SKIP_REST = "app.gains.action.SKIP_REST"
    }
}
