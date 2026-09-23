# Development

Recipes for working on Gains: the commands, how releases and deploys run, and how to extend
the importer, the insights, the programs, the schema and the languages.

```bash
./gradlew :shared:desktopTest -Pgains.android=false            # parser, importer, insight and integration tests
./gradlew :composeApp:desktopTest -Pgains.android=false        # UI smoke test that also renders screenshots into composeApp/build/screenshots
./gradlew :composeApp:run -Pgains.android=false                # desktop app
./gradlew :server:test -Pgains.android=false                   # the sync server's routes and a two-device round trip
./gradlew :server:run -Pgains.android=false                    # the sync server on :5003 (needs JWT_SECRET, see secrets/README.md)
./gradlew :shared:compileKotlinIosArm64 :composeApp:compileKotlinIosArm64 -Pgains.android=false  # the iOS compile CI runs on Linux
python3 -m unittest discover -s tools -p 'test_*.py'            # the release, deploy and pruning scripts
```

`-Pgains.android=false` configures the build without the Android Gradle Plugin, which is what
CI does on runners without an SDK. Everything else is unaffected.

## Running it

Requirements: JDK 17 or newer. Android additionally needs Android Studio with SDK 35, iOS needs
Xcode on a Mac (Xcode 26 to upload to App Store Connect, which only takes builds made with the
current iOS SDK).

```bash
git clone https://github.com/gerra/gains.git
cd gains
```

### Desktop

The desktop build is the quickest way to try the app or work on it. It has no dependency on the
Android SDK when you pass `-Pgains.android=false`.

```bash
# run the app
./gradlew :composeApp:run -Pgains.android=false

# open straight into the import preview with the bundled sample export
./gradlew :composeApp:run -Pgains.android=false -Pgains.openFile=samples/liftoff-export.csv

# run the tests
./gradlew :shared:desktopTest -Pgains.android=false
```

Pick **Continue as guest** on first launch, then import a file with the **+** button or log a
workout from the Home tab. The database lives in `~/.gains/gains.db`.

### Android

Open the project in Android Studio and run the `composeApp` configuration, or build an APK:

```bash
./gradlew :composeApp:assembleDebug
```

The app registers as a handler for CSV files, so exports shared from other apps open directly in
the import preview. Several files can be shared at once.

### iOS

```bash
open iosApp/iosApp.xcodeproj
```

Pick a device or simulator and run. The signing team, bundle id, version and build number live
in `iosApp/Configuration/Config.xcconfig`; change `TEAM_ID` there to build under another team.
The *Compile Kotlin Framework* phase runs Gradle with `-Pgains.android=false`, so a Mac needs a
JDK but no Android SDK. The Xcode project wraps the `ComposeApp` framework in SwiftUI and
registers the app as a CSV handler, so **Open in Gains** appears in the share sheet.

To put a build on a phone without a Mac and a cable, see [TestFlight](testflight.md).

## Recipes

**Screenshots.** The [Screenshots workflow](../.github/workflows/screenshots.yml) runs the UI test on
a GitHub runner and commits the images under `docs/screenshots`. Trigger it from the Actions tab
after a UI change.

