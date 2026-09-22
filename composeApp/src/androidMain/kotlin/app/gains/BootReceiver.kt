package app.gains

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** A reboot clears every alarm, so the streak reminders still to come are set again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) AndroidNudgeScheduler.rearm(context)
    }
}
