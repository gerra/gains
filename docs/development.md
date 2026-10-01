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
python3 -m unittest discover -s tools -p 'test_*.py'            # the release, deploy, TestFlight and pruning scripts
```

`-Pgains.android=false` configures the build without the Android Gradle Plugin, which is what
CI does on runners without an SDK. Everything else is unaffected.

## Running it

Requirements: JDK 17 or newer. Android additionally needs Android Studio with SDK 37 (Quail 4,
2026.1.4, or newer, for AGP 9.4), iOS needs
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
workout from the Home tab. The database lives in `~/.gains/gains.db`. A signed-in account's token
is not in it but in the OS keyring (docs/sync.md, "What the client does"): the macOS login
keychain, the Secret Service on Linux (`secret-tool`, from `libsecret-tools` on Debian and Ubuntu)
or DPAPI on Windows (`~/.gains/token.dpapi`). Without one, on a headless box, it stays in the
database.

Sign-in on the desktop goes through the browser (docs/sync.md, "Signing in"). The build passes
five Gradle properties to the app as system properties, for `run` and the installers alike:
`gains.serverUrl` (the sync server, `https://api.gains.gerra.sh` in `gradle.properties`),
`gains.googleDesktopClientId` (the **Desktop app** OAuth client, also in `gradle.properties`),
`gains.googleDesktopClientSecret`, `gains.appleServicesId` and `gains.passwordSignIn`. The secret stays out of the
repository even though Google documents it as not secret for installed apps: keep it in
`~/.gradle/gradle.properties` or pass it with `-P`. **Sign in with Google** shows only when both
the id and the secret are set. **Sign in with Apple** runs through the server's web flow and shows
once `gains.appleServicesId` is set, to the server's `APPLE_SERVICES_ID`; set it in
`gradle.properties` once the server has it, since until then `/auth/apple/start` answers 503.
**Sign in with email** (an email address and a password) shows once `gains.passwordSignIn` is
`true`; set it once the server has its mail account (`SMTP_*` in `secrets/.env`, see
`secrets/README.md`), since until then its routes answer 503.

```bash
./gradlew :composeApp:run -Pgains.android=false -Pgains.googleDesktopClientSecret=GOCSPX-…
```

### Android

Open the project in Android Studio and run the `androidApp` configuration, or build an APK:

```bash
./gradlew :androidApp:assembleDebug
```

`androidApp` is the application: the application id, the version, signing, R8 and lint, and no
code of its own. The Kotlin, the manifest and the resources are in `composeApp`, an Android
library it wraps (AGP 9 no longer builds an application in a Kotlin Multiplatform module).

A debug build carries version code 1 and the version name from `MARKETING_VERSION` in
`iosApp/Configuration/Config.xcconfig`, the same one the iOS build shows. Release bundles for
Play come from the release workflow, signed with the upload key and stamped with the run
number: [docs/play.md](play.md). A release build runs R8, which a debug build doesn't, so a
crash only the release build has is usually a missing keep rule for
`androidApp/proguard-rules.pro`; CI's Android job builds the release bundle on every pull
request that touches the code, so an R8 error shows there.

The app registers as a handler for CSV files, so exports shared from other apps open directly in
the import preview. Several files can be shared at once.

Sign-in on Android goes through Credential Manager for Google and the server's web flow for Apple
(docs/sync.md, "Signing in"). The build turns four Gradle properties into string resources
(`resValue` in `androidApp/build.gradle.kts`, over the empty defaults in `composeApp`'s
`res/values/sign_in_config.xml`): `gains.serverUrl` (the sync server), `gains.googleWebClientId`,
the Google **Web application** OAuth client, which is Credential Manager's `serverClientId` and
the audience of the tokens the phone sends, so the server lists it in `GOOGLE_CLIENT_IDS` too,
`gains.appleServicesId`, the same Services ID as the desktop's, which the server has as
`APPLE_SERVICES_ID`, and `gains.passwordSignIn`, `true` once the server has its mail account
for the email form. Each button shows only once its property is set, in `gradle.properties` or
with `-P`. The Google Cloud project also needs an **Android** client with the package name
`app.gains` and the SHA-1 of every signing key the app is built with (debug, upload, Play App
Signing), or the chooser refuses the app.

