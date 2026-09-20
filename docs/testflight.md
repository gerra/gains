# Shipping Gains to TestFlight

How a commit becomes a build that testers can install. The short version lives in the
[README](../README.md#testflight); this page is the complete checklist.

- [One-time setup](#one-time-setup)
- [Versions and build numbers](#versions-and-build-numbers)
- [Upload from Xcode](#upload-from-xcode)
- [Upload from the command line](#upload-from-the-command-line)
- [Upload from GitHub Actions](#upload-from-github-actions)
- [Upload from Xcode Cloud](#upload-from-xcode-cloud)
- [Adding testers](#adding-testers)
- [What the repository already takes care of](#what-the-repository-already-takes-care-of)
- [Troubleshooting](#troubleshooting)

## One-time setup

1. **Apple Developer Program.** The signing team in
   [`iosApp/Configuration/Config.xcconfig`](../iosApp/Configuration/Config.xcconfig) (`TEAM_ID`)
   needs a paid membership; free accounts cannot upload to TestFlight.
2. **Sign in to Xcode.** Xcode > Settings > Accounts > **+**, add the Apple ID, and confirm the
   team appears. Then under **Manage Certificates** add an **Apple Distribution** certificate if
   the team does not have one yet.
3. **Register the app identifier.** Building to a physical device once with automatic signing
   registers `app.gains.Gains` for the team. Otherwise add it by hand at
   [developer.apple.com > Identifiers](https://developer.apple.com/account/resources/identifiers/list)
   with no extra capabilities.
4. **Create the App Store Connect record.** [App Store Connect > Apps](https://appstoreconnect.apple.com/apps)
   > **+** > **New App**: platform iOS, name *Gains*, primary language, bundle id
   `app.gains.Gains`, any SKU (for example `gains-ios`). Nothing else has to be filled in for
   TestFlight.
5. **Test information.** In the app's **TestFlight** tab, fill in **Test Information**: a beta
   description, a feedback email and the review contact. External groups cannot be created
   without it.

## Versions and build numbers

Both numbers live in `Config.xcconfig` and flow into `Info.plist` through build settings:

| Setting | Meaning | When to change |
|---------|---------|----------------|
| `MARKETING_VERSION` | The version testers see, e.g. `1.0` | When a release is worth a new version. |
| `CURRENT_PROJECT_VERSION` | The build number | **Before every upload.** App Store Connect rejects a build number it has already seen for that version. |

Both can be overridden on the command line, which is how the GitHub workflow stamps each
upload with its run number:

```bash
xcodebuild ... MARKETING_VERSION=1.1 CURRENT_PROJECT_VERSION=42
```

If builds come from both Xcode and the workflow, keep the numbers moving in one direction: a
manual upload should use a build number above the latest workflow run number.

## Upload from Xcode

1. Bump `CURRENT_PROJECT_VERSION` in `Config.xcconfig` and commit it.
2. `open iosApp/iosApp.xcodeproj`, choose the **iosApp** scheme and the
   **Any iOS Device (arm64)** destination. Archiving needs a device destination, not a simulator.
3. **Product > Archive**. The *Compile Kotlin Framework* phase builds the Compose framework
   with Gradle first, so the first archive takes a while.
4. In the Organizer window that opens, select the archive and press **Distribute App** >
   **TestFlight & App Store** (older Xcode: **App Store Connect** > **Upload**). Keep the
   defaults: upload symbols, manage version and build number, automatic signing.
5. App Store Connect emails when processing is done, usually within 5 to 30 minutes. The
   build then appears under **TestFlight > iOS Builds** and can be added to a group.

## Upload from the command line

The same steps without the Organizer. Signing is automatic, so the Mac must be signed in to
the team in Xcode.

```bash
cd iosApp

# 1. Archive (Gradle builds the Kotlin framework as part of this).
xcodebuild archive \
  -project iosApp.xcodeproj -scheme iosApp -configuration Release \
  -destination "generic/platform=iOS" \
  -archivePath build/Gains.xcarchive \
  -allowProvisioningUpdates \
  CURRENT_PROJECT_VERSION=42

# 2. Export Gains.ipa, signed for the App Store, into build/export.
xcodebuild -exportArchive \
  -archivePath build/Gains.xcarchive \
  -exportOptionsPlist ExportOptions.plist \
  -exportPath build/export \
  -allowProvisioningUpdates

# 3. Upload. Either open build/export/Gains.ipa in the Transporter app (Mac App Store), or
#    validate and upload with an App Store Connect API key:
xcrun altool --validate-app -f build/export/Gains.ipa -t ios --apiKey KEY_ID --apiIssuer ISSUER_ID
xcrun altool --upload-app   -f build/export/Gains.ipa -t ios --apiKey KEY_ID --apiIssuer ISSUER_ID
```

`altool` looks for the key file at `~/.private_keys/AuthKey_KEY_ID.p8`. Setting `destination`
to `upload` in [`ExportOptions.plist`](../iosApp/ExportOptions.plist) and adding
`-authenticationKeyPath/-authenticationKeyID/-authenticationKeyIssuerID` to the export
command uploads in step 2 instead, which is what the workflow does.

## Upload from GitHub Actions

The [TestFlight workflow](../.github/workflows/testflight.yml) runs on a macOS runner, signs
with material stored as repository secrets, and uploads with an App Store Connect API key.
It runs on every push to `main` that touches the app (docs, samples and the Android- and
desktop-only sources are ignored), on any tag such as `v1.0.0`, and by hand from
**Actions > TestFlight > Run workflow** (optionally with a build number). The workflow run
number becomes the build number, so nothing in `Config.xcconfig` has to change. Uploads are
serialized by a concurrency group, so merging two pull requests back to back queues the second
build instead of colliding.

Add these six secrets under **Settings > Secrets and variables > Actions**:

| Secret | What it is | How to get it |
|--------|------------|---------------|
| `IOS_DISTRIBUTION_CERT_P12_BASE64` | The Apple Distribution certificate with its private key | Keychain Access > My Certificates > right-click *Apple Distribution: …* > **Export** as `.p12` with a password. Then `base64 -i cert.p12 \| pbcopy`. |
| `IOS_DISTRIBUTION_CERT_PASSWORD` | The password chosen during that export | |
| `IOS_APP_STORE_PROFILE_BASE64` | An **App Store Connect** distribution provisioning profile for `app.gains.Gains` | [developer.apple.com > Profiles](https://developer.apple.com/account/resources/profiles/list) > **+** > *App Store Connect* > pick the app id and the distribution certificate > name it (e.g. *Gains App Store*) > download. Then `base64 -i Gains_App_Store.mobileprovision \| pbcopy`. |
| `APP_STORE_CONNECT_API_KEY_ID` | Key ID of an App Store Connect API key | [App Store Connect > Users and Access > Integrations > App Store Connect API](https://appstoreconnect.apple.com/access/integrations/api) > **Team Keys** > **+**. Role **App Manager** (or **Developer**). |
| `APP_STORE_CONNECT_API_ISSUER_ID` | The Issuer ID shown at the top of that page | |
| `APP_STORE_CONNECT_API_KEY_P8_BASE64` | The `.p8` private key of that API key | Download it right after creating the key; Apple offers it only once. Then `base64 -i AuthKey_XXXXXXXXXX.p8 \| pbcopy`. |

The certificate and profile expire after a year; renew them and update the two secrets when
the workflow starts failing at the archive step. Everything is installed into a throwaway
keychain and removed at the end of the run.

Creating the certificate, profile and API key is the only part that touches a Mac (Keychain
Access exports the `.p12`). After that, every upload runs on GitHub's macOS runners; commit,
push a tag or press *Run workflow* from any machine.

**Caching.** The first run downloads the Gradle distribution, all dependencies and the
Kotlin/Native toolchain and takes 30 to 40 minutes. The workflow keeps the Gradle home
(`gradle/actions/setup-gradle` with `cache-read-only: false`, because tag and manual runs are
not on the default branch where the action writes by default) and `~/.konan`
(`actions/cache`, keyed on `gradle/libs.versions.toml`). With both warm and Gradle's build
cache from `gradle.properties`, a later run recompiles only the changed Kotlin. A Kotlin
upgrade in the version catalog fetches a fresh toolchain once. GitHub evicts caches that go
unused for a week and keeps at most 10 GB per repository, so a run after a long pause starts
cold again.

## Upload from Xcode Cloud

[Xcode Cloud](https://developer.apple.com/xcode-cloud/) is Apple's hosted CI. It clones the
repository onto an Apple-run Mac, archives the app, signs it with certificates Apple manages
itself and hands the build to TestFlight. Nothing has to be exported from a Mac and no
repository secrets are needed, so it is the simplest way to ship without a Mac.

Everything the build machine lacks is set up by
[`iosApp/ci_scripts/ci_post_clone.sh`](../iosApp/ci_scripts/ci_post_clone.sh), which Xcode
Cloud runs automatically because it sits in a `ci_scripts` folder next to the `.xcodeproj`:

- **A JDK.** The *Compile Kotlin Framework* phase runs Gradle, and Xcode Cloud machines ship
  without Java. The script installs OpenJDK 17 with Homebrew and registers it with
  `/usr/libexec/java_home`, which is how Gradle finds it.
- **A cache between builds.** Xcode Cloud keeps the derived data folder from one build to the
  next unless the workflow's *Clean* option is on. The script stores the JDK, the Gradle home
  (`~/.gradle`: wrapper, dependencies, build cache) and the Kotlin/Native toolchain
  (`~/.konan`) in there and symlinks them into place. The first build fills the cache and takes
  20 to 40 minutes; later builds skip the downloads and reuse Gradle's build cache, so only
  the changed Kotlin recompiles. The *Post-Clone* step log prints what the cache holds, and
  ticking *Clean* once in the workflow settings resets it.

One-time setup, from Xcode on any Mac or from the web:

1. **Connect the app.** Xcode: **Product > Xcode Cloud > Create Workflow**, or App Store
   Connect > *Gains* > **Xcode Cloud** > **Get Started**. Grant Xcode Cloud access to the
   `gerra/gains` GitHub repository when asked.
2. **Edit the default workflow** (App Store Connect > *Gains* > Xcode Cloud > **Manage
   Workflows**, or the Cloud tab of Xcode's Report navigator):
   - **Environment**: latest Xcode and macOS. Leave *Clean* off so the cache survives.
   - **Start Conditions**: *Branch Changes* on `main`, or *Tag Changes* for `v*` to mirror the
     GitHub workflow. Drop *Pull Request Changes* unless every PR should produce a build.
   - **Actions**: one **Archive** action, platform iOS, scheme `iosApp`, deployment preparation
     **TestFlight (Internal Testing Only)** or **TestFlight and App Store**.
   - **Post-Actions**: **TestFlight Internal Testing** with the internal group, so every green
     build reaches testers without a click. External groups can be added the same way once
     the first build has passed Beta App Review.
3. **Start a build** from the workflow page or by pushing to the branch. Results appear in
   App Store Connect and in Xcode under the Cloud tab; a failed build shows the log of the
   step that broke, which for this project is almost always the Gradle phase.

Xcode Cloud sets the build number itself (an increasing counter per workflow), so
`CURRENT_PROJECT_VERSION` in `Config.xcconfig` is ignored there. If builds also come from
Xcode by hand or from the GitHub workflow, keep the other build numbers above the Xcode Cloud
counter, or let one of the three be the only uploader.

## Adding testers

- **Internal testing.** App Store Connect > TestFlight > **Internal Testing** > **+**. Members of
  the App Store Connect team (up to 100) get every build immediately, no review needed. Turn on
  **Automatic distribution** so new uploads reach them without a click.
- **External testing.** **External Testing** > **+** creates a group for up to 10,000 people.
  Add testers by email, or enable the **Public Link** and paste it into the README under
  [Join the beta](../README.md#join-the-beta). The first build for an external group goes
  through Beta App Review (usually a day); later builds with the same version skip it unless
  Apple asks.
- Builds expire 90 days after upload; a new upload resets the clock for testers. TestFlight
  shows the **What to Test** notes entered per build, so write a line or two.

## What the repository already takes care of

- **Shared scheme** ([`iosApp.xcscheme`](../iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme))
  so `xcodebuild -scheme iosApp` works on a clean checkout and on CI.
- **Team, bundle id, version and build number** come from `Config.xcconfig`; the project
  file only references them.
- **Export compliance.** `ITSAppUsesNonExemptEncryption` is `false` in `Info.plist`: the app
  makes no network calls, so every build is available to testers right after processing
  instead of waiting on the *Missing Compliance* prompt.
- **Privacy manifest** ([`PrivacyInfo.xcprivacy`](../iosApp/iosApp/PrivacyInfo.xcprivacy))
  declares no tracking, no collected data, and the required-reason APIs the Kotlin/Native
  runtime, Skiko and SQLite reach from C, which keeps uploads free of ITMS-91053 warnings.
- **App icon** without an alpha channel. App Store Connect rejects a 1024×1024 icon that has
  one (ITMS-90717), so keep `AppIcon.png` an opaque RGB PNG when replacing it.
- **Kotlin framework without the Android SDK.** The Xcode build phase runs Gradle with
  `-Pgains.android=false`, so a Mac (or runner) needs only a JDK, not Android Studio.
- **Export options** ([`ExportOptions.plist`](../iosApp/ExportOptions.plist)) for command-line
  exports: App Store Connect method, automatic signing, symbols uploaded.

## Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| *No profiles for 'app.gains.Gains' were found* | Xcode is not signed in to the team, or the app id is not registered. Sign in (Settings > Accounts), then archive again with automatic signing, which creates the profile. |
| *The bundle version must be higher than the previously uploaded version* (ITMS-90189) | Bump `CURRENT_PROJECT_VERSION` or pass a larger `build_number` to the workflow. |
| *Missing Compliance* on the build in App Store Connect | `ITSAppUsesNonExemptEncryption` fell out of `Info.plist`. Put it back; the answer for this app is *No*. |
| ITMS-90717 *Invalid App Store Icon* | The icon PNG gained an alpha channel. Re-export it as opaque RGB. |
| ITMS-91053 *Missing API declaration* email | A new dependency uses a required-reason API. Add the category and reason to `PrivacyInfo.xcprivacy`. |
| Xcode Cloud build fails in *Compile Kotlin Framework* with `Unable to locate a Java Runtime` | `ci_scripts/ci_post_clone.sh` did not run or Homebrew failed. Check the *Post-Clone* step log in the build; the script must stay executable (`chmod +x`) and next to `iosApp.xcodeproj`. |
| Every Xcode Cloud build is slow and the *Post-Clone* log says the cache is empty | *Clean* is on in the workflow's Environment settings, or the cache was evicted. Turn *Clean* off; the next build refills it. |
| Xcode Cloud Gradle phase fails with a corrupt-cache or lock error | Tick *Clean* in the workflow, run once, then untick it. |
| *Compile Kotlin Framework* fails with `java: command not found` | Xcode's script phase does not see the shell's `PATH`. Install a JDK 17+ that registers with `/usr/libexec/java_home`, or symlink it into `/Library/Java/JavaVirtualMachines`. |
| Upload fails with *SDK version issue. This app was built with the iOS N SDK* | Apple only accepts apps built with the newest major SDK. The runner's default Xcode is too old: move `runs-on` in the workflow to the newest `macos-*` image (see the Xcode table at [actions/runner-images](https://github.com/actions/runner-images)). |
| Workflow fails at *Install signing certificate* | The `.p12` password does not match, or the secret was pasted with line breaks. Re-export the certificate and copy the base64 output in one go. |
| Workflow archives fine but the export fails with a profile error | The profile in `IOS_APP_STORE_PROFILE_BASE64` was made for another certificate or app id, or has expired. Create a fresh App Store Connect profile that includes the same distribution certificate. |
| The upload succeeds but the build never appears | Processing can take up to an hour. If App Store Connect emails about a problem instead, the message names the ITMS code above. |
