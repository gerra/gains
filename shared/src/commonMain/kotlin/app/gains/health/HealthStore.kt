package app.gains.health

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * The device's health store: Apple Health on iOS, nothing anywhere else. Gains writes the workouts it
 * logs and the weights entered in the app, and reads the weights that scales and other apps put there.
 *
 * The interface is plain Kotlin so the sync rules in [HealthSync] are unit-tested on the JVM; the
 * HealthKit calls live in `IosHealthStore` in the iOS source set.
 */
interface HealthStore {
    /** False where there is no health store (desktop, Android, some iPads): the feature is hidden. */
    val isAvailable: Boolean

    /**
     * Shows the system permission sheet for workouts and body mass. True when the sheet was shown and
     * dismissed, whatever was chosen on it: iOS never says whether reading was allowed, only writing.
     */
    suspend fun requestAccess(): Boolean

    /** Whether Gains may write [kind]. [HealthPermission.UNKNOWN] until [requestAccess] has run. */
    suspend fun canWrite(kind: HealthKind): HealthPermission

    /** Records a strength workout. A record Gains made earlier for the same session is replaced. */
    suspend fun saveWorkout(workout: HealthWorkout)

    /** Removes the workout Gains recorded for [sessionId], if there is one. */
    suspend fun deleteWorkout(sessionId: String)

    /** Every body mass sample taken at or after [since] (all of them when null), oldest first. */
    suspend fun readWeights(since: LocalDateTime?): List<HealthWeight>

    /** Records a weigh-in. Gains' own samples for that day are replaced; other apps' are left alone. */
    suspend fun saveWeight(date: LocalDate, weightKg: Double)

    /** Removes Gains' own samples for that day. Samples from other apps cannot be removed. */
    suspend fun deleteWeight(date: LocalDate)

    /** Platforms without a health store. */
    object None : HealthStore {
        override val isAvailable: Boolean get() = false
        override suspend fun requestAccess(): Boolean = false
        override suspend fun canWrite(kind: HealthKind): HealthPermission = HealthPermission.DENIED
        override suspend fun saveWorkout(workout: HealthWorkout) {}
        override suspend fun deleteWorkout(sessionId: String) {}
        override suspend fun readWeights(since: LocalDateTime?): List<HealthWeight> = emptyList()
        override suspend fun saveWeight(date: LocalDate, weightKg: Double) {}
        override suspend fun deleteWeight(date: LocalDate) {}
    }
}

/** What Gains asks to write. Reading is only ever body mass. */
enum class HealthKind { WORKOUTS, BODY_MASS }

enum class HealthPermission { UNKNOWN, DENIED, GRANTED }

/** A strength workout as Health records it: when it ran and for how long, not what was lifted. */
data class HealthWorkout(val sessionId: String, val start: LocalDateTime, val durationMinutes: Int)

/**
 * One body mass sample in local time. [fromGains] marks the ones Gains wrote itself, which are never
 * read back in: the entry they came from is already on the Body tab.
 */
data class HealthWeight(val at: LocalDateTime, val weightKg: Double, val fromGains: Boolean = false) {
    val date: LocalDate get() = at.date
}
