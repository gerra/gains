package app.gains.root

import app.gains.data.LiveSessionRepository
import app.gains.platform.LiveSessionNotice
import app.gains.platform.LiveSessionNotifier
import app.gains.platform.NoticeRequests
import app.gains.platform.ResumeRequests
import app.gains.platform.SkipRestRequests
import app.gains.ui.nav.NavEntry
import app.gains.ui.nav.Navigator
import app.gains.ui.nav.Screen
import app.gains.ui.nowMs
import app.gains.ui.screens.SessionEditorModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The platform's workout notice ([LiveSessionNotifier]) kept in step with the workout in progress,
 * and its two buttons carried out: [resumes] opens the running workout, [skips] drops its rest.
 * A class of its own rather than effects in the root, so these rules are tested without composing
 * the app. The root makes one per composition and [run]s it from a `LaunchedEffect`, so nothing is
 * posted, and no request is taken, outside the app's lifetime; while [run] runs, both request
 * counters are collected, which is what makes them [NoticeRequests.attended].
 *
 * [now] is the wall clock a rest's end is measured against; tests pass virtual time. [skipRestIn]
 * asks the editor an entry holds, if any, to skip the rest and says whether it did: only the editor
 * running the workout takes it, and a past workout's may be open too.
 */
internal class LiveSessionNotices(
    private val liveSessions: LiveSessionRepository,
    private val notifier: LiveSessionNotifier,
    private val navigator: Navigator,
    private val resumes: NoticeRequests = ResumeRequests,
    private val skips: NoticeRequests = SkipRestRequests,
    private val now: () -> Long = ::nowMs,
    private val skipRestIn: (NavEntry) -> Boolean = { it.peek(SessionEditorModel::class)?.skipRest() == true },
) {
    /** Mirrors the workout into the notice and answers its buttons until cancelled. */
    suspend fun run(): Unit = coroutineScope {
        launch { mirror() }
        // A request that comes while the one before is still being carried out replaces it.
        launch { resumes.pending.filter { it != 0 }.collectLatest { resume() } }
        launch { skips.pending.filter { it != 0 }.collectLatest { skipRest() } }
    }

    /**
     * Only what the notice shows is watched, so typing a weight does not re-post it, and a rest
     * countdown is re-posted without one once it is over.
     */
    private suspend fun mirror() {
        liveSessions.observe()
            .map { it?.let { s -> LiveSessionNotice(s.title, s.startedAtMs, s.rest?.endsAtMs) } }
            .distinctUntilChanged()
            .collectLatest { notice ->
                val restEnds = notice?.restEndsAtMs
                if (restEnds != null && restEnds > now()) {
                    notifier.update(notice)
                    delay(restEnds - now())
                }
                notifier.update(notice?.copy(restEndsAtMs = null))
            }
    }

    /**
     * A tap on the notice: open the running workout once the database has said there is one, so a tap
     * that launched the app is honoured too; not a second time when its editor is already on top.
     */
    private suspend fun resume() {
        val running = liveSessions.observe().first()
        resumes.consume()
        val current = navigator.current
        if (running != null && !(current is Screen.EditSession && current.live)) {
            navigator.push(Screen.EditSession(null, running.program, live = true))
        }
    }

    /**
     * "Skip rest" on the notice: the editor running the workout drops it when there is one, covered
     * or not (its next persist reaches the database and, through it, the notice); otherwise the
     * database is changed directly.
     */
    private suspend fun skipRest() {
        skips.consume()
        if (navigator.stack.none(skipRestIn)) liveSessions.clearRest()
    }
}