**Releasing.** Every two hours through the day,
[Cut release branch](../.github/workflows/release-branch.yml) branches
`release/<major>.<minor>` off `main` and [Release](../.github/workflows/release.yml) uploads it
through the [TestFlight workflow](../.github/workflows/testflight.yml), which archives the iOS app
on a macOS runner and sends it to App Store Connect, then tags the build, publishes it as a
GitHub release and opens the pull request back to `main`.
[docs/testflight.md](testflight.md#releases-through-the-day) covers the schedule, the secrets and
the manual route through Xcode.

**Adding a connector.** Declare a `ColumnSpec` and a `match` function in
[`CsvConnectors.kt`](../shared/src/commonMain/kotlin/app/gains/connectors/CsvConnectors.kt), register
it in `Connectors`, and add a fixture to `ConnectorsTest`.

**Tuning an insight.** Change the defaults in `InsightThresholds` and adjust the corresponding
case in `InsightEngineTest`. Per-goal overrides live in `GoalTuning`.

**Adding a built-in program.** Add a `program { day { slot(...) } }` block to
[`ProgramCatalogue.kt`](../shared/src/commonMain/kotlin/app/gains/catalogue/ProgramCatalogue.kt);
`ProgramCatalogueTest` checks that every slot points at a catalogue exercise. Progression rules
are `linear`, `double` (reps climb, then weight) or `ladder` (GZCLP-style stages).

**Tuning GZCLP starts, warm-ups and rest.** Every constant lives in
[`Gzclp.kt`](../shared/src/commonMain/kotlin/app/gains/program/Gzclp.kt): the tier fractions of
the estimated 1RM, the warm-up steps, the default bar weight and increments, and the rest ranges.
`GzclpTest` covers the rounding, the "never above a failed weight" rule and warm-up generation.

**Changing the schema.** Edit the `.sq` file and add a `migrations/N.sqm` with the same DDL;
`MigrationTest` upgrades a database from the previous version and compares it with a fresh one.
A new table that should sync also needs its three triggers in
[`Sync.sq`](../shared/src/commonMain/sqldelight/app/gains/db/Sync.sq) and a document class in
[`Documents.kt`](../shared/src/commonMain/kotlin/app/gains/sync/Documents.kt); `SyncStoreTest`
checks that what the repositories write is what the triggers record.

**The sync server.** Lives in [`server/`](../server) and is deployed by the
[Deploy server workflow](../.github/workflows/deploy.yml) on a push to `main` that touches it:
tests, `installDist`, rsync to the Hetzner box, the systemd unit from `deploy/`, a smoke test.
The steps are [`tools/deploy_server.py`](../tools/deploy_server.py), which also runs on the
box for the install itself; from a laptop, `python3 tools/deploy_server.py nginx` pushes the
nginx block and `python3 tools/deploy_server.py secrets` the secrets
([secrets/README.md](../secrets/README.md) lists them). A server-only change cuts no release branch. Design and routes: [docs/sync.md](sync.md).

**Pruning branches.** `python3 tools/prune_branches.py list` shows the branches on origin whose
work is already on `main`, and `prune` deletes them. `release/*` branches are always kept: the
next version number is worked out from them.

**Exercise photos.** `python3 tools/exercise_demos.py` (needs Pillow) matches every catalogue
exercise to a [free-exercise-db](https://github.com/yuhonas/free-exercise-db) entry by its
`// src:` comment, name and aliases, downloads the two photos, scales them to 480 px WebP under
`composeApp/src/commonMain/composeResources/files/exercises/<id>/` and regenerates
`ExerciseDemos.kt`, the table of which exercises have photos and where each came from. Pin a
better photo set in the script's `OVERRIDES`, or list an exercise the database has no photos
for in `SKIP`; `ExerciseDemosTest` checks the table, the files and the catalogue agree. A new
catalogue exercise makes the script stop until it is in one of the two.

## Languages

Every sentence, label and unit the app shows is a string resource:
[`composeApp/src/commonMain/composeResources/values/strings.xml`](../composeApp/src/commonMain/composeResources/values/strings.xml)
is the English reference and `values-ru/strings.xml` the Russian, with plurals (`<plurals>`)
and month and day names (`<string-array>`) alongside the plain strings. Screens read them
with `stringResource`; screen models, which live outside the composition, through `Texts`,
which carries the composition's resource environment to them. The shared module knows no language: the insight engine, the progression hints,
the day planner and the CSV parser return structured values (`InsightDetail`,
`Progression.Hint`, `CsvProblem`) and the UI words them in
[`composeApp/src/commonMain/kotlin/app/gains/ui/i18n/`](../composeApp/src/commonMain/kotlin/app/gains/ui/i18n).
**Settings → Language** picks one, or leaves it to the device. The choice is a preference like
any other (`AppLanguage`, read back through `SettingsRepository`), and `InLanguage` — around the
whole UI — puts it in force as the platform's current locale, which is where the string resources
take theirs from, and then composes everything below it afresh. The app is therefore in the new
language as the chip is tapped, and the back stack and the scroll positions are where they were;
screen models are made anew along with it, since each holds the words it was made with. On Android
13 and up the choice is handed to the system's own per-app language as well, so the workout
notification, whose strings are Android resources rather than Compose ones, follows too; on older
Android that one notification stays in the device's language.
The built-in exercises, programs,
day names and slot notes stay in English in the database and are looked up on the way to the
screen by a key derived from their id (`exercise_bench_press`, `program_gzclp`,
`day_workout_a`), so imports and aliases are unaffected; custom exercises and programs are shown
as typed.

**Adding a language.** Add a `values-xx/strings.xml` with every key of the English file
(`LocalizationResourcesTest` checks the two match and that every built-in exercise, program,
day and slot note is covered), an entry to `AppLanguage` with its tag, a `language_xx` string
naming it in its own words (the same text in every file: each language names itself), the locale to
`composeApp/src/androidMain/res/xml/locales_config.xml`, a `values-xx/strings.xml` under
`composeApp/src/androidMain/res` for the Android notification, and to `CFBundleLocalizations`
in `iosApp/iosApp/Info.plist`.

## Known limitations

- The Android source set is written against the standard APIs but is not compiled in CI, which
  runs without an Android SDK. Open the project in Android Studio to build it.
- The iOS app compiles to Kotlin/Native klibs on any host, and CI does so on every pull request,
  but linking, running and archiving it needs Xcode on a Mac (the TestFlight workflow uses a
  hosted macOS runner for this).
- Sign-in is wired up on iOS only (Apple and Google); Android and the desktop run as guests, so
  their data stays on the device. [docs/launch-plan.md](launch-plan.md) has the rest.

## Roadmap

Everything still to do, before and after going public, lives in one file:
[docs/launch-plan.md](launch-plan.md), with the test plan next to it. Done so far:

- [x] Self-hosted sync server and the client that speaks to it ([docs/sync.md](sync.md))
- [x] Sign in with Apple and with Google on iOS, linking a guest account, deleting an account,
      sync status in Settings and the token in the Keychain ([docs/auth-plan.md](auth-plan.md))

## Contributing

Issues and pull requests are welcome. Keep the shared module free of platform code, add a test
for anything the parser or an insight rule should handle, and run what CI runs before opening a
PR: `./gradlew :shared:desktopTest :composeApp:desktopTest :server:test -Pgains.android=false`,
plus the iOS compile and the `tools/` tests listed under [the commands at the top of this page](#development).

## Built with

- [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) for the UI
- [SQLDelight](https://sqldelight.github.io/sqldelight/) for typed SQLite
- [Koin](https://insert-koin.io/) for dependency injection
- [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime) for dates without tears
- [react-native-body-highlighter](https://github.com/HichamELBSI/react-native-body-highlighter) (MIT,
  © 2022 ELABBASSI Hicham) for the body drawing behind the muscle map, ported to Compose path data
- [free-exercise-db](https://github.com/yuhonas/free-exercise-db) (public domain, Unlicense) for
  the exercise photos and for the names of about 180 catalogue exercises
- The README layout borrows from the projects collected in [awesome-readme](https://github.com/matiassingers/awesome-readme)
