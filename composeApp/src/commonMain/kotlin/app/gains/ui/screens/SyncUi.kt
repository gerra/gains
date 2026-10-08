package app.gains.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.gains.auth.Account
import app.gains.auth.AccountRepository
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.sync.SyncEngine
import app.gains.sync.SyncStatus
import app.gains.sync.SyncStore
import app.gains.ui.i18n.daysText
import app.gains.ui.i18n.minutesText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What the account card says about the sync. [SyncStatus] lives in memory and knows nothing of
 * the change log or of runs before this launch, so the card reads this instead: the engine's
 * status, how many documents wait to be pushed and when a run last went through (stored).
 */
internal sealed interface SyncUi {
    /** A guest, no account yet, or no server: nothing syncs, so the card says nothing about it. */
    data object Hidden : SyncUi
    data object Running : SyncUi
    /** Idle or done. [lastSyncedAt] is null until a run has gone through on this device for this account. */
    data class Synced(val lastSyncedAt: Instant?, val pending: Int) : SyncUi
    /** The last run failed (offline, a 5xx); the next occasion or "Sync now" tries again. */
    data object Failed : SyncUi
    /** The server answered 401: the token is no longer good, and only signing in again fixes it. */
    data object SignedOut : SyncUi
}

/** Maps the sync's pieces to what the card shows; pure, so the desktop tests check it. */
internal fun syncUi(enabled: Boolean, account: Account?, status: SyncStatus, pending: Long, lastSyncedAt: String?): SyncUi = when {
    !enabled || account == null || account.isGuest -> SyncUi.Hidden
    status is SyncStatus.Running -> SyncUi.Running
    status is SyncStatus.Failed -> if (status.signedOut) SyncUi.SignedOut else SyncUi.Failed
    else -> SyncUi.Synced(lastSyncedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }, pending.toInt())
}

/** The same, read live from the account, the engine and the change log: the account card, Home's banner and the top bar's dot. */
internal fun observeSyncUi(enabled: Boolean, accounts: AccountRepository, engine: SyncEngine, store: SyncStore): Flow<SyncUi> = combine(
    accounts.observeAccount(), engine.status, store.observePendingCount(), store.observeLastSyncedAt(),
) { account, status, pending, last -> syncUi(enabled, account, status, pending, last) }

/**
 * Something the lifter should know without opening Settings: the last run failed, so changes are
 * only on this device, or the server signed them out and nothing will sync until they sign in again.
 */
internal val SyncUi.needsAttention: Boolean get() = this is SyncUi.Failed || this is SyncUi.SignedOut

/** The card's sync line: "Synced 5 min ago · 3 changes waiting", "Syncing…" or what went wrong. */
@Composable
internal fun SyncStatusText(sync: SyncUi) {
    val error = sync is SyncUi.Failed || sync is SyncUi.SignedOut
    Text(
        when (sync) {
            SyncUi.Hidden -> ""
            SyncUi.Running -> stringResource(Res.string.syncing)
            SyncUi.Failed -> stringResource(Res.string.sync_failed)
            SyncUi.SignedOut -> stringResource(Res.string.sync_signed_out)
            is SyncUi.Synced -> {
                val synced = sync.lastSyncedAt?.let { syncedAgoText(it) } ?: stringResource(Res.string.not_synced_yet)
                if (sync.pending > 0) "$synced · ${pluralStringResource(Res.plurals.changes_waiting, sync.pending, sync.pending)}" else synced
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * "Synced 5 min ago", in the minutes and hours the rest of the app writes durations in, then in
 * days. Re-read every half minute so the line ages while Settings stays open.
 */
@Composable
private fun syncedAgoText(at: Instant): String {
    val now by produceState(Clock.System.now()) {
        while (true) {
            delay(30.seconds)
            value = Clock.System.now()
        }
    }
    return when (val ago = syncedAgo(now - at)) {
        is SyncedAgo.JustNow -> stringResource(Res.string.synced_just_now)
        is SyncedAgo.Minutes -> stringResource(Res.string.synced_ago, minutesText(ago.minutes))
        is SyncedAgo.Days -> stringResource(Res.string.synced_ago, daysText(ago.days))
    }
}

/** How long ago, rounded the way it is said: minutes under an hour, whole hours under a day, then days. */
internal sealed interface SyncedAgo {
    data object JustNow : SyncedAgo
    data class Minutes(val minutes: Int) : SyncedAgo
    data class Days(val days: Int) : SyncedAgo
}

internal fun syncedAgo(elapsed: Duration): SyncedAgo {
    val minutes = elapsed.inWholeMinutes
    return when {
        minutes < 1 -> SyncedAgo.JustNow
        minutes < 60 -> SyncedAgo.Minutes(minutes.toInt())
        minutes < 24 * 60 -> SyncedAgo.Minutes((minutes / 60 * 60).toInt())
        else -> SyncedAgo.Days((minutes / (24 * 60)).toInt())
    }
}
