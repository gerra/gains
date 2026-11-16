package app.gains

import app.gains.data.ExerciseRepository
import app.gains.data.LiveSessionRepository
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.ui.i18n.localName
import app.gains.ui.nowMs
import app.gains.watch.WatchCommands
import app.gains.watch.WatchJson
import app.gains.watch.WatchLink
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import platform.Foundation.NSError
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKWorkoutActivityTypeTraditionalStrengthTraining
import platform.HealthKit.HKWorkoutConfiguration
import platform.HealthKit.HKWorkoutSessionLocationTypeIndoor
import platform.HealthKit.startWatchAppWithWorkoutConfiguration
import platform.WatchConnectivity.WCSession
import platform.WatchConnectivity.WCSessionActivationStateActivated
import platform.WatchConnectivity.WCSessionDelegateProtocol
import platform.darwin.NSObject

/**
 * The iPhone's end of the Apple Watch link (docs/watch.md): WatchConnectivity carrying the JSON
 * of [WatchLink] both ways. Installed from the Swift app delegate as the app finishes launching,
 * so it also runs when the watch wakes the app in the background to deliver a tick, with no
 * screen up.
 *
 * Out: every [WatchState] goes into the application context, which the watch reads whenever it
 * wakes (only the latest one is kept, which is what a state is for), and, while the watch app is
 * open and reachable, also as a message so it lands at once. When a workout is started on the
 * phone, HealthKit is asked to open the watch app, which then runs its own workout session.
 *
 * In: a command from the watch is handed to the shared UI while it runs ([WatchCommands]), so it
 * reaches an open editor; otherwise it is applied to the stored workout here, and the state that
 * change produces goes back to the watch as any other would.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosWatchBridge {
    private const val STATE = "state"
    private const val COMMAND = "command"
    private const val HELLO = "hello"
    /** A workout that started longer ago than this was not just started: reopening the app must not wake the watch. */
    private const val JUST_STARTED_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Keep a strong reference: the session only holds its delegate weakly.
    private val delegate = Delegate()
    private val health by lazy { HKHealthStore() }
    private var link: WatchLink? = null
    /** The last state as JSON, sent again once the session activates and handed to a watch that asks. */
    private var latest: String? = null
    /** The workout the watch app was last asked to open for, so it is asked once per workout. */
    private var launchedFor: Long? = null

    private val session: WCSession? get() = if (WCSession.isSupported()) WCSession.defaultSession else null

    fun install() {
        val session = session ?: return
        if (link != null) return
        val koin = KoinPlatform.getKoin()
        val link = WatchLink(
            liveSessions = koin.get<LiveSessionRepository>(),
            sessions = koin.get<SessionRepository>(),
            exercises = koin.get<ExerciseRepository>(),
            settings = koin.get<SettingsRepository>(),
            programs = koin.get<ProgramRepository>(),
            names = { it.localName() },
        )
        this.link = link
        session.delegate = delegate
        session.activateSession()
        scope.launch {
            link.states().collect { state ->
                latest = WatchJson.encode(state)
                send()
                state.workout?.startedAtMs?.let(::openWatchApp)
            }
        }
    }

    private fun send() {
        val session = session ?: return
        val json = latest ?: return
        if (session.activationState != WCSessionActivationStateActivated || !session.paired || !session.watchAppInstalled) return
        session.updateApplicationContext(mapOf(STATE to json), null)
        if (session.reachable) session.sendMessage(mapOf(STATE to json), replyHandler = null, errorHandler = null)
    }

    /**
     * Wakes the watch app into the workout that just started on the phone. HealthKit does this
     * only for a paired watch with the app installed; anything else (no watch, an older watch,
     * HealthKit unavailable) fails quietly and the lifter opens the app on the watch by hand.
     */
    private fun openWatchApp(startedAtMs: Long) {
        if (launchedFor == startedAtMs) return
        launchedFor = startedAtMs
        val session = session ?: return
        if (nowMs() - startedAtMs > JUST_STARTED_MS || !session.paired || !session.watchAppInstalled) return
        if (!HKHealthStore.isHealthDataAvailable()) return
        val configuration = HKWorkoutConfiguration().apply {
            activityType = HKWorkoutActivityTypeTraditionalStrengthTraining
            locationType = HKWorkoutSessionLocationTypeIndoor
        }
        health.startWatchAppWithWorkoutConfiguration(configuration) { _, _ -> }
    }

    /** A command from the watch, on the main thread: to the shared UI while it listens, else to the database. */
    private fun receive(message: Map<Any?, *>) {
        val text = message[COMMAND] as? String ?: return
        val command = WatchJson.decodeCommand(text) ?: return
        scope.launch {
            if (!WatchCommands.offer(command)) link?.apply(command)
        }
    }

    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }

    private class Delegate : NSObject(), WCSessionDelegateProtocol {
        override fun session(session: WCSession, activationDidCompleteWithState: Long, error: NSError?) = onMain { send() }

        // Called on iOS when the lifter switches to another watch; activating again picks up the new one.
        override fun sessionDidBecomeInactive(session: WCSession) = Unit
        override fun sessionDidDeactivate(session: WCSession) = session.activateSession()

        override fun sessionWatchStateDidChange(session: WCSession) = onMain { send() }
        override fun sessionReachabilityDidChange(session: WCSession) = onMain { send() }

        @ObjCSignatureOverride
        override fun session(session: WCSession, didReceiveMessage: Map<Any?, *>) = onMain { receive(didReceiveMessage) }

        /** The watch asking for the state as it opens ([HELLO]) or sending a command and waiting for the answer. */
        override fun session(session: WCSession, didReceiveMessage: Map<Any?, *>, replyHandler: (Map<Any?, *>?) -> Unit) {
            onMain {
                if (didReceiveMessage[HELLO] == null) receive(didReceiveMessage)
                replyHandler(latest?.let { mapOf(STATE to it) } ?: emptyMap<Any?, Any?>())
            }
        }

        /** A command queued while the phone was out of reach, delivered once it is back. */
        @ObjCSignatureOverride
        override fun session(session: WCSession, didReceiveUserInfo: Map<Any?, *>) = onMain { receive(didReceiveUserInfo) }
    }
}
