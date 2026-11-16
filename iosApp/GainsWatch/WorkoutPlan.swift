import Foundation

// The watch's own copy of the phone's rules (shared/.../watch/WatchPlan.kt), so a tick shows and its
// rest starts the moment it is made, before the phone answers or while it is out of reach. The
// phone's next state replaces whatever is guessed here; keep the two in step.
enum WorkoutPlan {
    /// `workout` with `command` carried out at `now`, or nil when it does not apply.
    static func apply(_ command: WatchCommand, to workout: WatchWorkout, now: Date) -> WatchWorkout? {
        guard command.startedAtMs == workout.startedAtMs else { return nil }
        var w = workout
        switch command {
        case let .complete(_, exerciseId, set, weight, reps, seconds):
            guard let e = w.exercises.firstIndex(where: { $0.id == exerciseId }), w.exercises[e].sets.indices.contains(set) else { return nil }
            var s = w.exercises[e].sets[set]
            guard !s.done else { return nil }
            if let weight { s.weight = weight }
            if let reps { s.reps = reps }
            if let seconds { s.seconds = seconds }
            guard s.hasValues else { return nil }
            s.done = true
            w.exercises[e].sets[set] = s
            w.rest = WatchRest(exerciseId: exerciseId, endsAtMs: now.epochMs + Int64(s.rest) * 1000, totalSeconds: s.rest)
        case let .undo(_, exerciseId, set):
            guard let e = w.exercises.firstIndex(where: { $0.id == exerciseId }), w.exercises[e].sets.indices.contains(set),
                  w.exercises[e].sets[set].done else { return nil }
            w.exercises[e].sets[set].done = false
        case .skipRest:
            guard w.rest != nil else { return nil }
            w.rest = nil
        case let .extendRest(_, seconds):
            guard seconds > 0 else { return nil }
            if let rest = w.rest, rest.endsAtMs > now.epochMs {
                w.rest = WatchRest(exerciseId: rest.exerciseId, endsAtMs: rest.endsAtMs + Int64(seconds) * 1000, totalSeconds: rest.totalSeconds + seconds)
            } else {
                guard let id = w.rest?.exerciseId ?? w.exercises.first?.id else { return nil }
                w.rest = WatchRest(exerciseId: id, endsAtMs: now.epochMs + Int64(seconds) * 1000, totalSeconds: seconds)
            }
        }
        return w.renumbered()
    }

    /// The set to do now: from the lift the rest belongs to onwards, then those before it.
    static func current(_ done: [[Bool]], anchor: Int?) -> WatchSetRef? {
        guard !done.isEmpty else { return nil }
        let start = anchor ?? 0
        for step in 0..<done.count {
            let e = (start + step) % done.count
            if let s = done[e].firstIndex(of: false) { return WatchSetRef(exercise: e, set: s) }
        }
        return nil
    }

    /// The unticked set after `from`, in the same order as `current`.
    static func after(_ done: [[Bool]], from: WatchSetRef) -> WatchSetRef? {
        let same = done[from.exercise]
        if let s = same.indices.first(where: { $0 > from.set && !same[$0] }) { return WatchSetRef(exercise: from.exercise, set: s) }
        if done.count > 1 {
            for step in 1..<done.count {
                let e = (from.exercise + step) % done.count
                if let s = done[e].firstIndex(of: false) { return WatchSetRef(exercise: e, set: s) }
            }
        }
        if let s = same.indices.first(where: { $0 < from.set && !same[$0] }) { return WatchSetRef(exercise: from.exercise, set: s) }
        return nil
    }

    /// Whether `workout`, from the phone, already shows `command` carried out, so it need not be
    /// guessed again. `restBefore` is when the rest ended as the command was sent: a rest the
    /// phone has changed since then shows a skip or a stretch taken.
    static func reflects(_ workout: WatchWorkout?, _ command: WatchCommand, restBefore: Int64?) -> Bool {
        guard let workout, workout.startedAtMs == command.startedAtMs else { return true }
        switch command {
        case let .complete(_, id, set, _, _, _):
            return workout.exercises.first(where: { $0.id == id })?.sets[safe: set]?.done ?? true
        case let .undo(_, id, set):
            return !(workout.exercises.first(where: { $0.id == id })?.sets[safe: set]?.done ?? false)
        case .skipRest, .extendRest:
            return workout.rest?.endsAtMs != restBefore
        }
    }
}

extension WatchWorkout {
    /// `current`, `next` and the counts worked out again after a change made on the watch.
    func renumbered() -> WatchWorkout {
        var w = self
        let done = exercises.map { $0.sets.map(\.done) }
        let anchor = rest.flatMap { r in exercises.firstIndex(where: { $0.id == r.exerciseId }) }
        w.current = WorkoutPlan.current(done, anchor: anchor)
        w.next = w.current.flatMap { WorkoutPlan.after(done, from: $0) }
        w.done = done.reduce(0) { $0 + $1.filter { $0 }.count }
        w.total = done.reduce(0) { $0 + $1.count }
        return w
    }
}

extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}
