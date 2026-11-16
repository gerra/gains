import Foundation
import HealthKit

/// A strength-training workout in Apple Health for the workout running on the phone. While it runs,
/// watchOS keeps Gains on the wrist when it is raised and lets it tap at the end of a rest with the
/// wrist down; it also reads the heart rate and counts the workout towards the Activity rings.
/// Started when the phone starts a workout (HealthKit opens the app for it) or when the app opens
/// on one; saved when the phone stores the workout, dropped when the phone discards it. Optional:
/// without HealthKit permission the app works the same, just without these.
@MainActor
final class HealthWorkout: NSObject, ObservableObject {
    static let shared = HealthWorkout()

    @Published private(set) var heartRate: Double?
    @Published private(set) var activeCalories: Double?
    @Published private(set) var running = false

    private let store = HKHealthStore()
    private var session: HKWorkoutSession?
    private var builder: HKLiveWorkoutBuilder?
    /// The phone workout this one is for; nil for one started before the phone's state came in.
    private var forWorkout: Int64?
    private var asked = false

    private static var configuration: HKWorkoutConfiguration {
        let c = HKWorkoutConfiguration()
        c.activityType = .traditionalStrengthTraining
        c.locationType = .indoor
        return c
    }

    /// Keeps the Health workout in step with the phone's state.
    func follow(_ state: WatchState) {
        if let workout = state.workout {
            if session == nil {
                Task { await start(at: workout.startedAt, for: workout.startedAtMs) }
            } else if forWorkout == nil {
                // Opened by the phone before its state arrived: this is the workout it was for.
                forWorkout = workout.startedAtMs
            } else if forWorkout != workout.startedAtMs {
                // Another workout altogether: close the old one as kept, open one for this.
                end(save: true)
                Task { await start(at: workout.startedAt, for: workout.startedAtMs) }
            }
        } else if session != nil {
            let save = state.ended.map { $0.startedAtMs != forWorkout || $0.saved } ?? true
            end(save: save)
        }
    }

    /// HealthKit opened the app because a workout started on the phone.
    func startFromPhone(_ configuration: HKWorkoutConfiguration) {
        guard session == nil else { return }
        Task { await start(at: Date(), for: nil, configuration: configuration) }
    }

    /// watchOS relaunched the app with its workout still running (after a crash, say): take it back.
    func recover() {
        store.recoverActiveWorkoutSession { session, _ in
            guard let session else { return }
            Task { @MainActor in HealthWorkout.shared.adopt(session, for: nil) }
        }
    }

    private func start(at date: Date, for startedAtMs: Int64?, configuration: HKWorkoutConfiguration = HealthWorkout.configuration) async {
        guard HKHealthStore.isHealthDataAvailable(), session == nil else { return }
        guard await authorized() else { return }
        guard session == nil, let session = try? HKWorkoutSession(healthStore: store, configuration: configuration) else { return }
        adopt(session, for: startedAtMs)
        // A workout started on the phone a moment ago counts from then, not from when the watch caught up.
        let start = min(date, Date())
        session.startActivity(with: start)
        builder?.beginCollection(withStart: start) { _, _ in }
    }

    private func adopt(_ session: HKWorkoutSession, for startedAtMs: Int64?) {
        let builder = session.associatedWorkoutBuilder()
        builder.dataSource = HKLiveWorkoutDataSource(healthStore: store, workoutConfiguration: session.workoutConfiguration)
        session.delegate = self
        builder.delegate = self
        self.session = session
        self.builder = builder
        forWorkout = startedAtMs
        running = true
    }

    private func end(save: Bool) {
        guard let session, let builder else { return }
        self.session = nil
        self.builder = nil
        forWorkout = nil
        running = false
        heartRate = nil
        activeCalories = nil
        session.end()
        builder.endCollection(withEnd: Date()) { _, _ in
            if save {
                builder.finishWorkout { _, _ in }
            } else {
                builder.discardWorkout()
            }
        }
    }

    /// Asks once for what the workout needs: to save workouts, and to read the heart rate and energy.
    private func authorized() async -> Bool {
        let workouts = HKObjectType.workoutType()
        if store.authorizationStatus(for: workouts) == .sharingAuthorized { return true }
        guard !asked else { return false }
        asked = true
        let read: Set<HKObjectType> = [HKQuantityType(.heartRate), HKQuantityType(.activeEnergyBurned), workouts]
        let share: Set<HKSampleType> = [workouts, HKQuantityType(.activeEnergyBurned)]
        do {
            try await store.requestAuthorization(toShare: share, read: read)
        } catch {
            return false
        }
        return store.authorizationStatus(for: workouts) == .sharingAuthorized
    }
}

extension HealthWorkout: HKWorkoutSessionDelegate {
    nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didChangeTo toState: HKWorkoutSessionState, from fromState: HKWorkoutSessionState, date: Date) {}

    nonisolated func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {}
}

extension HealthWorkout: HKLiveWorkoutBuilderDelegate {
    nonisolated func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {}

    nonisolated func workoutBuilder(_ workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOf collectedTypes: Set<HKSampleType>) {
        let bpm = HKUnit.count().unitDivided(by: .minute())
        let heart = workoutBuilder.statistics(for: HKQuantityType(.heartRate))?.mostRecentQuantity()?.doubleValue(for: bpm)
        let energy = workoutBuilder.statistics(for: HKQuantityType(.activeEnergyBurned))?.sumQuantity()?.doubleValue(for: .kilocalorie())
        Task { @MainActor in
            let health = HealthWorkout.shared
            if let heart { health.heartRate = heart }
            if let energy { health.activeCalories = energy }
        }
    }
}
