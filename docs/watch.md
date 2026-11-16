# Apple Watch

Gains on the wrist: the workout running on the iPhone, with its clock, the rest counting down,
a tap to tick the set off and the set that comes next. The watch app is a companion: it shows and
changes the iPhone's workout and has nothing of its own without one.

## What it does

**Now** (the first page):

- **The workout clock**, counting up from the start, and the heart rate while the Health
  workout runs.
- **The set to do**: the lift, "Set 3 of 5" (or "Warm-up 1", with "per dumbbell" where it
  applies), and its values as the phone has them. The Digital Crown changes the outlined value;
  reps are outlined first, since they change most (the last, as-many-as-possible set above all),
  and a tap outlines the weight instead (2.5 kg or 5 lbs a step). A timed set has its seconds
  (5 s a step). **Done** ticks it off with those values.
- **The rest**, once a set is ticked: a blue ring counting down from the set's rest (the tier's
  short end, 30 s after a warm-up, 90 s otherwise, as on the phone), **+30 s** and **Skip**. The
  wrist taps at 3, 2 and 1 and gives a stronger tap when it is over; the page then turns back to
  the next set by itself.
- **What's next**: "Next: 100 kg × 3" for the same lift, "Next: Bench press · 60 kg × 10" for
  another.
- **All sets done** once nothing is left to tick: the workout is finished on the iPhone.

**Sets** (swipe up, or turn the crown past the end): every set of the workout, ticked ones in
green and the current one's number in green. A tap ticks a set off with the phone's values or
unticks a ticked one.

Between workouts the watch says that none is running, and that the last one was saved.

The watch app speaks English and Russian, in the watch's own language; the lift names and the
workout's title come from the phone, in the app's language there.

## Connecting a watch

There is nothing to pair in Gains: the watch app talks to the iPhone its watch is paired with.
What the lifter has to do is get the watch app onto the watch, and the phone says how only to
someone with a watch, following Apple's guidance for companion apps: say it where it helps, once,
and never block anything on it.

- **No watch paired** (or Android, or the desktop): nothing about the watch is shown anywhere.
- **Paired, Gains not on it:** Settings has an **Apple Watch** card with the three steps (open the
  Watch app on the iPhone, Install next to Gains under Available Apps, start a workout here), an
  **Open the Watch app** button and the tip that **Automatic App Install** (Watch app, General)
  puts apps on the watch by themselves. A timed workout also shows a card, once: what the watch
  would do for this workout, **Open the Watch app** and **Not now**. "Not now" is stored
  (`watch_nudge_dismissed` in the settings table) and the card never comes back; Settings keeps the
  steps.
- **Gains on the watch:** Settings says **Connected** and what happens next. Starting a workout
  opens the watch app by itself, so there is nothing else to learn.

The status comes from WatchConnectivity (`isPaired`, `isWatchAppInstalled`), refreshed when the
session activates and whenever the watch's state changes, so the card turns to Connected as soon
as the install finishes, even with Settings open. **Open the Watch app** uses the Watch app's
`itms-watchs://` URL, which Apple does not document; the steps are always spelled out beside it in
case it stops working. On the watch, HealthKit asks for its permission when the first workout
starts there, with the reasons in `iosApp/GainsWatch/Info.plist`; declining keeps everything but
the heart rate and the Health workout.

## How it works

```
iPhone                                             Apple Watch
SessionEditorModel ─┐ persist                      WorkoutStore
                    ▼                                │  ▲
LiveSessionRepository ── WatchLink.states() ──► IosWatchBridge ── WCSession ──► state (JSON)
        ▲                                            │                          │
        └── WatchLink.apply() ◄── no editor ─────────┤ ◄── command (JSON) ──────┘
SessionEditorModel.applyWatch() ◄── editor open ─────┘
```

- **One workout, owned by the phone.** The watch is a view of `LiveSession` and a remote for it.
  The rules for what is current, what comes next, and what a tick, an undo, a skip and +30 s do
  are `WatchPlan` in `shared/` (tested in `WatchPlanTest`), used by the open editor
  (`SessionEditorModel.applyWatch`) and, with no editor holding the workout, by `WatchLink.apply`
  on the stored workout, exactly as the Android **Skip rest** works.
