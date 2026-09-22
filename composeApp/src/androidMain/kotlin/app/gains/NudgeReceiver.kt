package app.gains

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * A streak reminder going off. Not exported: the only thing that can reach it is the alarm the app
 * itself set, so nothing else can put words in a Gains notification.
 *
 * Those words came with the alarm. They were worked out when the plan was made, by the shared code
 * that knows the streak and the language, so the reminder costs no database work in a process the
 * system started only to show a notification.
 */
class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NUDGE) return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        post(context, title, intent.getStringExtra(EXTRA_BODY).orEmpty())
        // Saturday's reminder is done with; Sunday's is still to come.
        AndroidNudgeScheduler.rearm(context)
    }

    private fun post(context: Context, title: String, body: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_streak), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notification_channel_streak_description)
            },
        )
        val open = Intent(context.applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(context, 2, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_NUDGE = "app.gains.action.STREAK_NUDGE"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        private const val CHANNEL_ID = "streak_reminder"
        private const val NOTIFICATION_ID = 2
    }
}
