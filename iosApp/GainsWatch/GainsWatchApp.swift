import HealthKit
import SwiftUI
import WatchKit

/// Gains on the Apple Watch: the workout running on the iPhone, on the wrist (docs/watch.md).
@main
struct GainsWatchApp: App {
    @WKApplicationDelegateAdaptor(WatchAppDelegate.self) private var delegate

    var body: some Scene {
        WindowGroup {
            RootView(store: .shared, health: .shared)
        }
    }
}

final class WatchAppDelegate: NSObject, WKApplicationDelegate {
    func applicationDidFinishLaunching() {
        Task { @MainActor in
            let store = WorkoutStore.shared
            store.onPhoneState = { HealthWorkout.shared.follow($0) }
            store.activate()
        }
    }

    /// The phone started a workout and asked HealthKit to bring the watch app up for it.
    func handle(_ workoutConfiguration: HKWorkoutConfiguration) {
        Task { @MainActor in HealthWorkout.shared.startFromPhone(workoutConfiguration) }
    }

    /// The app was relaunched with its Health workout still running.
    func handleActiveWorkoutRecovery() {
        Task { @MainActor in HealthWorkout.shared.recover() }
    }
}