- **Out:** `WatchLink.states()` turns the stored workout into a `WatchState` each time it changes
  (the editor persists 300 ms after a change). `IosWatchBridge` puts the JSON in the
  WatchConnectivity application context, which the watch reads whenever it wakes, and also sends
  it as a message while the watch app is open. When the workout stops, the states say whether it
  was stored or discarded (`ended`), so the watch can save or drop its Health workout.
- **In:** a command from the watch goes to the shared UI while it runs (`WatchCommands`, collected
  by `LiveSessionNotices`), which hands it to the editor running the workout or else applies it to
  the database; with no UI up (the watch woke the app in the background) the bridge applies it to
  the database itself. Each command names the workout it was meant for by its start, and a set by
  its lift's id and its index, so a late command is dropped and a reorder can't tick the wrong lift.
- **At once on the wrist.** The watch applies its own command straight away with the same rules
  (`WorkoutPlan.swift`, the Swift twin of `WatchPlan`), so a tick shows and its rest starts before
  the phone answers. While the phone is out of reach the command is queued (`transferUserInfo`) and
  delivered when it is back. The phone's next state replaces the guess once it shows the command.
- **The Health workout.** When a workout starts on the phone, `IosWatchBridge` asks HealthKit to
  open the watch app (`startWatchApp`), which starts a strength-training `HKWorkoutSession`. That is
  what keeps Gains on the wrist when it is raised, lets it tap at the end of a rest with the wrist
  down, reads the heart rate and counts the workout towards the Activity rings. It is saved to
  Health when the phone stores the workout and dropped when the phone discards it. Without
  HealthKit permission everything else works the same; the phone's **Rest over** notification
  still reaches the wrist when the phone is locked.

The protocol is `WatchProtocol.kt` in `shared/` and `WatchProtocol.swift` in
`iosApp/GainsWatch/`; change a field in both, and bump `WatchState.VERSION` if an older watch
could misread it. Unknown fields are ignored on both sides.

## Where it lives

| What | Where |
|---|---|
| Protocol, current/next, the commands | `shared/src/commonMain/kotlin/app/gains/watch/` |
| The phone's half (mirror, database path, command bus) | `composeApp/src/commonMain/kotlin/app/gains/watch/WatchLink.kt` |
| WatchConnectivity and HealthKit on the iPhone | `composeApp/src/iosMain/kotlin/app/gains/IosWatchBridge.kt` |
| The watch app (SwiftUI, watchOS 10) | `iosApp/GainsWatch/`, target and scheme `GainsWatch` |
| Paired and installed, for the screens | `composeApp/.../platform/WatchPairing.kt`, set by `IosWatchBridge` |
| Settings' card and the workout's offer | `composeApp/.../ui/screens/WatchConnect.kt`; screenshots `23-apple-watch*` |

The watch app is embedded in the iPhone app (`Embed Watch Content`), so building or archiving the
`iosApp` scheme builds it too, and the iOS workflow's simulator build checks it. To run it, pick
the `GainsWatch` scheme and a watch simulator paired with an iPhone simulator running Gains.

## One-time setup in the Apple Developer account

Needed before the next TestFlight upload after this lands, or the archive fails to sign:

1. **iPhone App ID** `app.gains.Gains`: [Identifiers](https://developer.apple.com/account/resources/identifiers/list)
   > the app > tick **HealthKit** > Save. Then regenerate its App Store profile (Profiles > the
   profile > Edit > Save > Download) and update `IOS_APP_STORE_PROFILE_BASE64`.
2. **Watch App ID** `app.gains.Gains.watchkitapp`: Identifiers > **+** > App IDs > App, with
   **HealthKit**. Then Profiles > **+** > *App Store Connect* > that App ID and the distribution
   certificate > name it (e.g. *Gains Watch App Store*) > download, and add it as the secret
   `IOS_WATCH_APP_STORE_PROFILE_BASE64` (`base64 -i Gains_Watch_App_Store.mobileprovision | pbcopy`).
3. **App Store Connect > App Privacy**: Health and Fitness data stays in Apple Health on the
   device; Gains does not collect it, so the answers there do not change. The App Review notes
   can say the watch app uses HealthKit to run a workout session during a workout logged in Gains.
