package app.gains

import app.gains.platform.LiveSessionNotice
import app.gains.platform.LiveSessionNotifier
import app.gains.platform.ResumeRequests
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.nowMs
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * The running workout in Notification Centre while the lifter is in another app: its name, when
 * it started and how long it has run, the end of the rest if one is counting down, and a tap that
 * brings it back. Posted (quietly: no banner, no sound) each time the app goes to the background
 * and taken down each time it comes to the front, so it is there whenever the app is left, even
 * after a tap on it, which iOS clears. Notification Centre cannot count, so a rest is turned
 * into a second, scheduled notification: "Rest over", delivered by iOS when the countdown ends
 * whether or not the app is still awake. Everything is cleared when the session is ended or
 * discarded. iOS asks for permission on the first workout.
 *
 * The centre's delegate has to be in place before the app finishes launching for a tap that cold
 * starts the app to reach it, so the SwiftUI app delegate calls [install] first thing.
 */
internal object IosLiveSessionNotifier : LiveSessionNotifier {
    private const val RUNNING = "live_session"
    private const val REST_OVER = "live_session_rest_over"
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()
    // Keep a strong reference: the centre only holds its delegate weakly.
    private val delegate = Delegate()
    /** The notice as the shared UI last sent it: what goes up when the app is left. */
    private var notice: LiveSessionNotice? = null
    private var inBackground = false
    private var asked = false

    fun install() {
        center.delegate = delegate
        val lifecycle = NSNotificationCenter.defaultCenter
        lifecycle.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            inBackground = true
            notice?.let(::post)
        }
        lifecycle.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            inBackground = false
            clear()
        }
    }

    override fun update(notice: LiveSessionNotice?) {
        this.notice = notice
        if (notice == null) { clear(); return }
        // Asked while the lifter is in the app, where the prompt can be shown; iOS keeps the answer.
        if (!asked) {
            asked = true
            center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert) { _, _ -> }
        }
        // A change in the moment before the app is suspended (a rest ending, say) is put up straight away.
        if (inBackground) post(notice)
    }

    private fun clear() {
        center.removeDeliveredNotificationsWithIdentifiers(listOf(RUNNING, REST_OVER))
        center.removePendingNotificationRequestsWithIdentifiers(listOf(REST_OVER))
    }

    private fun post(notice: LiveSessionNotice) {
        val now = nowMs()
        val restEndsAt = notice.restEndsAtMs?.takeIf { it > now }
        val running = UNMutableNotificationContent().apply {
            setTitle(notice.title)
            setBody(buildString {
                if (restEndsAt != null) append(str(Res.string.resting_until, clock(restEndsAt)))
                append(str(Res.string.running_since, clock(notice.startedAtMs), elapsed(now - notice.startedAtMs)))
            })
            setInterruptionLevel(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelPassive)
        }
        // No trigger: delivered straight away. Same identifier: a re-post replaces the last one.
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(RUNNING, running, null), withCompletionHandler = null)
        center.removePendingNotificationRequestsWithIdentifiers(listOf(REST_OVER))
        if (restEndsAt == null) return
        val over = UNMutableNotificationContent().apply {
            setTitle(str(Res.string.rest_over))
            setBody(str(Res.string.rest_over_body, notice.title, elapsed(restEndsAt - notice.startedAtMs)))
            setInterruptionLevel(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive)
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(((restEndsAt - now) / 1000.0).coerceAtLeast(1.0), repeats = false)
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(REST_OVER, over, trigger), withCompletionHandler = null)
    }

    /** "14:05", in the device's format. */
    private fun clock(ms: Long): String = NSDateFormatter().apply {
        dateStyle = NSDateFormatterNoStyle
        timeStyle = NSDateFormatterShortStyle
    }.stringFromDate(NSDate.dateWithTimeIntervalSince1970(ms / 1000.0))

    /** "under a minute", "23 min", "1 h 05 min". */
    private fun elapsed(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(0)
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 -> "$h ${str(Res.string.hour_abbrev)} ${m.toString().padStart(2, '0')} ${str(Res.string.minute_abbrev)}"
            m > 0 -> "$m ${str(Res.string.minute_abbrev)}"
            else -> str(Res.string.under_a_minute)
        }
    }

    /** A string resource in the device's language, read outside the composition. */
    private fun str(res: StringResource, vararg args: Any): String = runBlocking { getString(res, *args) }

    private class Delegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
        /** Delivered while the app is open: into the list, with no banner over the workout. */
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
        ) {
            withCompletionHandler(UNNotificationPresentationOptionList)
        }

        /** Tapped: back to the workout. */
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            didReceiveNotificationResponse: UNNotificationResponse,
            withCompletionHandler: () -> Unit,
        ) {
            if (didReceiveNotificationResponse.notification.request.identifier in setOf(RUNNING, REST_OVER)) ResumeRequests.request()
            withCompletionHandler()
        }
    }
}
