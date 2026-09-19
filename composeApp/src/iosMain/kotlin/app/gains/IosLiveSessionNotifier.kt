package app.gains

import app.gains.platform.LiveSessionNotice
import app.gains.platform.LiveSessionNotifier
import app.gains.platform.ResumeRequests
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * The running workout as a notification in Notification Centre, so it is a pull-down away while
 * the lifter is in another app: its name, when it started, and a tap that brings it back. Posted
 * quietly (no banner or sound, since the lifter is in the app when it starts) and removed when the
 * session is ended or discarded. iOS asks for permission on the first workout.
 *
 * The centre's delegate has to be in place before the app finishes launching for a tap that cold
 * starts the app to reach it, so the SwiftUI app delegate calls [install] first thing.
 */
object IosLiveSessionNotifier : LiveSessionNotifier {
    private const val IDENTIFIER = "live_session"
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()
    // Keep a strong reference: the centre only holds its delegate weakly.
    private val delegate = Delegate()
    private var shown: LiveSessionNotice? = null

    fun install() {
        center.delegate = delegate
    }

    override fun update(notice: LiveSessionNotice?) {
        if (notice == null) {
            shown = null
            center.removeDeliveredNotificationsWithIdentifiers(listOf(IDENTIFIER))
            center.removePendingNotificationRequestsWithIdentifiers(listOf(IDENTIFIER))
            return
        }
        // Notification Centre cannot count, so the rest is not shown; only a new workout needs a post.
        val plain = notice.copy(restEndsAtMs = null)
        if (plain == shown) return
        shown = plain
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert) { granted, _ ->
            if (granted) post(plain)
        }
    }

    private fun post(notice: LiveSessionNotice) {
        val startedAt = NSDateFormatter().apply {
            dateStyle = NSDateFormatterNoStyle
            timeStyle = NSDateFormatterShortStyle
        }.stringFromDate(NSDate.dateWithTimeIntervalSince1970(notice.startedAtMs / 1000.0))
        val content = UNMutableNotificationContent().apply {
            setTitle(notice.title)
            setBody("Started $startedAt. Tap to get back to your workout.")
            setInterruptionLevel(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelPassive)
        }
        // No trigger: delivered straight away. Same identifier: a re-post replaces the last one.
        val request = UNNotificationRequest.requestWithIdentifier(IDENTIFIER, content, null)
        center.addNotificationRequest(request, withCompletionHandler = null)
    }

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
            if (didReceiveNotificationResponse.notification.request.identifier == IDENTIFIER) ResumeRequests.request()
            withCompletionHandler()
        }
    }
}