```bash
./gradlew :androidApp:assembleDebug -Pgains.googleWebClientId=…apps.googleusercontent.com -Pgains.appleServicesId=app.gains.Gains.web
```

**Sign in with Apple** ends on the App Link `https://gains.gerra.sh/auth/done`. Android opens it
in the app only after checking `https://gains.gerra.sh/.well-known/assetlinks.json` for the SHA-256
of the key the installed app is signed with, so `site/.well-known/assetlinks.json` lists the Play
App Signing key and the upload key; add the debug key's for a debug build
(`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`),
then `python3 tools/deploy_server.py site`. Until the fingerprint is there the browser shows the
site's `/auth/done` page, whose "Open Gains" button finishes the sign-in the same way. To check a
verified install: `adb shell pm get-app-links app.gains` says `verified` next to `gains.gerra.sh`.

### iOS

```bash
open iosApp/iosApp.xcodeproj
```

Pick a device or simulator and run. The signing team, bundle id, version and build number live
in `iosApp/Configuration/Config.xcconfig`; change `TEAM_ID` there to build under another team.
The *Compile Kotlin Framework* phase runs Gradle with `-Pgains.android=false`, so a Mac needs a
JDK but no Android SDK. The Xcode project wraps the `ComposeApp` framework in SwiftUI and
registers the app as a CSV handler, so **Open in Gains** appears in the share sheet.

To check that the project builds without opening Xcode or having a signing team, run what the
[iOS workflow](../.github/workflows/ios.yml) runs on pull requests: an unsigned simulator build.

```bash
python3 tools/testflight.py build-simulator
```

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

**Research rounds.** Once a week the box runs [`tools/research_run.py`](../tools/research_run.py)
(`deploy/gains-research.timer`): Claude Code, headless, finds out what lifters are asking gym
apps for, scores each wish, builds the top five and opens them as stacked pull requests, with the
reasoning, the sources and the cost in every description. What it learned stays under
[`research/`](../research) so the next round builds on it; [`research/README.md`](../research/README.md)
has the setup and the files. Merging is the approval.

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
[`Sync.sq`](../shared/src/commonMain/sqldelight/app/gains/db/Sync.sq), a document class in
[`Documents.kt`](../shared/src/commonMain/kotlin/app/gains/sync/Documents.kt) and its kind in
[`SyncKinds`](../protocol/src/commonMain/kotlin/app/gains/sync/Kinds.kt), which the server checks;
`SyncStoreTest` checks that what the repositories write is what the triggers record.

**The sync server.** Lives in [`server/`](../server) and is deployed by the
[Deploy server workflow](../.github/workflows/deploy.yml) on a push to `main` that touches it or
the wire format in [`protocol/`](../protocol), the one module of the app's it is built from:
tests, `installDist`, rsync to the Hetzner box, the systemd unit from `deploy/`, a smoke test.
The steps are [`tools/deploy_server.py`](../tools/deploy_server.py), which also runs on the
box for the install itself. The [Deploy site and nginx workflow](../.github/workflows/deploy-site.yml)
pushes `deploy/nginx/` and `site/` (gains.gerra.sh) the same way; from a laptop,
`python3 tools/deploy_server.py secrets` pushes the secrets
([secrets/README.md](../secrets/README.md) lists them). A server-only change cuts no release branch. Design and routes: [docs/sync.md](sync.md).

**Modules.** Five: `protocol` (the sync wire format), `shared` (everything below the UI),
`composeApp` (the UI and the entry points), `androidApp` (the Android application around
`composeApp`, no code of its own) and `server`. Don't split `composeApp` or `shared` into
feature modules by taste or by screen count. Split when one of these is seen concretely, and say
which in the pull request:
- a feature with an owner of its own, or reused outside this app;
- a change in one feature that recompiles unrelated ones for a noticeable time;
- a dependency cycle between packages that a module boundary would forbid;
- a feature whose tests need a large unrelated graph (the whole database and Koin) to run.

