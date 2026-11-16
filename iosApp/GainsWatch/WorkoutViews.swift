import SwiftUI
import WatchKit

// The watch's screens. Two pages, swiped between with the Digital Crown or a vertical swipe:
// Now (the clock, the set to do or the rest counting down, and what comes next) and Sets (every
// set of the workout, tapped to tick or untick). The colours are the phone's dark theme
// (docs/design.md): chalk text, grey for what is secondary, green for done, blue for rest.

enum Palette {
    static let chalk = Color(red: 0xF2 / 255, green: 0xF0 / 255, blue: 0xEB / 255)
    static let secondary = Color(red: 0xA7 / 255, green: 0xAC / 255, blue: 0xB3 / 255)
    static let card = Color(red: 0x1B / 255, green: 0x1E / 255, blue: 0x22 / 255)
    static let track = Color(red: 0x2B / 255, green: 0x30 / 255, blue: 0x36 / 255)
    static let floor = Color(red: 0x12 / 255, green: 0x14 / 255, blue: 0x16 / 255)
    /// Progress and "done".
    static let volt = Color(red: 0x62 / 255, green: 0xD9 / 255, blue: 0x9A / 255)
    /// Rest.
    static let rest = Color(red: 0x6C / 255, green: 0xB2 / 255, blue: 0xFF / 255)
}

struct RootView: View {
    @ObservedObject var store: WorkoutStore
    @ObservedObject var health: HealthWorkout
    @Environment(\.scenePhase) private var phase

    var body: some View {
        Group {
            if let workout = store.workout {
                TabView {
                    NowPage(workout: workout, store: store, health: health)
                    SetsPage(workout: workout, store: store)
                }
                .tabViewStyle(.verticalPage)
            } else {
                IdleView(ended: store.ended)
            }
        }
        .onChange(of: phase) { _, phase in
            if phase == .active { store.refresh() }
        }
    }
}

// MARK: - Now

struct NowPage: View {
    let workout: WatchWorkout
    @ObservedObject var store: WorkoutStore
    @ObservedObject var health: HealthWorkout

