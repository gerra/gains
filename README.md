# Gains — training log and progress analyzer

A Kotlin Multiplatform app that keeps your training history, lets you log and edit
workouts, imports history from other apps through connectors, and tells you what's
actually moving, what's stalled and what's gone backwards. iOS is the primary target;
Android and a desktop (JVM) build share the same code. Data lives on the device; a
self-hosted sync server is planned.

## Layout

| Module        | Contents |
|---------------|----------|
| `shared/`     | Import connectors (Liftoff, Strong, Hevy, generic CSV) over a shared row-per-set parser, domain model, exercise catalogue, import analyzer, SQLDelight persistence, insight engine and analysis code. All pure Kotlin, unit-tested. |
| `composeApp/` | Compose Multiplatform UI (home insights, history with a workout editor, import preview, lifts, volume, bodyweight, settings), Canvas charts, and the Android / iOS / desktop entry points. |
| `iosApp/`     | Xcode project wrapping the `ComposeApp` framework in SwiftUI. Registers the app as a CSV handler so "Open in Gains" appears in the share sheet. |

## Design

The UI follows the look of current training apps: a dark-first palette (deep charcoal
surfaces with one electric accent), large numerals for the figures that matter, rounded cards,
a floating pill navigation bar and smooth, animated Canvas charts (monotone-cubic curves with
gradient fills, rounded stacked bars, a calendar heat-map, inline sparklines on the lifts list).
Appearance can be switched between Dark, Light and System in Settings. All charts are drawn
with Compose `Canvas`; there is no charting library.

## Building

Requirements: JDK 17+, Android Studio (Android SDK 35) for Android, Xcode 15+ for iOS.

```bash
./gradlew :shared:desktopTest                     # parser, importer, insight and integration tests
./gradlew :composeApp:run                         # desktop app (handy for development)
./gradlew :composeApp:run -Pgains.openFile=export.csv   # open straight into the import preview
./gradlew :composeApp:assembleDebug               # Android APK
open iosApp/iosApp.xcodeproj                      # iOS: build & run from Xcode (fill TEAM_ID in iosApp/Configuration/Config.xcconfig)
```

On a machine without an Android SDK (a CI box that only runs the shared tests, for
example) pass `-Pgains.android=false` to configure the build without the Android
Gradle Plugin. Everything else is unaffected.

## Accounts and sync

On first launch the app asks how to continue. **Continue as guest** keeps everything in the local
database. **Continue with Google / Apple** are present but disabled: they light up once
`AuthConfig` (see `shared/src/commonMain/kotlin/app/gains/auth/Account.kt`, provided in
`SharedModule`) carries a Google client id, an Apple service id and the base URL of the sync
server. The token exchange against the server is left as a TODO in `AccountRepository` for when
the Hetzner backend exists. Settings shows the current account and lets you return to the
sign-in screen; local data is kept.

## Connectors

Every source implements `ImportConnector` (`shared/src/commonMain/kotlin/app/gains/connectors`):
it recognises a file by its header and turns it into sessions. The registry in `Connectors`
auto-detects the format, so the user just picks files. Shipped connectors:

| Connector | Notes |
|-----------|-------|
| Liftoff   | Fixed column order, weights in lbs at float precision. |
| Strong    | Old and new export layouts, per-row weight/distance units when present, `1h 5m` durations. |
| Hevy      | `weight_kg`/`weight_lbs` columns, `5 Jan 2026, 18:30` timestamps, duration from start/end. |
| Workout CSV | Anything with date, exercise, weight and reps columns under common names. |

Adding a connector means a `ColumnSpec` plus a `match` function; the parser, duplicate
detection and outlier handling are shared. Sessions remember their connector in the
`source` column, and workouts logged in the app carry `manual`.

## Logging and editing

The History tab lists every session. Tap one to edit its date, time, duration, exercises,
sets and notes, or delete it; the plus button logs a new workout. Exercises come from the
catalogue or can be created by name. Edits feed straight into the same analyses as imports.