`protocol` was split out for the second reason ([launch-plan item 37](launch-plan.md#37-module-boundaries-a-wire-protocol-module-and-when-to-split-features)):
every change to the app's `shared` code rebuilt and redeployed the server. `androidApp` is not a
feature split but a build one ([launch-plan item 38](launch-plan.md#38-android-an-app-module-of-its-own-then-agp-9-and-compilesdk-37)):
AGP 9 refuses `com.android.application` in a Kotlin Multiplatform module.

**Dependencies and actions.** Every `uses:` in `.github/workflows/` names a full commit SHA, with
the version it is as a trailing comment (`actions/checkout@<sha> # v4.4.0`), because a tag can be
moved to other code and these workflows hold the signing keys and the deploy key. To bump one,
look up the new tag's commit (`git ls-remote --tags https://github.com/<owner>/<repo>.git`,
taking the `^{}` line for an annotated tag) and change the SHA and the comment together; the
actions of one repository (`gradle/actions/…`, `github/codeql-action/…`) move together.
[Dependency graph](../.github/workflows/dependency-graph.yml) submits the Gradle dependencies on
every push to `main`, so Insights → Dependency graph lists them and the Dependabot alerts under
Security cover them; on a pull request, CI's dependency review fails when the change brings in a
dependency with a known advisory. [CodeQL](../.github/workflows/codeql.yml) scans the Kotlin on
pull requests, on `main` and weekly, into Security → Code scanning.
A pull request that only touches the docs, the site, `tools/`, `deploy/`, Markdown or the other
workflows skips CI's Gradle jobs and the CodeQL analysis, and one that changes no build file or
version catalog skips the dependency review ([`tools/changes.py`](../tools/changes.py)). They
skip with a job-level condition, which GitHub reports as passed, so any of them can be made a
required check; the script tests run on every pull request, and pushes to `main` run everything.
GitHub's own *Automatic dependency submission* (Settings → Code security) stays off: the two
workflows above already submit the Gradle graph, and it would resolve it a third time on every push.
[Dependabot](../.github/dependabot.yml) opens its pull requests on Mondays: one for the actions
(SHA and version comment together), and for Gradle one per group (`kotlin-compose`, `androidx`,
`ktor`, `kotlinx`, `everything-else`), so a round is a few pull requests, each reviewed by CI.
Kotlin and Compose Multiplatform get patches only, and Koin and AGP no majors: those are
deliberate passes like [launch-plan item 27](launch-plan.md#27-a-dependency-modernization-pass).
To take one of those anyway, change the version in
`gradle/libs.versions.toml` by hand; to hold something else back, add an `ignore` entry there.
There is no Gradle dependency verification (`gradle/verification-metadata.xml`): Dependabot
doesn't regenerate that file, so every Gradle pull request from it would fail until someone
rewrote the file by hand ([launch-plan item 29](launch-plan.md#29-supply-chain-dependabot-and-gradle-dependency-verification-where-practical)).

**Pruning branches.** `python3 tools/prune_branches.py list` shows the branches on origin whose
work is already on `main`, and `prune` deletes them. Merged `release/*` branches go too: once a
release branch is gone, `tools/release.py` works from its `testflight/<version>/<build>` tags.

**Licenses.** Gains is MPL-2.0 ([`LICENSE`](../LICENSE)), and [`NOTICE.md`](../NOTICE.md) lists
the files that aren't. A file that comes from elsewhere keeps its own license and header and gets
a row there before it is merged; a work that ships in the app (not a library) also gets an entry in
`ui/licenses/ThirdPartyWorks.kt`, which `LicensesTest` checks against `NOTICE.md`. The libraries
need nothing by hand: the AboutLibraries Gradle plugin writes each target's list, with its
licenses, into that target's Compose resources as `files/libraries.json`, and the Open-source
licenses screen (Settings → About) shows it. Its strict mode fails the build on a license outside
`allowedLicenses` in `composeApp/build.gradle.kts` (Apache-2.0, MIT, and Google's Android SDK
License for Play services): a dependency under another license is a decision to make, and to
record in the launch plan, before it goes in.

**Exercise drawings.** About 115 built-in exercises show a start and end drawing under "How to do it",
adapted from [Everkinetic](https://github.com/everkinetic/data) (CC BY-SA 4.0) by
`tools/exercise_demos.py` (`python3 tools/exercise_demos.py`, needs Pillow and CairoSVG). Its
`MATCHES` table is written by hand: an exercise gets a drawing only when Everkinetic draws that
exercise, not a near relative, and everything else keeps the video search alone. The script renders
each pair from Everkinetic's SVGs, which hold the whole drawing on a canvas the pair shares (their
PNGs are cropped closer, each on its own, and cut feet and plates off), crops the pair together, and
turns the white transparent, so the app tints the lines to the theme. The drawings stay under
CC BY-SA 4.0 ([NOTICE.md](../NOTICE.md)); a change to one is shared under it too. The photos from
free-exercise-db that used to be there were dropped in
[launch-plan item 39](launch-plan.md#39-open-source-under-mpl-20-the-license-its-scope-the-notices-no-personal-data):
they were scraped off the internet upstream, and nobody could license them.

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

- The iOS app compiles to Kotlin/Native klibs on any host, and CI does so on every pull request
  that touches the code.
  The iOS workflow builds the Xcode project for the simulator on a hosted macOS runner, unsigned,
  on pull requests and pushes to `main` that touch more than the docs, the site, the server, the
  deploy files or `tools/`; only archiving needs the signing material (the TestFlight workflow).
  Running it still needs Xcode on a Mac or a device.
- Sign-in is wired up on iOS, the desktop and Android, Apple and Google on each (Android once
  `gains.googleWebClientId` and `gains.appleServicesId` are set), and an email address and
  password on all three once the server has a mail account. [docs/launch-plan.md](launch-plan.md)
  has the rest.

## Roadmap

Everything still to do, before and after going public, lives in one file:
[docs/launch-plan.md](launch-plan.md), with the test plan next to it. Done so far:

- [x] Self-hosted sync server and the client that speaks to it ([docs/sync.md](sync.md))
- [x] Sign in with Apple and with Google on iOS, linking a guest account, deleting an account,
      sync status in Settings and the token in the Keychain ([docs/auth-plan.md](auth-plan.md))
- [x] Sign in with Apple and with Google on Android and the desktop, Apple through the server's
      web flow, with the token in the Android Keystore and the OS keychain
      ([launch plan](launch-plan.md), items 9–17)
- [x] Email and password accounts on all three platforms, switched on with the server's mail
      account ([launch plan](launch-plan.md), item 18)

## Contributing

Issues and pull requests are welcome, and are accepted under MPL-2.0, the license they are
contributed to; there is no CLA and no sign-off. Keep the shared module free of platform code, add a test
for anything the parser or an insight rule should handle, and run what CI runs before opening a
PR: `./gradlew :shared:desktopTest :composeApp:desktopTest :server:test -Pgains.android=false`,
plus the iOS compile and the `tools/` tests listed under [the commands at the top of this page](#development).

## Built with

- [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) for the UI
- [SQLDelight](https://sqldelight.github.io/sqldelight/) for typed SQLite
- [Koin](https://insert-koin.io/) for dependency injection
- [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime) for dates without tears
- [react-native-body-highlighter](https://github.com/HichamELBSI/react-native-body-highlighter) (MIT,
  © 2022 ELABBASSI Hicham) for the body drawing behind the muscle map, ported to Compose path data;
  its notice is in [NOTICE.md](../NOTICE.md)
- [Everkinetic](https://github.com/everkinetic/data) (CC BY-SA 4.0) for the start and end drawings
  under "How to do it", adapted ([NOTICE.md](../NOTICE.md))
- [free-exercise-db](https://github.com/yuhonas/free-exercise-db) (public domain, Unlicense) for
  the names of about 180 catalogue exercises ([NOTICE.md](../NOTICE.md))
- [AboutLibraries](https://github.com/mikepenz/AboutLibraries)' Gradle plugin for the list of
  libraries and licenses each build carries
- The README layout borrows from the projects collected in [awesome-readme](https://github.com/matiassingers/awesome-readme)

The versions live in `gradle/libs.versions.toml`. The last deliberate pass over them was
[launch-plan item 27](launch-plan.md#27-a-dependency-modernization-pass): Kotlin 2.4.20, Koin
4.2.2. [Item 38](launch-plan.md#38-android-an-app-module-of-its-own-then-agp-9-and-compilesdk-37)
split the Android application into `:androidApp`, which AGP 9 requires, and moved to AGP 9.4.1
on Gradle 9.8.0, compileSdk 37 (`targetSdk` stays 36) and Compose Multiplatform 1.12.1. Material 3
stays on 1.9.0, its last stable release.
