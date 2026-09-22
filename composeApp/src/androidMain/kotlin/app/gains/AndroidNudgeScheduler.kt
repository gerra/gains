package app.gains

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import app.gains.platform.Nudge
import app.gains.platform.NudgeScheduler

/**
 * The streak reminders as alarms. An alarm survives the app being killed, so the reminder arrives on
 * a Saturday evening the lifter has not opened Gains all week; [NudgeReceiver] turns it into the
 * notification when it goes off, and puts the alarms back after a reboot, which clears them.
 *
 * Every plan replaces the last one whole. What is outstanding is kept in preferences rather than in
 * memory, because the process is exactly what does not survive between a reminder being scheduled
 * and the workout that makes it pointless: log a session on Saturday morning after a relaunch and
 * the evening's alarm still has to come down.
 *
 * The alarms are inexact — a nudge is worth a few minutes' drift and not worth an exact-alarm
 * permission, and inexact alarms let the system batch them with everything else it wakes for.
 */
internal class AndroidNudgeScheduler(
    private val context: Context,
    private val requestPermission: () -> Unit = {},
) : NudgeScheduler {
    private var asked = false

    override fun schedule(nudges: List<Nudge>) {
        if (nudges.isNotEmpty() && !canPost() && !asked) {
            asked = true
            requestPermission()
        }
        replace(context, nudges)
    }

    private fun canPost(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val PREFS = "streak_nudges"
        private const val KEY_PLAN = "plan"
        /** `id|atEpochMs|title|body` per reminder; the fields cannot contain the separators. */
        private const val RECORD = "\u001E"
        private const val FIELD = "\u001F"

        private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun alarms(context: Context): AlarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        /** Cancels whatever was outstanding and puts [nudges] in its place, remembering them for a reboot. */
        fun replace(context: Context, nudges: List<Nudge>) {
            for (nudge in stored(context)) alarms(context).cancel(intent(context, nudge))
            prefs(context).edit().putString(KEY_PLAN, encode(nudges)).apply()
            arm(context, nudges)
        }

        /** Re-arms what is stored: after a reboot, and after each reminder goes off. */
        fun rearm(context: Context) = arm(context, stored(context))

        private fun arm(context: Context, nudges: List<Nudge>) {
            val now = System.currentTimeMillis()
            for (nudge in nudges) {
                if (nudge.atEpochMs <= now) continue
                // A window rather than an exact time: the system may move it by a few minutes to batch it.
                alarms(context).setWindow(AlarmManager.RTC_WAKEUP, nudge.atEpochMs, WINDOW_MS, intent(context, nudge))
            }
        }

        private const val WINDOW_MS = 10 * 60 * 1000L

        private fun intent(context: Context, nudge: Nudge): PendingIntent {
            val intent = Intent(context.applicationContext, NudgeReceiver::class.java)
                .setAction(NudgeReceiver.ACTION_NUDGE)
                // A distinct data URI per reminder: PendingIntents that differ only in their extras are the same one.
                .setData(Uri.parse("gains://nudge/" + nudge.id))
                .putExtra(NudgeReceiver.EXTRA_TITLE, nudge.title)
                .putExtra(NudgeReceiver.EXTRA_BODY, nudge.body)
            return PendingIntent.getBroadcast(
                context.applicationContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun stored(context: Context): List<Nudge> = decode(prefs(context).getString(KEY_PLAN, null))

        private fun encode(nudges: List<Nudge>): String = nudges.joinToString(RECORD) {
            listOf(it.id, it.atEpochMs.toString(), it.title, it.body).joinToString(FIELD)
        }

        private fun decode(raw: String?): List<Nudge> = raw.orEmpty().split(RECORD).mapNotNull { record ->
            val fields = record.split(FIELD)
            if (fields.size != 4) return@mapNotNull null
            val at = fields[1].toLongOrNull() ?: return@mapNotNull null
            Nudge(fields[0], at, fields[2], fields[3])
        }
    }
}
