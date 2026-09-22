package app.gains.sync

import app.gains.auth.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * When to sync: a couple of seconds after the change log last grew, whenever the app is brought
 * to the front ([requestSync]) and right after a sign-in. Nothing runs for a guest or while the
 * server is not configured. A failed run is simply retried at the next occasion; the status is
 * on [SyncEngine.status] for the settings screen.
 */
class SyncController(
    private val engine: SyncEngine,
    private val store: SyncStore,
    private val accounts: AccountRepository,
    private val enabled: Boolean,
) {
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Asks for a run soon: the app came to the foreground, or the person tapped "Sync now". */
    fun requestSync() {
        requests.tryEmit(Unit)
    }

    /** Runs until [scope] is cancelled. Launched once, from the app's root. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (!enabled) return
        scope.launch {
            val signedIn = accounts.observeAccount().map { it != null && !it.isGuest }.distinctUntilChanged()
            val changes = store.observePendingCount().filter { it > 0 }.map { }
            val triggers = merge(changes, requests, signedIn.filter { it }.map { })
            combine(signedIn, triggers) { on, _ -> on }
                .filter { it }
                .debounce(2.seconds)
                .collect {
                    try {
                        syncWithFreshToken()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Reported on the engine's status; the next occasion tries again.
                    }
                }
        }
    }

    /** Refreshes a token older than a week before syncing, so a device used daily never sees it expire. */
    private suspend fun syncWithFreshToken() {
        val issued = store.tokenIssuedAt()?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (issued == null || kotlin.time.Clock.System.now() - issued > 7.days) {
            try {
                accounts.refreshToken()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A refresh that fails leaves the old token in place; a 401 on the sync itself is what signs out.
            }
        }
        engine.sync()
    }
}
