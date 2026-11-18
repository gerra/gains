import Foundation
import WatchConnectivity
import WatchKit

/// The workout as the watch knows it: the phone's latest state, with the watch's own ticks laid on
/// top until the phone has caught up with them. Talks to the phone over WatchConnectivity
/// (docs/watch.md): states come in as the application context and as messages; commands go out as
/// a message while the phone is reachable and are queued with transferUserInfo while it is not.
@MainActor
final class WorkoutStore: NSObject, ObservableObject {
    static let shared = WorkoutStore()

    /// What the screens show: the phone's workout with the pending ticks applied.
    @Published private(set) var workout: WatchWorkout?
    /// The last workout to stop, as the phone said.
    @Published private(set) var ended: WatchEnded?
    @Published private(set) var phoneReachable = false
    /// Commands made here that the phone has not shown back yet.
    @Published private(set) var waiting = 0

    /// Called with each state from the phone, so the Health workout can follow it.
    var onPhoneState: (@MainActor (WatchState) -> Void)?

    private struct Pending {
        let command: WatchCommand
        let sentAt: Date
        /// When the rest ended as the command was made: a skip or a stretch shows as a change to it.
        let restBefore: Int64?
    }

    /// The phone's workout as last sent, before the watch's own changes.
    private var phone: WatchWorkout?
    private var lastSentAtMs: Int64 = 0
    private var pending: [Pending] = []
    private var restAlarm: Task<Void, Never>?
    private var session: WCSession? { WCSession.isSupported() ? WCSession.default : nil }

    /// How long a command waits to be shown back before the phone's word is taken over it.
    private let patience: TimeInterval = 20

    func activate() {
        guard let session, session.delegate == nil else { return }
        session.delegate = self
        session.activate()
    }

    // MARK: - From the phone

    /// A state from the phone, as JSON. An older one than already seen is dropped.
    func accept(_ text: String?) {
        guard let text, let state = WatchJson.decodeState(text) else { return }
        guard state.sentAtMs >= lastSentAtMs else { return }
        lastSentAtMs = state.sentAtMs
        phone = state.workout
        ended = state.ended
        let now = Date()
        pending.removeAll { p in
            now.timeIntervalSince(p.sentAt) > patience || WorkoutPlan.reflects(state.workout, p.command, restBefore: p.restBefore)
        }
        republish()
        onPhoneState?(state)
    }

    /// Asks the phone for its state, for when the app opens and the context may be stale.
    func refresh() {
        guard let session, session.activationState == .activated else { return }
        accept(session.receivedApplicationContext["state"] as? String)
        guard session.isReachable else { return }
        session.sendMessage(["hello": 1], replyHandler: { reply in
            let text = reply["state"] as? String
            Task { @MainActor in WorkoutStore.shared.accept(text) }
        }, errorHandler: nil)
    }

    // MARK: - From the lifter

    /// Tick off the set at `ref` with the values settled on here (nil keeps the phone's).
    func complete(_ ref: WatchSetRef, weight: String? = nil, reps: String? = nil, seconds: String? = nil) {
        guard let w = workout, let exercise = w.exercises[safe: ref.exercise] else { return }
        let ok = send(.complete(startedAtMs: w.startedAtMs, exerciseId: exercise.id, set: ref.set, weight: weight, reps: reps, seconds: seconds))
        WKInterfaceDevice.current().play(ok ? .success : .failure)
    }

    func undo(_ ref: WatchSetRef) {
        guard let w = workout, let exercise = w.exercises[safe: ref.exercise] else { return }
        if send(.undo(startedAtMs: w.startedAtMs, exerciseId: exercise.id, set: ref.set)) {
            WKInterfaceDevice.current().play(.click)
        }
    }

    func skipRest() {
        guard let w = workout else { return }
        if send(.skipRest(startedAtMs: w.startedAtMs)) { WKInterfaceDevice.current().play(.click) }
    }

    func extendRest(by seconds: Int = 30) {
        guard let w = workout else { return }
        if send(.extendRest(startedAtMs: w.startedAtMs, seconds: seconds)) { WKInterfaceDevice.current().play(.click) }
    }

    /// Applies `command` here at once and sends it to the phone. False when it changes nothing.
    @discardableResult
    private func send(_ command: WatchCommand) -> Bool {
        guard let current = workout, WorkoutPlan.apply(command, to: current, now: Date()) != nil else { return false }
        pending.append(Pending(command: command, sentAt: Date(), restBefore: current.rest?.endsAtMs))
        republish()
        let payload: [String: Any] = ["command": command.json]
        if let session, session.activationState == .activated {
            if session.isReachable {
                // Not delivered: queue it, and WatchConnectivity hands it over once the phone is back.
                session.sendMessage(payload, replyHandler: nil) { _ in
                    Task { @MainActor in WorkoutStore.shared.session?.transferUserInfo(payload) }
                }
            } else {
                session.transferUserInfo(payload)
            }
        }
        return true
    }

    /// The phone's workout with the pending commands laid over it, and the rest alarm set for it.
    private func republish() {
        var shown = phone
        let now = Date()
        for p in pending {
            if let base = shown, let applied = WorkoutPlan.apply(p.command, to: base, now: p.sentAt) { shown = applied }
        }
        if shown != workout { workout = shown }
        waiting = pending.count
        scheduleRestAlarm(shown?.rest, now: now)
    }

    // MARK: - The rest

    /// Taps on the wrist for the last three seconds of a rest and a stronger one when it is over.
    /// While a Health workout runs the app keeps running with the wrist down, so these arrive then
    /// too; without one the phone's "Rest over" notification is what reaches the wrist.
    private func scheduleRestAlarm(_ rest: WatchRest?, now: Date) {
        restAlarm?.cancel()
        restAlarm = nil
        guard let rest, rest.endsAt > now else { return }
        let endsAt = rest.endsAt
        restAlarm = Task { @MainActor in
            for secondsLeft in [3, 2, 1] {
                let at = endsAt.addingTimeInterval(-Double(secondsLeft))
                if at > Date() {
                    try? await Task.sleep(nanoseconds: UInt64(at.timeIntervalSinceNow * 1_000_000_000))
                    if Task.isCancelled { return }
                    WKInterfaceDevice.current().play(.click)
                }
            }
            let left = endsAt.timeIntervalSinceNow
            if left > 0 { try? await Task.sleep(nanoseconds: UInt64(left * 1_000_000_000)) }
            if Task.isCancelled { return }
            WKInterfaceDevice.current().play(.notification)
        }
    }
}

extension WorkoutStore: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        let reachable = session.isReachable
        Task { @MainActor in
            let store = WorkoutStore.shared
            store.phoneReachable = reachable
            store.refresh()
        }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        let reachable = session.isReachable
        Task { @MainActor in
            let store = WorkoutStore.shared
            store.phoneReachable = reachable
            if reachable { store.refresh() }
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        let text = applicationContext["state"] as? String
        Task { @MainActor in WorkoutStore.shared.accept(text) }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        let text = message["state"] as? String
        Task { @MainActor in WorkoutStore.shared.accept(text) }
    }
}
