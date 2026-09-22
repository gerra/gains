package app.gains.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the last run did, for the settings screen and the tests. */
data class SyncOutcome(val pushed: Int, val pulled: Int, val rejected: Int)

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus
    data class Done(val at: String, val outcome: SyncOutcome) : SyncStatus
    data class Failed(val at: String, val message: String, val signedOut: Boolean) : SyncStatus
}

/**
 * One sync: push what this device changed, then pull what the others did. Both halves are
 * idempotent and either can be repeated after a failure; a run never overlaps another.
 * docs/sync.md describes the rules; [SyncStore] holds the device's side, [SyncApi] the server's.
 */
class SyncEngine(
    private val store: SyncStore,
    private val api: SyncApi,
) {
    private val lock = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status

    suspend fun sync(): SyncOutcome = lock.withLock {
        _status.value = SyncStatus.Running
        try {
            val (pushed, rejected) = push()
            val pulled = pull()
            val at = SyncStore.now()
            // Stored as well, so "Synced 5 min ago" survives a restart; the status itself is in memory.
            store.setLastSyncedAt(at)
            SyncOutcome(pushed, pulled, rejected).also { _status.value = SyncStatus.Done(at, it) }
        } catch (e: Exception) {
            _status.value = SyncStatus.Failed(SyncStore.now(), e.message ?: e.toString(), (e as? SyncException)?.unauthorized == true)
            throw e
        }
    }

    /** Returns how many documents went up and how many the server declined for being older. */
    private suspend fun push(): Pair<Int, Int> {
        var pushed = 0
        var rejected = 0
        val pending = store.pendingChanges()
        // Photos go one at a time through the blob route; everything else in batches.
        val (photos, documents) = pending.partition { it.kind == SyncKinds.SESSION_PHOTO && !it.deleted }
        for (batch in documents.chunked(BATCH)) {
            val docs = batch.map { store.load(it) }
            val response = api.push(docs)
            val accepted = response.results.filter { it.accepted }.map { it.kind to it.id }.toSet()
            for (change in batch) {
                if ((change.kind to change.id) in accepted) pushed++ else rejected++
                // Declined or not, this version is dealt with: the pull brings the newer one.
                store.clearPushed(change)
            }
        }
        for (change in photos) {
            val bytes = store.photo(change.id)
            if (bytes == null) {
                // Gone since the trigger fired; the delete trigger will have left a tombstone.
                store.clearPushed(change)
                continue
            }
            try {
                api.putBlob(SyncKinds.SESSION_PHOTO, change.id, change.changedAt, bytes)
                pushed++
            } catch (e: SyncException) {
                // A newer photo is stored: this one is dealt with the same way a declined document is.
                if (e.status != 409) throw e
                rejected++
            }
            store.clearPushed(change)
        }
        return pushed to rejected
    }

    /** Returns how many documents came down. Each page is applied and its cursor stored before the next is asked for. */
    private suspend fun pull(): Int {
        var pulled = 0
        var cursor = store.cursor()
        while (true) {
            val page = api.pull(cursor)
            if (page.documents.isEmpty() && page.cursor <= cursor) break
            val photos = HashMap<String, ByteArray>()
            for (doc in page.documents) {
                if (doc.kind == SyncKinds.SESSION_PHOTO && !doc.deleted) {
                    api.getBlob(SyncKinds.SESSION_PHOTO, doc.id)?.let { photos[doc.id] = it }
                }
            }
            store.applyPage(page.documents, photos, page.cursor)
            pulled += page.documents.size
            cursor = page.cursor
            if (!page.more) break
        }
        return pulled
    }

    companion object {
        const val BATCH = 200
    }
}
