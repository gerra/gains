package app.gains

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import app.gains.platform.LiveSessionNotice
import app.gains.platform.LiveSessionNotifier

/**
 * The running workout as an ongoing notification in the tray: its name, the total time counting
 * up and, while a rest runs, the rest counting down beside it, with a "Skip rest" button. Both
 * clocks are drawn by the system, so they keep time while the app is asleep or killed. A tap
 * brings the workout back. Cleared when the session is ended or discarded.
 *
 * Android 13 needs permission before anything can be posted. [requestPermission] is the activity's
 * prompt; it is shown once per workout, on the first notice, and the notice is posted when it is
 * granted. The receiver behind "Skip rest" posts on its own with no prompt to offer.
 */
internal class AndroidLiveSessionNotifier(private val context: Context, private val requestPermission: () -> Unit = {}) : LiveSessionNotifier {
    // Fetched on use: the activity constructs this before it has a base context.
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var pending: LiveSessionNotice? = null
    private var asked = false

    override fun update(notice: LiveSessionNotice?) {
        pending = notice
        if (notice == null) {
            manager.cancel(NOTIFICATION_ID)
            // The next workout may ask again: a prompt that was dismissed rather than refused comes back.
            asked = false
            return
        }
        if (!canPost()) {
            if (!asked) { asked = true; requestPermission() }
            return
        }
        post(notice)
    }

    /** The permission prompt came back: post the notice that was waiting on it. */
    fun onPermissionResult(granted: Boolean) {
        if (granted) pending?.let(::post)
    }

    private fun canPost(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(notice: LiveSessionNotice) {
        val channel = NotificationChannel(CHANNEL_ID, "Workout in progress", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a workout is running, so you can get back to it from anywhere."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_RESUME_SESSION)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val resting = notice.restEndsAtMs != null
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.notification_accent))
            // The template's own lines are replaced by the clocks below; these remain for screen readers and watches.
            .setContentTitle(notice.title)
            .setContentText(if (resting) "Resting. Tap to get back to your workout." else "Workout in progress. Tap to get back to it.")
            // The system's header and action row around our own content, themed to the tray's light or dark background.
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(clocks(notice))
            .setCustomBigContentView(clocks(notice))
            // No clock in the header: the content has the real ones.
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(tap)
            .apply {
                if (resting) {
                    val skip = Intent(context, SkipRestReceiver::class.java).setAction(SkipRestReceiver.ACTION_SKIP_REST)
                    val onSkip = PendingIntent.getBroadcast(context, 1, skip, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    // The icon is not drawn on these Android versions, but the builder wants one.
                    val icon = Icon.createWithResource(context, R.drawable.ic_notification)
                    addAction(Notification.Action.Builder(icon, context.getString(R.string.notification_skip_rest), onSkip).build())
                }
            }
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** The notification's content: the workout's name over the total time, plus the rest countdown while one runs. */
    private fun clocks(notice: LiveSessionNotice): RemoteViews =
        RemoteViews(context.packageName, R.layout.notification_live_session).apply {
            setTextViewText(R.id.workout_title, notice.title)
            setChronometer(R.id.total_clock, uptimeAt(notice.startedAtMs), null, true)
            val restEndsAt = notice.restEndsAtMs
            setViewVisibility(R.id.rest_group, if (restEndsAt != null) View.VISIBLE else View.GONE)
            if (restEndsAt != null) {
                setChronometerCountDown(R.id.rest_clock, true)
                setChronometer(R.id.rest_clock, uptimeAt(restEndsAt), null, true)
            }
        }

    /** Chronometers count against the uptime clock, not the wall clock: the same instant on that clock. */
    private fun uptimeAt(wallMs: Long): Long = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - wallMs)

    private companion object {
        const val CHANNEL_ID = "live_session"
        const val NOTIFICATION_ID = 1
    }
}
