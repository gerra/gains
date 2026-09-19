package app.gains.health

import app.gains.analysis.Dates.minusDays
import app.gains.data.BodyweightRepository
import app.gains.data.SettingsRepository
import app.gains.domain.BodyweightEntry
import app.gains.domain.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toLocalDateTime
import kotlin.math.round
import kotlin.time.Clock

/**
 * Keeps Gains and the health store in step.
 *
 * Workouts go one way, out: every session ended or logged in the app with a duration is recorded as
 * strength training, replaced when edited and removed when deleted. Nothing imported from a CSV is
 * sent, since the app it came from may have recorded it already.
 *
 * Weights go both ways. A weight entered in Gains is written to Health. Weights other apps and
 * scales put in Health become entries on the Body tab, marked as coming from Health, and those
 * entries follow Health: they update when the sample changes and go when it is deleted there. An
 * entry typed into Gains is never touched by a sync. See [reconcile] for the exact rule.
 *
 * The hooks ([workoutSaved] and friends) return at once and do their work on [scope], so a save
 * in the editor never waits for Health and a failure there never loses a workout.
 */
class HealthSync(
    private val store: HealthStore,
    private val settings: SettingsRepository,
    private val bodyweight: BodyweightRepository,
    private val now: () -> LocalDateTime = { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** Whether this device has a health store at all. Every screen hides the feature when not. */
    val isAvailable: Boolean get() = store.isAvailable

    /** The switches in Settings. Off until [connect] has run. */
    data class Prefs(
        val connected: Boolean = false,
        val workouts: Boolean = false,
        val bodyweight: Boolean = false,
        val lastSync: LocalDateTime? = null,
    ) {
        val writesWorkouts: Boolean get() = connected && workouts
        val syncsBodyweight: Boolean get() = connected && bodyweight
    }

    private val syncing = Mutex()

    fun observePrefs(): Flow<Prefs> = combine(
        settings.observe(KEY_CONNECTED), settings.observe(KEY_WORKOUTS), settings.observe(KEY_BODYWEIGHT), settings.observe(KEY_LAST_SYNC),
    ) { connected, workouts, bodyweight, lastSync ->
        Prefs(
            connected = connected == "1",
            workouts = workouts == "1",
            bodyweight = bodyweight == "1",
            lastSync = lastSync?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
        )
    }

    suspend fun prefs(): Prefs = observePrefs().first()

    /**
     * Asks the system for access, then turns both directions on and reads every weight already in
     * Health. False when there is no health store or the permission sheet could not be shown.
     */
    suspend fun connect(): Boolean {
        if (!store.isAvailable || !store.requestAccess()) return false
        settings.set(KEY_CONNECTED, "1")
        settings.set(KEY_WORKOUTS, "1")
        settings.set(KEY_BODYWEIGHT, "1")
        syncWeights(full = true)
        return true
    }

    /** Stops reading and writing. What was written stays, and the permissions stay in the Health app. */
    suspend fun disconnect() = settings.set(KEY_CONNECTED, "0")

    suspend fun setWorkouts(on: Boolean) = settings.set(KEY_WORKOUTS, if (on) "1" else "0")

    suspend fun setBodyweight(on: Boolean) {
        settings.set(KEY_BODYWEIGHT, if (on) "1" else "0")
        if (on) syncWeights(full = true)
    }

    /** Whether Health lets Gains save workouts: the one permission iOS reports. */
    suspend fun workoutPermission(): HealthPermission = store.canWrite(HealthKind.WORKOUTS)

    /**
     * Brings the weights in Health onto the Body tab and returns how many entries changed. A routine
     * sync reads the last [LOOKBACK_DAYS] days before the previous sync onwards: scale apps often
     * hand a morning weigh-in to Health hours later, and a weight can be added to Health for a past
     * day, so reading only what is newer than the last sync would miss both. A [full] sync reads
     * everything, which is what connecting does.
     */
    suspend fun syncWeights(full: Boolean = false): Int = syncing.withLock {
        val prefs = prefs()
        if (!store.isAvailable || !prefs.syncsBodyweight) return 0
        val since = if (full) null else prefs.lastSync?.let { it.date.minusDays(LOOKBACK_DAYS).atTime(0, 0) }
        val samples = runCatching { store.readWeights(since) }.getOrElse { return 0 }
        val changes = reconcile(bodyweight.entries(), samples, since?.date)
        bodyweight.upsertAll(changes.upserts)
        bodyweight.deleteAll(changes.deletes)
        settings.set(KEY_LAST_SYNC, now().toString())
        return changes.count
    }

    /**
     * [syncWeights] on the sync's own scope, for a screen opening: the screen neither waits for it
     * nor cancels it half-way by closing.
     */
    fun syncLater() = background { syncWeights() }

    /** A workout was stored or changed: record it in Health, in the background. */
    fun workoutSaved(session: Session) = background {
        val minutes = session.durationMinutes ?: return@background
        if (minutes <= 0 || !prefs().writesWorkouts) return@background
        store.saveWorkout(HealthWorkout(session.id, session.timestamp, minutes))
    }

    /** A workout was deleted: take Gains' record of it out of Health, whatever the switch says now. */
    fun workoutDeleted(sessionId: String) = background {
        if (prefs().connected) store.deleteWorkout(sessionId)
    }

    /** A weight was entered in Gains: write it to Health. Entries that came from Health are not sent back. */
    fun weightSaved(entry: BodyweightEntry) = background {
        if (!entry.fromHealth && prefs().syncsBodyweight) store.saveWeight(entry.date, entry.weightKg)
    }

    /** A weight was deleted in Gains: remove what Gains wrote to Health for that day. */
    fun weightDeleted(date: LocalDate) = background {
        if (prefs().connected) store.deleteWeight(date)
    }

    private fun background(work: suspend () -> Unit) {
        if (!store.isAvailable) return
        scope.launch { runCatching { work() } }
    }

    /** What a sync found to change. */
    data class Changes(val upserts: List<BodyweightEntry>, val deletes: List<LocalDate>) {
        val count: Int get() = upserts.size + deletes.size
    }

    companion object {
        const val KEY_CONNECTED = "health_connected"
        const val KEY_WORKOUTS = "health_workouts"
        const val KEY_BODYWEIGHT = "health_bodyweight"
        const val KEY_LAST_SYNC = "health_last_sync"
        const val LOOKBACK_DAYS = 30

        /**
         * Mirrors Health onto the Body tab for the days from [windowStart] on (every day when null).
         * Per day the latest sample not written by Gains counts. A day with no entry gets one marked
         * as from Health; an entry from Health follows its sample and is deleted when the sample is
         * gone; an entry typed into Gains is never changed. Pure, so the rule is unit-tested.
         */
        fun reconcile(existing: List<BodyweightEntry>, samples: List<HealthWeight>, windowStart: LocalDate?): Changes {
            val latest = samples.filter { !it.fromGains }.groupBy { it.date }.mapValues { (_, day) -> day.maxBy { it.at } }
            val byDate = existing.associateBy { it.date }
            val upserts = latest.values.sortedBy { it.date }.mapNotNull { sample ->
                val kg = roundKg(sample.weightKg)
                val current = byDate[sample.date]
                when {
                    current == null -> BodyweightEntry(sample.date, kg, BodyweightEntry.HEALTH)
                    current.fromHealth && current.weightKg != kg -> current.copy(weightKg = kg)
                    else -> null
                }
            }
            val deletes = existing
                .filter { it.fromHealth && (windowStart == null || it.date >= windowStart) && it.date !in latest }
                .map { it.date }
            return Changes(upserts, deletes)
        }

        /** Health keeps grams; the Body tab shows one decimal. Two decimals keep re-syncs from flickering. */
        fun roundKg(kg: Double): Double = round(kg * 100) / 100
    }
}
