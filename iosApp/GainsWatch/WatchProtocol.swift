import Foundation

// What the iPhone and the watch say to each other: the Swift twin of
// shared/src/commonMain/kotlin/app/gains/watch/WatchProtocol.kt (docs/watch.md). The phone sends a
// WatchState as JSON whenever the workout changes; the watch sends a WatchCommand as JSON when the
// lifter ticks a set, undoes one, or skips or stretches the rest. Field names are the protocol:
// change one there and here together.

struct WatchState: Codable, Equatable {
    var version: Int
    /// The phone's clock when it was sent.
    var sentAtMs: Int64
    /// The workout in progress; nil while none runs.
    var workout: WatchWorkout?
    /// The last workout to stop: whether it was stored, so its Health workout is saved or dropped.
    var ended: WatchEnded?
}

struct WatchWorkout: Codable, Equatable {
    var startedAtMs: Int64
    var title: String
    /// "kg" or "lbs".
    var unit: String
    var rest: WatchRest?
    var exercises: [WatchExercise]
    var current: WatchSetRef?
    var next: WatchSetRef?
    var done: Int
    var total: Int

    var startedAt: Date { Date(epochMs: startedAtMs) }

    func set(_ ref: WatchSetRef) -> WatchSet? {
        guard exercises.indices.contains(ref.exercise), exercises[ref.exercise].sets.indices.contains(ref.set) else { return nil }
        return exercises[ref.exercise].sets[ref.set]
    }
}

struct WatchRest: Codable, Equatable {
    var exerciseId: String
    var endsAtMs: Int64
    var totalSeconds: Int

    var endsAt: Date { Date(epochMs: endsAtMs) }
}

struct WatchExercise: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var kind: WatchKind
    var perDumbbell: Bool
    var sets: [WatchSet]
}

enum WatchKind: String, Codable {
    case weighted, bodyweight, timed, cardio

    /// A kind added on the phone after this watch app shipped is shown as reps, never a failure.
    init(from decoder: Decoder) throws {
        self = WatchKind(rawValue: try decoder.singleValueContainer().decode(String.self)) ?? .bodyweight
    }
}

struct WatchSet: Codable, Equatable {
    /// "W1" for a warm-up, "1" for a work set, as the phone numbers its rows.
    var label: String
    var weight: String
    var reps: String
    var seconds: String
    var distanceKm: String
    var warmup: Bool
    var done: Bool
    /// The rest in seconds that ticking this set starts.
    var rest: Int

    var hasValues: Bool { ![weight, reps, seconds, distanceKm].allSatisfy { $0.trimmingCharacters(in: .whitespaces).isEmpty } }

    enum CodingKeys: String, CodingKey { case label, weight, reps, seconds, distanceKm, warmup, done, rest }

    init(label: String, weight: String = "", reps: String = "", seconds: String = "", distanceKm: String = "", warmup: Bool = false, done: Bool = false, rest: Int = 90) {
        self.label = label; self.weight = weight; self.reps = reps; self.seconds = seconds
        self.distanceKm = distanceKm; self.warmup = warmup; self.done = done; self.rest = rest
    }

    /// Every field but the label is optional on the wire, as the Kotlin side has defaults for them.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        label = try c.decode(String.self, forKey: .label)
        weight = try c.decodeIfPresent(String.self, forKey: .weight) ?? ""
        reps = try c.decodeIfPresent(String.self, forKey: .reps) ?? ""
        seconds = try c.decodeIfPresent(String.self, forKey: .seconds) ?? ""
        distanceKm = try c.decodeIfPresent(String.self, forKey: .distanceKm) ?? ""
        warmup = try c.decodeIfPresent(Bool.self, forKey: .warmup) ?? false
        done = try c.decodeIfPresent(Bool.self, forKey: .done) ?? false
        rest = try c.decodeIfPresent(Int.self, forKey: .rest) ?? 90
    }
}

struct WatchSetRef: Codable, Equatable, Hashable {
    var exercise: Int
    var set: Int
}

struct WatchEnded: Codable, Equatable {
    var startedAtMs: Int64
    var saved: Bool
}

/// Something done on the watch, named by the workout it was meant for so a late one is dropped.
enum WatchCommand: Equatable {
    case complete(startedAtMs: Int64, exerciseId: String, set: Int, weight: String?, reps: String?, seconds: String?)
    case undo(startedAtMs: Int64, exerciseId: String, set: Int)
    case skipRest(startedAtMs: Int64)
    case extendRest(startedAtMs: Int64, seconds: Int)

    var startedAtMs: Int64 {
        switch self {
        case let .complete(startedAtMs, _, _, _, _, _), let .undo(startedAtMs, _, _),
             let .skipRest(startedAtMs), let .extendRest(startedAtMs, _):
            return startedAtMs
        }
    }

    /// {"type": "complete", "startedAtMs": …, …}: kotlinx.serialization's sealed-class shape.
    var json: String {
        var body: [String: Any] = ["startedAtMs": startedAtMs]
        switch self {
        case let .complete(_, exerciseId, set, weight, reps, seconds):
            body["type"] = "complete"
            body["exerciseId"] = exerciseId
            body["set"] = set
            if let weight { body["weight"] = weight }
            if let reps { body["reps"] = reps }
            if let seconds { body["seconds"] = seconds }
        case let .undo(_, exerciseId, set):
            body["type"] = "undo"
            body["exerciseId"] = exerciseId
            body["set"] = set
        case .skipRest:
            body["type"] = "skipRest"
        case let .extendRest(_, seconds):
            body["type"] = "extendRest"
            body["seconds"] = seconds
        }
        let data = (try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])) ?? Data()
        return String(data: data, encoding: .utf8) ?? "{}"
    }
}

enum WatchJson {
    static func decodeState(_ text: String) -> WatchState? {
        guard let data = text.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(WatchState.self, from: data)
    }
}

extension Date {
    init(epochMs: Int64) { self.init(timeIntervalSince1970: TimeInterval(epochMs) / 1000) }
    var epochMs: Int64 { Int64((timeIntervalSince1970 * 1000).rounded()) }
}
