package app.gains

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.gains.platform.LiveSessionNotice
import app.gains.platform.LiveSessionNotifier

/**
 * The running workout as an ongoing notification in the tray: its name, the clock counting up (or
 * the rest counting down) drawn by the system, so it keeps time even while the app is killed, and
 * a tap that brings the workout back. Cleared when the session is ended or discarded.
 *
 * Android 13 needs permission before anything can be posted. [requestPermission] is the activity's
 * prompt; it is shown once, on the first notice, and the notice is posted when it is granted.
 */
class AndroidLiveSessionNotifier(private val context: Context, private val requestPermission: () -> Unit) : LiveSessionNotifier {
    // Fetched on use: the activity constructs this before it has a base context.
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var pending: LiveSessionNotice? = null
    private var asked = false

    override fun update(notice: LiveSessionNotice?) {
        pending = notice
        if (notice == null) { manager.cancel(NOTIFICATION_ID); return }
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
            .setContentTitle(notice.title)
            .setContentText(if (resting) "Resting. Tap to get back to your workout." else "Workout in progress. Tap to get back to it.")
            // The system draws the clock from `when`: up from the start, or down to the end of the rest.
            .setWhen(notice.restEndsAtMs ?: notice.startedAtMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(resting)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(tap)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "live_session"
        const val NOTIFICATION_ID = 1
    }
}
