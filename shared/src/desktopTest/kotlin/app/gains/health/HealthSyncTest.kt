package app.gains.health

import app.gains.data.BodyweightRepository
import app.gains.data.DesktopDriverFactory
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.domain.BodyweightEntry
import app.gains.domain.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rules that keep the Body tab and the health store in step, against a fake store and a real in-memory database. */
class HealthSyncTest {
    private class FakeStore(override val isAvailable: Boolean = true) : HealthStore {
        var samples: List<HealthWeight> = emptyList()
        var accessRequested = false
        var reads = 0
        var lastReadSince: LocalDateTime? = null
        val savedWorkouts = mutableListOf<HealthWorkout>()
        val deletedWorkouts = mutableListOf<String>()
        val savedWeights = mutableListOf<Pair<LocalDate, Double>>()
        val deletedWeights = mutableListOf<LocalDate>()

        override suspend fun requestAccess(): Boolean { accessRequested = true; return true }
        override suspend fun canWrite(kind: HealthKind): HealthPermission = HealthPermission.GRANTED
        override suspend fun saveWorkout(workout: HealthWorkout) { savedWorkouts += workout }
        override suspend fun deleteWorkout(sessionId: String) { deletedWorkouts += sessionId }
        override suspend fun readWeights(since: LocalDateTime?): List<HealthWeight> {
            reads++
            lastReadSince = since
            return samples.filter { since == null || it.at >= since }
        }
        override suspend fun saveWeight(date: LocalDate, weightKg: Double) { savedWeights += date to weightKg }
        override suspend fun deleteWeight(date: LocalDate) { deletedWeights += date }
    }

    private val now = LocalDateTime(2026, 9, 19, 9, 0)
    private val d1 = LocalDate(2026, 9, 1)
    private val d2 = LocalDate(2026, 9, 2)
    private val d3 = LocalDate(2026, 9, 3)

    private class Harness(val sync: HealthSync, val bodyweight: BodyweightRepository, val settings: SettingsRepository)

    private fun harness(store: HealthStore, scope: CoroutineScope): Harness {
        val db = GainsDatabase(DesktopDriverFactory(file = null).createDriver())
        val settings = SettingsRepository(db, Dispatchers.Unconfined)
        val bodyweight = BodyweightRepository(db, Dispatchers.Unconfined)
        return Harness(HealthSync(store, settings, bodyweight, now = { now }, scope = scope), bodyweight, settings)
    }

    private fun at(date: LocalDate, hour: Int) = LocalDateTime(date.year, date.month, date.day, hour, 0)

    // The pure rule.

    @Test
    fun latestSampleOfEachDayComesInAndGainsOwnSamplesDoNot() {
        val samples = listOf(
            HealthWeight(at(d1, 7), 80.123),
            HealthWeight(at(d1, 19), 80.6),
            HealthWeight(at(d2, 8), 81.0, fromGains = true),
        )
        val changes = HealthSync.reconcile(emptyList(), samples, windowStart = null)
        assertEquals(listOf(BodyweightEntry(d1, 80.6, BodyweightEntry.HEALTH)), changes.upserts)
        assertTrue(changes.deletes.isEmpty())
    }

    @Test
    fun weightsTypedIntoGainsAreNeverTouched() {
        val existing = listOf(BodyweightEntry(d1, 79.0), BodyweightEntry(d2, 79.5))
        val samples = listOf(HealthWeight(at(d1, 7), 80.0))
        val changes = HealthSync.reconcile(existing, samples, windowStart = null)
        assertEquals(0, changes.count, "a manual entry is not overwritten, and one without a sample is not deleted")
    }

    @Test
    fun entriesFromHealthFollowHealthInsideTheWindow() {
        val existing = listOf(
            BodyweightEntry(d1, 80.0, BodyweightEntry.HEALTH),   // sample changed: updated
            BodyweightEntry(d2, 80.5, BodyweightEntry.HEALTH),   // sample gone: deleted
            BodyweightEntry(LocalDate(2026, 8, 1), 82.0, BodyweightEntry.HEALTH),   // before the window: left alone
        )
        val samples = listOf(HealthWeight(at(d1, 7), 80.25), HealthWeight(at(d3, 7), 80.75))
        val changes = HealthSync.reconcile(existing, samples, windowStart = d1)
        assertEquals(listOf(BodyweightEntry(d1, 80.25, BodyweightEntry.HEALTH), BodyweightEntry(d3, 80.75, BodyweightEntry.HEALTH)), changes.upserts)
        assertEquals(listOf(d2), changes.deletes)
    }

    @Test
    fun unchangedEntriesAreNotRewritten() {
        val existing = listOf(BodyweightEntry(d1, 80.6, BodyweightEntry.HEALTH))
        val changes = HealthSync.reconcile(existing, listOf(HealthWeight(at(d1, 7), 80.6)), windowStart = null)
        assertEquals(0, changes.count)
    }

    // The coordinator.