    var body: some View {
        // Once a second, so a rest that runs out hands over to the next set without a tap.
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let now = context.date
            VStack(alignment: .leading, spacing: 4) {
                header
                if let rest = workout.rest, rest.endsAt > now {
                    RestBlock(rest: rest, now: now, upcoming: Describe.upcoming(workout, workout.current, after: nil), store: store)
                } else if let ref = workout.current, let exercise = workout.exercises[safe: ref.exercise], let set = exercise.sets[safe: ref.set] {
                    SetBlock(exercise: exercise, set: set, ref: ref, unit: workout.unit,
                             upcoming: Describe.upcoming(workout, workout.next, after: ref), store: store)
                        // A new set starts from its own values, not the last one's.
                        .id("\(workout.startedAtMs)-\(exercise.id)-\(ref.set)")
                } else {
                    AllDone()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }

    /// How long the workout has run, the heart rate while Health is on, and a mark while ticks wait for the phone.
    private var header: some View {
        HStack(spacing: 6) {
            Text(workout.startedAt, style: .timer)
                .font(.system(.footnote, design: .rounded).weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(Palette.chalk)
            if let bpm = health.heartRate {
                Label("\(Int(bpm.rounded()))", systemImage: "heart.fill")
                    .font(.footnote)
                    .monospacedDigit()
                    .foregroundStyle(Palette.secondary)
                    .labelStyle(.titleAndIcon)
            }
            if store.waiting > 0 && !store.phoneReachable {
                Image(systemName: "iphone.slash")
                    .font(.footnote)
                    .foregroundStyle(Palette.secondary)
                    .accessibilityLabel(Text("Waiting for your iPhone"))
            }
        }
        .lineLimit(1)
    }
}

/// The set to do: its lift, which set it is, the values to change with the Digital Crown, Done.
struct SetBlock: View {
    let exercise: WatchExercise
    let set: WatchSet
    let ref: WatchSetRef
    let unit: String
    let upcoming: String?
    let store: WorkoutStore

    private enum Field { case weight, reps, seconds }

    @State private var weight: Double
    @State private var reps: Double
    @State private var seconds: Double
    @FocusState private var focus: Field?

    init(exercise: WatchExercise, set: WatchSet, ref: WatchSetRef, unit: String, upcoming: String?, store: WorkoutStore) {
        self.exercise = exercise
        self.set = set
        self.ref = ref
        self.unit = unit
        self.upcoming = upcoming
        self.store = store
        _weight = State(initialValue: Describe.number(set.weight) ?? 0)
        _reps = State(initialValue: Describe.number(set.reps) ?? 0)
        _seconds = State(initialValue: Describe.number(set.seconds) ?? 0)
    }

    private var showsWeight: Bool { exercise.kind == .weighted || !set.weight.isEmpty }
    private var showsReps: Bool { exercise.kind == .weighted || exercise.kind == .bodyweight || !set.reps.isEmpty }
    private var showsSeconds: Bool { exercise.kind == .timed }
    /// The smallest plate step: 2.5 kg or 5 lbs.
    private var weightStep: Double { unit == "lbs" ? 5 : 2.5 }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            VStack(alignment: .leading, spacing: 0) {
                Text(exercise.name)
                    .font(.headline)
                    .foregroundStyle(Palette.chalk)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                Text(caption)
                    .font(.footnote)
                    .foregroundStyle(Palette.secondary)
                    .lineLimit(1)
            }
            if exercise.kind == .cardio {
                Text(Describe.values(set, kind: exercise.kind, unit: unit))
                    .font(.system(.title3, design: .rounded).weight(.semibold))
                    .monospacedDigit()
            } else {
                HStack(spacing: 4) {
                    if showsWeight {
                        field(Describe.weight(weight), Describe.unitLabel(unit), .weight)
                            .digitalCrownRotation($weight, from: 0, through: 1000, by: weightStep, sensitivity: .low, isContinuous: false, isHapticFeedbackEnabled: true)
                    }
                    if showsWeight && showsReps {
                        Text(verbatim: "×").foregroundStyle(Palette.secondary)
                    }
                    if showsReps {
                        field(reps > 0 ? "\(Int(reps))" : "–", String(localized: "reps"), .reps)
                            .digitalCrownRotation($reps, from: 0, through: 100, by: 1, sensitivity: .medium, isContinuous: false, isHapticFeedbackEnabled: true)
                    }
                    if showsSeconds {
                        field(seconds > 0 ? "\(Int(seconds))" : "–", String(localized: "s"), .seconds)
                            .digitalCrownRotation($seconds, from: 0, through: 3600, by: 5, sensitivity: .medium, isContinuous: false, isHapticFeedbackEnabled: true)
                    }
                }
            }
            Button(action: done) {
                Label("Done", systemImage: "checkmark")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(FilledStyle(fill: Palette.volt, text: Palette.floor))
            if let upcoming {
                Text("Next: \(upcoming)")
                    .font(.footnote)
                    .foregroundStyle(Palette.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
        }
        .onAppear {
            // Reps are what changes most from set to set (the last, as-many-as-possible set above all).
            focus = showsReps ? .reps : (showsSeconds ? .seconds : (showsWeight ? .weight : nil))
        }
    }

    /// "Set 2 of 5", "Warm-up 1", with "per dumbbell" for a dumbbell lift.
    private var caption: String {
        let work = exercise.sets.filter { !$0.warmup }.count
        let base: String
        if set.warmup {
            base = String(localized: "Warm-up \(String(set.label.dropFirst()))")
        } else {
            base = String(localized: "Set \(set.label) of \(work)")
        }
        return exercise.perDumbbell ? base + " · " + String(localized: "per dumbbell") : base
    }

    /// A value the Digital Crown changes once it is tapped; the focused one is outlined.
    private func field(_ value: String, _ label: String, _ which: Field) -> some View {
        VStack(spacing: 0) {
            Text(value)
                .font(.system(size: 26, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .foregroundStyle(Palette.chalk)
            Text(label)
                .font(.caption2)
                .foregroundStyle(Palette.secondary)
                .lineLimit(1)
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 10).fill(Palette.card))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(focus == which ? Palette.volt : .clear, lineWidth: 2))
        .focusable()
        .focused($focus, equals: which)
        .onTapGesture { focus = which }
    }

    /// Ticks the set, sending only the values the lifter changed so the phone keeps its own spelling.
    private func done() {
        var sentWeight: String?
        if showsWeight, weight > 0, Describe.number(set.weight) != weight { sentWeight = Describe.weight(weight) }
        var sentReps: String?
        if showsReps, reps > 0, Describe.number(set.reps) != reps { sentReps = "\(Int(reps))" }
        var sentSeconds: String?
        if showsSeconds, seconds > 0, Describe.number(set.seconds) != seconds { sentSeconds = "\(Int(seconds))" }
        store.complete(ref, weight: sentWeight, reps: sentReps, seconds: sentSeconds)
    }
}

/// The rest counting down in a ring, with +30 s and Skip, and the set that follows it.
struct RestBlock: View {
    let rest: WatchRest
    let now: Date
    let upcoming: String?
    let store: WorkoutStore

    private var left: Double { max(0, rest.endsAt.timeIntervalSince(now)) }
    private var fraction: Double { rest.totalSeconds > 0 ? min(1, left / Double(rest.totalSeconds)) : 0 }

    var body: some View {
        VStack(spacing: 6) {
            ZStack {
                Circle().stroke(Palette.track, lineWidth: 7)
                Circle()
                    .trim(from: 0, to: fraction)
                    .stroke(Palette.rest, style: StrokeStyle(lineWidth: 7, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.linear(duration: 1), value: fraction)
                VStack(spacing: 0) {
                    Text(timerInterval: now...max(now, rest.endsAt), countsDown: true)
                        .font(.system(size: 30, weight: .semibold, design: .rounded))
                        .monospacedDigit()
                        .multilineTextAlignment(.center)
                        .foregroundStyle(Palette.chalk)
                    Text("Rest")
                        .font(.footnote)
                        .foregroundStyle(Palette.rest)
                }
            }
            .frame(width: 96, height: 96)
            .frame(maxWidth: .infinity)
            HStack(spacing: 6) {
                Button { store.extendRest() } label: {
                    Text("+30 s").frame(maxWidth: .infinity)
                }
                .buttonStyle(FilledStyle(fill: Palette.card, text: Palette.chalk))
                Button { store.skipRest() } label: {
                    Text("Skip").frame(maxWidth: .infinity)
                }
                .buttonStyle(FilledStyle(fill: Palette.card, text: Palette.chalk))
            }
            if let upcoming {
                Text("Next: \(upcoming)")
                    .font(.footnote)
                    .foregroundStyle(Palette.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

struct AllDone: View {
    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 40))
                .foregroundStyle(Palette.volt)
            Text("All sets done")
                .font(.headline)
            Text("Finish the workout on your iPhone.")
                .font(.footnote)
                .foregroundStyle(Palette.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Sets

/// Every set of the workout. A tap ticks a set off with the phone's values, or unticks a ticked one.
struct SetsPage: View {
    let workout: WatchWorkout
    @ObservedObject var store: WorkoutStore

    var body: some View {
        List {
            Section {
                Text("\(workout.done) of \(workout.total) sets done")
                    .font(.footnote)
                    .foregroundStyle(Palette.secondary)
                    .listRowBackground(Color.clear)
            }
            ForEach(Array(workout.exercises.enumerated()), id: \.offset) { e, exercise in
                Section {
                    ForEach(Array(exercise.sets.enumerated()), id: \.offset) { s, set in
                        let ref = WatchSetRef(exercise: e, set: s)
                        Button {
                            if set.done { store.undo(ref) } else { store.complete(ref) }
                        } label: {
                            SetRow(set: set, kind: exercise.kind, unit: workout.unit, isCurrent: ref == workout.current)
                        }
                        .disabled(!set.done && !set.hasValues)
                    }
                } header: {
                    Text(exercise.name).lineLimit(2)
                }
            }
        }
    }
}

struct SetRow: View {
    let set: WatchSet
    let kind: WatchKind
    let unit: String
    let isCurrent: Bool

    var body: some View {
        HStack(spacing: 8) {
            Text(set.label)
                .font(.footnote.monospacedDigit())
                .foregroundStyle(isCurrent ? Palette.volt : Palette.secondary)
                .frame(minWidth: 22, alignment: .leading)
            Text(Describe.values(set, kind: kind, unit: unit))
                .font(.body.monospacedDigit())
                .foregroundStyle(set.done ? Palette.secondary : Palette.chalk)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Spacer(minLength: 0)
            Image(systemName: set.done ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(set.done ? Palette.volt : Palette.track)
        }
    }
}

// MARK: - No workout

struct IdleView: View {
    let ended: WatchEnded?

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Image(systemName: "dumbbell.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(Palette.volt)
                Text("No workout running")
                    .font(.headline)
                    .multilineTextAlignment(.center)
                Text("Start a workout on your iPhone and it shows up here.")
                    .font(.footnote)
                    .foregroundStyle(Palette.secondary)
                    .multilineTextAlignment(.center)
                if ended?.saved == true {
                    Label("Last workout saved", systemImage: "checkmark.circle.fill")
                        .font(.footnote)
                        .foregroundStyle(Palette.volt)
                        .padding(.top, 4)
                }
            }
            .frame(maxWidth: .infinity)
        }
    }
}

// MARK: - Pieces

struct FilledStyle: ButtonStyle {
    let fill: Color
    let text: Color

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(text)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 40)
            .background(Capsule().fill(fill))
            .opacity(configuration.isPressed ? 0.7 : 1)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
    }
}

/// Numbers and sets as words on the wrist.
enum Describe {
    /// "62.5" or "62,5" as a number; nil for nothing.
    static func number(_ text: String) -> Double? {
        Double(text.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespaces))
    }

    /// 100 → "100", 102.5 → "102.5", 101.25 → "101.25"; 0 → "–".
    static func weight(_ value: Double) -> String {
        guard value > 0 else { return "–" }
        if value == value.rounded() { return String(Int(value)) }
        var text = String(format: "%.2f", value)
        while text.hasSuffix("0") { text.removeLast() }
        return text
    }

    static func unitLabel(_ unit: String) -> String {
        unit == "lbs" ? String(localized: "lbs") : String(localized: "kg")
    }

    /// "100 kg × 5", "12 reps", "45 s", "2.5 km · 12:00"; "–" for an empty set.
    static func values(_ set: WatchSet, kind: WatchKind, unit: String) -> String {
        var parts: [String] = []
        let weight = number(set.weight)
        let reps = number(set.reps).map { Int($0) }
        if let weight, weight > 0 {
            let w = "\(Describe.weight(weight)) \(unitLabel(unit))"
            parts.append(reps.map { "\(w) × \($0)" } ?? w)
        } else if let reps {
            parts.append(String(localized: "\(reps) reps"))
        }
        if let distance = number(set.distanceKm), distance > 0 {
            parts.append("\(Describe.weight(distance)) " + String(localized: "km"))
        }
        if let seconds = number(set.seconds).map({ Int($0) }), seconds > 0 {
            parts.append(seconds >= 60 && kind == .cardio
                ? String(format: "%ld:%02ld", seconds / 60, seconds % 60)
                : "\(seconds) " + String(localized: "s"))
        }
        return parts.isEmpty ? "–" : parts.joined(separator: " · ")
    }

    /// What comes at `ref`: the set alone when it is the same lift as `after`, else the lift's name too.
    static func upcoming(_ workout: WatchWorkout, _ ref: WatchSetRef?, after: WatchSetRef?) -> String? {
        guard let ref, let exercise = workout.exercises[safe: ref.exercise], let set = exercise.sets[safe: ref.set] else { return nil }
        let values = Describe.values(set, kind: exercise.kind, unit: workout.unit)
        if let after, after.exercise == ref.exercise { return values }
        return "\(exercise.name) · \(values)"
    }
}
