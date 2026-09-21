package app.gains

import app.gains.platform.Nudge
import app.gains.platform.NudgeScheduler
import app.gains.ui.nowMs
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

/**
 * The streak reminders as scheduled local notifications. iOS holds them and delivers them on time
 * whether or not the app is running, which is the whole point: the nudge has to arrive on a Saturday
 * evening the lifter has not opened Gains all week.
 *
 * Every plan replaces the last one whole. What is outstanding is read back from the system rather
 * than remembered in the process, because the process is exactly what does not survive between a
 * reminder being scheduled and the workout that makes it pointless: log a session on Saturday
 * morning after a relaunch and the evening's reminder still has to come down.
 *
 * Nothing is scheduled until the reminder has been turned on, so the system's permission prompt only
 * appears once the lifter has asked for one.
 */
internal object IosNudgeScheduler : NudgeScheduler {
    /** Every streak reminder's identifier starts with this; nothing else the app posts does. */
    private const val PREFIX = "streak-"
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()
    private var asked = false

    override fun schedule(nudges: List<Nudge>) {
        if (nudges.isNotEmpty() && !asked) {
            asked = true
            // Asked while the app is open, where the prompt belongs; iOS keeps the answer.
            center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ -> }
        }
        center.getPendingNotificationRequestsWithCompletionHandler { pending ->
            val ours = pending.orEmpty().filterIsInstance<UNNotificationRequest>().map { it.identifier }.filter { it.startsWith(PREFIX) }
            if (ours.isNotEmpty()) center.removePendingNotificationRequestsWithIdentifiers(ours)
            post(nudges)
        }
    }

    private fun post(nudges: List<Nudge>) {
        val now = nowMs()
        for (nudge in nudges) {
            val seconds = (nudge.atEpochMs - now) / 1000.0
            if (seconds < 1.0) continue
            val content = UNMutableNotificationContent().apply {
                setTitle(nudge.title)
                setBody(nudge.body)
                // Worth a banner, not worth breaking through a Focus: this is a reminder, not an alarm.
                setInterruptionLevel(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive)
            }
            val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false)
            center.addNotificationRequest(
                UNNotificationRequest.requestWithIdentifier(PREFIX + nudge.id.removePrefix(PREFIX), content, trigger),
                withCompletionHandler = null,
            )
        }
    }
}
