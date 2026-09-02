# Gains — workout progress analyzer

A local-only Kotlin Multiplatform app that ingests CSV exports from the Liftoff
workout logger and tells you what's actually moving, what's stalled and what's
gone backwards. iOS is the primary target; Android and a desktop (JVM) build
share the same code. There is no backend, no account and no network access.

## Layout

| Module        | Contents |
|---------------|----------|
| `shared/`     | CSV reader and Liftoff parser, domain model, exercise catalogue, import analyzer, SQLDelight persistence, insight engine and analysis code. All pure Kotlin, unit-tested. |
| `composeApp/` | Compose Multiplatform UI (home insights, import preview, lifts, volume, bodyweight, consistency, settings), Canvas charts, and the Android / iOS / desktop entry points. |
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

## How the import works

1. The file is read with a real RFC 4180 parser (quoted notes with commas and line breaks are fine).
2. Rows are grouped into sessions by their `Date` timestamp and sorted; file order is never trusted.
3. Weights are converted from lbs to kg and rounded to 0.25 kg (`132.277357311` → `60 kg`). The
   preview lets you say the file is already in kg if you use a different exporter.
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
- The desktop app was run against a generated one-year export containing every defect from the
  spec, and every screen was exercised: import preview and commit, re-import (idempotent), home
  insights, lift detail charts, volume dashboard, bodyweight entry, consistency calendar, settings.
- `shared` and `composeApp` compile to Kotlin/Native klibs for `iosArm64` and `iosSimulatorArm64`
  (including the UIKit document-picker code). Linking and running the iOS app needs Xcode on a Mac.
- The Android source set (SAF picker, share-sheet intents, SQLDelight Android driver) is written
  against the standard APIs but has not been compiled here, because the environment had no
  Android SDK or access to Google's Maven repository. Open the project in Android Studio to build it.