## How the import works

Several exports can be imported at once (multi-select in the Files picker, Android's document
picker, or the desktop dialog; the share sheet accepts several files too). Files are parsed
independently, a session that appears in more than one file is stored once (the copy with more
sets wins), and the merged set is then de-duplicated against itself and the database exactly as a
single file would be.


1. The file is read with a real RFC 4180 parser (quoted notes with commas and line breaks are fine).
2. Rows are grouped into sessions by their timestamp and sorted; file order is never trusted.
3. Weights are converted to kg and rounded to 0.25 kg (`132.277357311 lbs` → `60 kg`). For files
   that do not state their unit the preview lets you choose it.
4. Each set is classified as weighted, bodyweight, isometric or cardio; empty rows are discarded
   and listed in the summary with the reason; durations over 4 hours are dropped as timer errors.
5. Exercise names are mapped onto the built-in catalogue (`Seated Shoulder Press` and
   `Seated Dumbbell Shoulder Press` are one exercise). Unknown names become custom exercises with
   guessed muscle groups; you can merge them into a catalogue exercise in Settings, which also
   records an alias for future imports.
6. Near-duplicate sessions (same day, same exercise list, same set count) are detected both inside
   the file and against what is already stored, and only one copy is kept.
7. Isometric holds more than 5× the usual (lower-median) hold for that exercise are flagged; you
   decide per hold whether to keep or discard them before anything is written.
8. Warm-ups are inferred per exercise per session: weighted sets under 85% of the session's top
   weight. The percentage can be overridden per exercise on the lift's detail screen.
9. Re-importing an overlapping export is safe: sessions already stored are skipped, sessions whose
   content changed are replaced, new ones are added.

## Insights

Insight rules are pure functions in `shared/src/commonMain/kotlin/app/gains/analysis/InsightEngine.kt`
with their thresholds gathered in `InsightThresholds`. The defaults are:

| Insight     | Rule (defaults) |
|-------------|-----------------|
| Regression  | Best performance in the last 30 days is at least 5% below the all-time best set before that window. Weighted lifts compare Epley e1RM; bodyweight lifts reps; holds seconds; cardio distance. |
| Stall       | Top working weight unchanged for 6+ weeks with at least 4 sessions in that time, and the lift was trained in the last 4 weeks. Not reported when a regression is already reported for the lift. |
| Neglect (lift) | Trained 4+ times in a 12-week span, then absent for 4+ weeks. |
| Neglect (muscle) | Averaged 8+ working sets/week over the previous 8 weeks, now under 4 sets/week over the last 2 weeks. |
| Consistency | Sessions/week over the last 4 weeks against the 4 weeks before; ±15% is "steady". |
| Progress    | Best performance in the last 30 days beats the earlier all-time best by at least 2.5%. |

Volume uses 1.0 set credit for primary muscle groups and 0.5 for secondary; under 8 sets/week is
flagged as maintenance and over 22 as likely junk volume.

## Verification status

- `shared` and the desktop target compile and all tests pass on JVM (parser fixtures for
  out-of-order rows, duplicates, corrupt durations, isometric outliers, quoted notes, empty rows;
  insight rules; an in-memory SQLite integration test; a 10,000-row import timing test that
  parses and analyzes in well under a second).
- The desktop app was run against generated exports containing every defect from the
  spec, and every screen was exercised: sign-in, import preview and commit, multi-file and
  re-import (idempotent), home insights, history and the workout editor, lift detail charts,
  volume dashboard, bodyweight entry, settings.
- `shared` and `composeApp` compile to Kotlin/Native klibs for `iosArm64` and `iosSimulatorArm64`
  (including the UIKit document-picker code). Linking and running the iOS app needs Xcode on a Mac.
- The Android source set (SAF picker, share-sheet intents, SQLDelight Android driver) is written
  against the standard APIs but has not been compiled here, because the environment had no
  Android SDK or access to Google's Maven repository. Open the project in Android Studio to build it.