    @Test
    fun nothingHappensUntilConnected() = runTest {
        val store = FakeStore().apply { samples = listOf(HealthWeight(at(d1, 7), 80.0)) }
        val h = harness(store, this)
        assertEquals(0, h.sync.syncWeights())
        h.sync.workoutSaved(Session("s1", at(d1, 18), 45, emptyList(), Session.MANUAL))
        h.sync.weightSaved(BodyweightEntry(d1, 80.0))
        advanceUntilIdle()
        assertEquals(0, store.reads)
        assertTrue(store.savedWorkouts.isEmpty())
        assertTrue(store.savedWeights.isEmpty())
        assertTrue(h.bodyweight.entries().isEmpty())
    }

    @Test
    fun connectingAsksOnceTurnsBothOnAndReadsEverything() = runTest {
        val store = FakeStore().apply { samples = listOf(HealthWeight(at(d1, 7), 80.0), HealthWeight(at(d2, 7), 80.4)) }
        val h = harness(store, this)
        assertTrue(h.sync.connect())
        assertTrue(store.accessRequested)
        val prefs = h.sync.prefs()
        assertTrue(prefs.connected && prefs.workouts && prefs.bodyweight)
        assertEquals(now, prefs.lastSync)
        assertEquals(null, store.lastReadSince, "the first read takes every sample")
        assertEquals(listOf(BodyweightEntry(d1, 80.0, BodyweightEntry.HEALTH), BodyweightEntry(d2, 80.4, BodyweightEntry.HEALTH)), h.bodyweight.entries())
    }

    @Test
    fun aRoutineSyncLooksBackThirtyDaysFromTheLastOne() = runTest {
        val store = FakeStore()
        val h = harness(store, this)
        h.sync.connect()
        h.settings.set(HealthSync.KEY_LAST_SYNC, LocalDateTime(2026, 9, 10, 8, 0).toString())
        h.sync.syncWeights()
        assertEquals(LocalDateTime(2026, 8, 11, 0, 0), store.lastReadSince)
        assertEquals(now, h.sync.prefs().lastSync)
    }

    @Test
    fun noHealthStoreMeansNoSyncEvenWhenConnected() = runTest {
        val store = FakeStore(isAvailable = false)
        val h = harness(store, this)
        assertFalse(h.sync.connect())
        assertFalse(store.accessRequested)
        assertFalse(h.sync.prefs().connected)
    }

    @Test
    fun finishedWorkoutsAreRecordedAndEditsReplaceThem() = runTest {
        val store = FakeStore()
        val h = harness(store, this)
        h.sync.connect()
        h.sync.workoutSaved(Session("s1", at(d1, 18), 45, emptyList(), Session.MANUAL))
        h.sync.workoutSaved(Session("s2", at(d2, 18), null, emptyList(), Session.MANUAL))   // no duration: no end time, not recorded
        advanceUntilIdle()
        assertEquals(listOf(HealthWorkout("s1", at(d1, 18), 45)), store.savedWorkouts)

        h.sync.workoutDeleted("s1")
        advanceUntilIdle()
        assertEquals(listOf("s1"), store.deletedWorkouts)
    }

    @Test
    fun theWorkoutSwitchStopsWritesButNotDeletes() = runTest {
        val store = FakeStore()
        val h = harness(store, this)
        h.sync.connect()
        h.sync.setWorkouts(false)
        h.sync.workoutSaved(Session("s1", at(d1, 18), 45, emptyList(), Session.MANUAL))
        h.sync.workoutDeleted("s0")
        advanceUntilIdle()
        assertTrue(store.savedWorkouts.isEmpty())
        assertEquals(listOf("s0"), store.deletedWorkouts)
    }

    @Test
    fun weightsTypedInGoToHealthAndOnesFromHealthDoNotGoBack() = runTest {
        val store = FakeStore()
        val h = harness(store, this)
        h.sync.connect()
        h.sync.weightSaved(BodyweightEntry(d1, 80.0))
        h.sync.weightSaved(BodyweightEntry(d2, 80.5, BodyweightEntry.HEALTH))
        h.sync.weightDeleted(d3)
        advanceUntilIdle()
        assertEquals(listOf(d1 to 80.0), store.savedWeights)
        assertEquals(listOf(d3), store.deletedWeights)
    }

    @Test
    fun disconnectingStopsEverythingAndKeepsTheEntries() = runTest {
        val store = FakeStore().apply { samples = listOf(HealthWeight(at(d1, 7), 80.0)) }
        val h = harness(store, this)
        h.sync.connect()
        assertEquals(1, h.bodyweight.entries().size)
        h.sync.disconnect()
        assertEquals(0, h.sync.syncWeights())
        h.sync.weightSaved(BodyweightEntry(d2, 81.0))
        advanceUntilIdle()
        assertTrue(store.savedWeights.isEmpty())
        assertEquals(1, h.bodyweight.entries().size)
    }
}
