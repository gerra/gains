package app.gains

import app.gains.auth.Account
import app.gains.auth.AccountKind
import app.gains.sync.SyncOutcome
import app.gains.sync.SyncStatus
import app.gains.ui.screens.SyncUi
import app.gains.ui.screens.SyncedAgo
import app.gains.ui.screens.syncUi
import app.gains.ui.screens.syncedAgo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The account card's sync line: which state it shows for each mix of status, change log and stored time. */
class SyncUiTest {
    private val me = Account(AccountKind.APPLE, "Me", "me@x.y")
    private val at = "2026-09-22T10:00:00.000Z"

    @Test
    fun nothingShowsWithoutASignedInAccountAndAServer() {
        assertEquals(SyncUi.Hidden, syncUi(enabled = false, me, SyncStatus.Idle, 0, at))
        assertEquals(SyncUi.Hidden, syncUi(enabled = true, null, SyncStatus.Idle, 0, at))
        assertEquals(SyncUi.Hidden, syncUi(enabled = true, Account(AccountKind.GUEST), SyncStatus.Idle, 3, at))
    }

    @Test
    fun aRunningSyncSaysSoWhateverWaits() {
        assertEquals(SyncUi.Running, syncUi(enabled = true, me, SyncStatus.Running, 3, at))
    }

    @Test
    fun idleOrDoneShowsTheStoredTimeAndWhatWaits() {
        val synced = SyncUi.Synced(Instant.parse(at), pending = 0)
        // After a restart the status is Idle, but the stored time still says when.
        assertEquals(synced, syncUi(enabled = true, me, SyncStatus.Idle, 0, at))
        assertEquals(synced, syncUi(enabled = true, me, SyncStatus.Done(at, SyncOutcome(1, 0, 0)), 0, at))
        assertEquals(SyncUi.Synced(Instant.parse(at), pending = 3), syncUi(enabled = true, me, SyncStatus.Idle, 3, at))
        assertEquals(SyncUi.Synced(null, pending = 2), syncUi(enabled = true, me, SyncStatus.Idle, 2, null))
    }

    @Test
    fun aFailureIsARetryUnlessTheServerSignedUsOut() {
        assertEquals(SyncUi.Failed, syncUi(enabled = true, me, SyncStatus.Failed(at, "offline", signedOut = false), 3, at))
        assertEquals(SyncUi.SignedOut, syncUi(enabled = true, me, SyncStatus.Failed(at, "401", signedOut = true), 3, at))
    }

    @Test
    fun agesAreRoundedTheWayTheyAreSaid() {
        assertEquals(SyncedAgo.JustNow, syncedAgo(40.seconds))
        assertEquals(SyncedAgo.Minutes(5), syncedAgo(5.minutes + 30.seconds))
        assertEquals(SyncedAgo.Minutes(59), syncedAgo(59.minutes))
        assertEquals(SyncedAgo.Minutes(120), syncedAgo(2.hours + 40.minutes))
        assertEquals(SyncedAgo.Days(3), syncedAgo(3.days + 5.hours))
        // A clock that moved back reads as just now rather than a negative age.
        assertEquals(SyncedAgo.JustNow, syncedAgo((-3).minutes))
    }
}
