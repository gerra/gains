package app.gains.health

import app.gains.analysis.Dates.plusDays
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toKotlinInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toNSDate
import platform.Foundation.NSBundle
import platform.Foundation.NSCompoundPredicate
import platform.Foundation.NSDate
import platform.Foundation.NSPredicate
import platform.Foundation.NSSortDescriptor
import platform.HealthKit.HKAuthorizationStatusSharingAuthorized
import platform.HealthKit.HKAuthorizationStatusSharingDenied
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKMetadataKeyExternalUUID
import platform.HealthKit.HKMetadataKeyIndoorWorkout
import platform.HealthKit.HKMetricPrefixKilo
import platform.HealthKit.HKObject
import platform.HealthKit.HKObjectQueryNoLimit
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKQuantity
import platform.HealthKit.HKQuantitySample
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKQuantityTypeIdentifierBodyMass
import platform.HealthKit.HKQuery
import platform.HealthKit.HKQueryOptionNone
import platform.HealthKit.HKSample
import platform.HealthKit.HKSampleQuery
import platform.HealthKit.HKSampleSortIdentifierStartDate
import platform.HealthKit.HKSampleType
import platform.HealthKit.HKSource
import platform.HealthKit.HKUnit
import platform.HealthKit.HKWorkoutActivityTypeTraditionalStrengthTraining
import platform.HealthKit.HKWorkoutBuilder
import platform.HealthKit.HKWorkoutConfiguration
import platform.HealthKit.HKWorkoutSessionLocationTypeIndoor
// Class methods that HealthKit declares in categories arrive as extensions on the companion objects.
import platform.HealthKit.gramUnitWithMetricPrefix
import platform.HealthKit.predicateForObjectsFromSource
import platform.HealthKit.predicateForObjectsWithMetadataKey
import platform.HealthKit.predicateForSamplesWithStartDate
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Apple Health through HealthKit.
 *
 * Workouts are written with [HKWorkoutBuilder] as indoor traditional strength training, tagged with
 * the session id under HealthKit's external-UUID metadata key so an edit can find and replace the
 * record and a delete can remove it, with nothing extra stored on Gains' side. Weights are body mass
 * samples; the ones Gains wrote are told apart by their source bundle id. HealthKit only lets an app
 * delete what it wrote itself, which is exactly the rule the sync wants.
 *
 * Every HealthKit call takes a completion block on some background queue; each is wrapped in a
 * suspending call so the sync reads top to bottom. A failed call is treated as "nothing written":
 * the workout or weight is already safe in Gains' database.
 */
@OptIn(ExperimentalForeignApi::class)
class IosHealthStore : HealthStore {
    private val store = HKHealthStore()
    private val workoutType: HKSampleType = HKObjectType.workoutType()
    private val bodyMassType: HKQuantityType? = HKQuantityType.quantityTypeForIdentifier(HKQuantityTypeIdentifierBodyMass)
    private val kilograms: HKUnit = HKUnit.gramUnitWithMetricPrefix(HKMetricPrefixKilo)
    private val bundleId: String? = NSBundle.mainBundle.bundleIdentifier

    override val isAvailable: Boolean get() = HKHealthStore.isHealthDataAvailable()

    override suspend fun requestAccess(): Boolean {
        val bodyMass = bodyMassType ?: return false
        return suspendCancellableCoroutine { cont ->
            store.requestAuthorizationToShareTypes(setOf(workoutType, bodyMass), readTypes = setOf(bodyMass)) { ok, _ -> cont.resume(ok) }
        }
    }

    override suspend fun canWrite(kind: HealthKind): HealthPermission {
        val type = when (kind) {
            HealthKind.WORKOUTS -> workoutType
            HealthKind.BODY_MASS -> bodyMassType ?: return HealthPermission.DENIED
        }
        return when (store.authorizationStatusForType(type)) {
            HKAuthorizationStatusSharingAuthorized -> HealthPermission.GRANTED
            HKAuthorizationStatusSharingDenied -> HealthPermission.DENIED
            else -> HealthPermission.UNKNOWN
        }
    }

    override suspend fun saveWorkout(workout: HealthWorkout) {
        deleteWorkout(workout.sessionId)
        val zone = TimeZone.currentSystemDefault()
        val start = workout.start.toInstant(zone)
        val end = start + workout.durationMinutes.minutes
        val configuration = HKWorkoutConfiguration().apply {
            activityType = HKWorkoutActivityTypeTraditionalStrengthTraining
            locationType = HKWorkoutSessionLocationTypeIndoor
        }
        val builder = HKWorkoutBuilder(healthStore = store, configuration = configuration, device = null)
        if (!builder.begin(start.toNSDate())) return
        builder.metadata(mapOf(HKMetadataKeyExternalUUID to workout.sessionId, HKMetadataKeyIndoorWorkout to true))
        if (!builder.end(end.toNSDate())) return
        builder.finish()
    }

    override suspend fun deleteWorkout(sessionId: String) {
        val tagged = HKQuery.predicateForObjectsWithMetadataKey(HKMetadataKeyExternalUUID, allowedValues = listOf(sessionId))
        val ours = query(workoutType, tagged)
        if (ours.isNotEmpty()) delete(ours)
    }

    override suspend fun readWeights(since: LocalDateTime?): List<HealthWeight> {
        val type = bodyMassType ?: return emptyList()
        val zone = TimeZone.currentSystemDefault()
        val predicate = HKQuery.predicateForSamplesWithStartDate(since?.toInstant(zone)?.toNSDate(), endDate = null, options = HKQueryOptionNone)
        return query(type, predicate).mapNotNull { sample ->
            val quantity = sample as? HKQuantitySample ?: return@mapNotNull null
            HealthWeight(
                at = quantity.startDate.toKotlinInstant().toLocalDateTime(zone),
                weightKg = quantity.quantity.doubleValueForUnit(kilograms),
                fromGains = quantity.sourceRevision.source.bundleIdentifier == bundleId,
            )
        }
    }

    override suspend fun saveWeight(date: LocalDate, weightKg: Double) {
        val type = bodyMassType ?: return
        deleteWeight(date)
        val zone = TimeZone.currentSystemDefault()
        val now = Clock.System.now().toLocalDateTime(zone)
        // A weigh-in for today is now; one entered for a past day gets midday, so it sorts among that day's samples.
        val at = (if (date == now.date) now else date.atTime(12, 0)).toInstant(zone).toNSDate()
        val sample = HKQuantitySample.quantitySampleWithType(
            type,
            quantity = HKQuantity.quantityWithUnit(kilograms, doubleValue = weightKg),
            startDate = at,
            endDate = at,
            metadata = mapOf(HKMetadataKeyExternalUUID to "bodyweight-$date"),
        )
        save(sample)
    }

    override suspend fun deleteWeight(date: LocalDate) {
        val type = bodyMassType ?: return
        val zone = TimeZone.currentSystemDefault()
        val day = HKQuery.predicateForSamplesWithStartDate(
            date.atTime(0, 0).toInstant(zone).toNSDate(),
            endDate = date.plusDays(1).atTime(0, 0).toInstant(zone).toNSDate(),
            options = HKQueryOptionNone,
        )
        val ours = HKQuery.predicateForObjectsFromSource(HKSource.defaultSource())
        val samples = query(type, NSCompoundPredicate.andPredicateWithSubpredicates(listOf(day, ours)))
        if (samples.isNotEmpty()) delete(samples)
    }

    private suspend fun query(type: HKSampleType, predicate: NSPredicate): List<HKSample> = suspendCancellableCoroutine { cont ->
        val byStart = NSSortDescriptor.sortDescriptorWithKey(HKSampleSortIdentifierStartDate, ascending = true)
        val query = HKSampleQuery(sampleType = type, predicate = predicate, limit = HKObjectQueryNoLimit, sortDescriptors = listOf(byStart)) { _, results, _ ->
            cont.resume(results?.mapNotNull { it as? HKSample } ?: emptyList())
        }
        store.executeQuery(query)
    }

    private suspend fun save(sample: HKObject): Boolean = suspendCancellableCoroutine { cont ->
        store.saveObject(sample) { ok, _ -> cont.resume(ok) }
    }

    private suspend fun delete(samples: List<HKSample>): Boolean = suspendCancellableCoroutine { cont ->
        store.deleteObjects(samples) { ok, _ -> cont.resume(ok) }
    }

    private suspend fun HKWorkoutBuilder.begin(start: NSDate): Boolean = suspendCancellableCoroutine { cont ->
        beginCollectionWithStartDate(start) { ok, _ -> cont.resume(ok) }
    }

    private suspend fun HKWorkoutBuilder.metadata(values: Map<Any?, *>): Boolean = suspendCancellableCoroutine { cont ->
        addMetadata(values) { ok, _ -> cont.resume(ok) }
    }

    private suspend fun HKWorkoutBuilder.end(end: NSDate): Boolean = suspendCancellableCoroutine { cont ->
        endCollectionWithEndDate(end) { ok, _ -> cont.resume(ok) }
    }

    private suspend fun HKWorkoutBuilder.finish(): Boolean = suspendCancellableCoroutine { cont ->
        finishWorkoutWithCompletion { workout, _ -> cont.resume(workout != null) }
    }
}
