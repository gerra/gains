# Shipping Gains to Google Play

How a commit becomes an Android build that testers can install, the Play side of
[Releases through the day](testflight.md#releases-through-the-day). Every release round
that uploads to TestFlight also builds the Android app bundle of the same commit and sends it
to the **closed testing** track on Play, with the same build number.

- [One-time setup](#one-time-setup)
- [Versions and version codes](#versions-and-version-codes)
- [Upload from GitHub Actions](#upload-from-github-actions)
- [Upload by hand](#upload-by-hand)
- [Adding testers](#adding-testers)
- [What the repository already takes care of](#what-the-repository-already-takes-care-of)
- [Troubleshooting](#troubleshooting)

## One-time setup

1. **The Play Console app** ([launch plan](launch-plan.md#8-android-package-name-and-play-console-app),
   item 8): an app with the package name `app.gains`, enrolled in **Play App Signing**, which
   is the default for a new app. Play keeps the key that signs what phones install; the
   repository only ever holds the **upload key** below, which Play checks uploads against.
2. **The upload key.** On any machine with a JDK:

   ```bash
   keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   base64 -i upload.jks | pbcopy        # Linux: base64 -w0 upload.jks
   ```

   Keep `upload.jks` and its passwords somewhere safe outside the repository. Play registers
   the upload key from the first bundle it receives; if the key is ever lost, Play Console →
   **App integrity** has a form to reset it.
3. **A service account** that may publish to the app:
   - Google Cloud console, the same project as the sign-in clients → **IAM & Admin → Service
     accounts → Create**. Name it (e.g. `play-upload`), no roles. Then **Keys → Add key →
     JSON**; the file downloads once.
   - **Google Play Android Developer API** enabled for the project (APIs & Services → Library).
   - Play Console → **Users and permissions → Invite new users**, the service account's email
     (`…@….iam.gserviceaccount.com`), with the app permissions **View app information**,
     **Manage testing tracks and edit tester lists** and **Release to testing tracks**, on the
     Gains app only.
4. **The first release.** Play lets the API upload only into an app that already has a
   release: create the closed testing track's first release by hand in the console, with a
   bundle from Android Studio (**Build → Generate Signed App Bundle**, the upload key above)
   or from [`bundleRelease`](#upload-by-hand). That first release also asks for the store
   listing and the content questionnaires, which the API never touches.
5. **The secrets and variables**, under **Settings → Secrets and variables → Actions**. See
   [Upload from GitHub Actions](#upload-from-github-actions) for the table.

## Versions and version codes

| Value | Where it comes from | Meaning |
|-------|---------------------|---------|
| `versionName` | `MARKETING_VERSION` in [`iosApp/Configuration/Config.xcconfig`](../iosApp/Configuration/Config.xcconfig), read by [`composeApp/android.gradle`](../composeApp/android.gradle) | The version testers see, the same one as on TestFlight. The release branch bumps it, so an Android Studio build of `main` carries the last released version. |
| `versionCode` | `-Pgains.versionCode`, which the workflow sets to its run number; `1` without it | Play refuses any upload whose code is not above every one it has seen. The Release workflow's run number is the TestFlight build number too, so a version's builds on both stores carry one number. |

A local `./gradlew :composeApp:assembleDebug` gets code 1, which is fine for a phone over USB.
An upload by hand needs a code above the last workflow run number, and re-releases of a
version belong on the *Release* workflow rather than on *Google Play → Run workflow*, whose own
run number may be lower.

## Upload from GitHub Actions

The [Google Play workflow](../.github/workflows/play.yml) builds on a Linux runner, whose
image has the Android SDK, signs the bundle with the upload key from the repository secrets,
and sends it to the closed testing track with a service account through the Play Developer
API. The [Release workflow](../.github/workflows/release.yml) calls it next to TestFlight for
each release branch; it also runs by hand from **Actions → Google Play → Run workflow**.

**Until the secrets exist the job does nothing.** Its first step checks them, writes which
are missing to the run summary and skips the rest, so the release round keeps shipping to
TestFlight while the Play Console is being set up. Add these five secrets:

| Secret | What it is | How to get it |
|--------|------------|---------------|
| `ANDROID_UPLOAD_KEYSTORE_BASE64` | The upload keystore | `base64 -i upload.jks \| pbcopy` after the `keytool` line above. |
| `ANDROID_UPLOAD_KEYSTORE_PASSWORD` | Its store password | Chosen when the key was generated. |
| `ANDROID_UPLOAD_KEY_ALIAS` | The key's alias | `upload` in the line above. |
| `ANDROID_UPLOAD_KEY_PASSWORD` | The key's password | Chosen when the key was generated; `keytool` defaults it to the store password. |
| `PLAY_SERVICE_ACCOUNT_JSON` | The service account's key file, as is | The JSON downloaded when the key was created. Paste the whole file; line breaks are fine. |

And, as **variables** rather than secrets since they are public and compiled into the app
(the same values as `gains.googleWebClientId` and `gains.appleServicesId` in
`gradle.properties`, which they override for this build; see
[development.md](development.md#android)):

| Variable | What it is |
|----------|------------|
| `GAINS_GOOGLE_WEB_CLIENT_ID` | The Google **Web application** OAuth client id; without it the release build has no Google button. |
| `GAINS_APPLE_SERVICES_ID` | The Apple Services ID (`APPLE_SERVICES_ID` on the server); without it, no Apple button. |
| `GAINS_PASSWORD_SIGN_IN` | `true` once the server has its mail account (`SMTP_*` in `secrets/.env`); without it, no email form. |
| `PLAY_TRACK` | Optional. The track to release to; unset means `alpha`, which is what the API calls the first closed testing track whatever the console shows. A track created by hand goes by its own name. |

The steps are [`tools/play.py`](../tools/play.py), one command each, in the style of
`tools/testflight.py`: `check-secrets`, `paths`, `settings`, `install-signing`, `bundle`,
`upload`, `cleanup`. The Play Developer API is called with the standard library, and
`openssl` on the runner signs the service account's JWT, so nothing has to be installed. The
parts that need no Google are tested by [`tools/test_play.py`](../tools/test_play.py), which
CI runs on every pull request:

```bash
python3 -m unittest discover -s tools -p 'test_*.py'
```

Release builds are shrunk and obfuscated by R8 (`minifyEnabled` in `composeApp/android.gradle`,
[launch-plan item 30](launch-plan.md#30-android-r8-for-release-builds)), so the upload also sends
R8's `mapping.txt` as the bundle's deobfuscation file, and Play Console's crash reports show the
real class and method names. The bundle and its mapping file are kept as a run artifact
(`play-bundle-<number>`) for 90 days, and the key material is removed from the runner at the end
whether or not the upload worked.

## Upload by hand

A signed bundle from the command line, for the console's **Create new release** page:

```bash
./gradlew :composeApp:bundleRelease \
  -Pgains.versionCode=1234 \
  -Pgains.uploadKeystore=/path/to/upload.jks \
  -Pgains.uploadKeystorePassword=… -Pgains.uploadKeyAlias=upload -Pgains.uploadKeyPassword=… \
  -Pgains.googleWebClientId=… -Pgains.appleServicesId=…
# → composeApp/build/outputs/bundle/release/composeApp-release.aab
# → composeApp/build/outputs/mapping/release/mapping.txt
```

Upload the mapping file with the bundle (the release's **App bundle explorer → Downloads →
ReTrace mapping file**), or the crash reports for that version stay obfuscated.

Without `gains.uploadKeystore` the release bundle is unsigned, which is what Android Studio's
**Build → Generate Signed App Bundle** expects: it signs with the key you point it at.

## Adding testers

- **Closed testing** (Play Console → Testing → Closed testing) takes a list of testers by
  email, or a Google Group. Every upload to the track reaches them without a review. Testers
  find the build through the track's **opt-in link**, on the track's page under **Testers →
  How testers join your test**; it opens a page where they accept the test and then install
  from Play. A build takes minutes to a few hours to show up after the upload.
- A new personal developer account must run a closed test with at least **12 testers
  opted in for 14 days in a row** before it can apply for production
  ([launch plan](launch-plan.md#8-android-package-name-and-play-console-app), item 8), so
  start the track early and keep those testers on it.
- **Internal testing** (up to 100 testers, no review, builds available at once) is the
  quicker lane for yourself; set `PLAY_TRACK` to `internal` to send the workflow's builds
  there instead.

## What the repository already takes care of

- **Version name from one place.** `android.gradle` reads `MARKETING_VERSION` out of
  `Config.xcconfig`, so the Play release and the TestFlight build of a branch never disagree.
- **Signing only when asked.** The upload key is used when `gains.uploadKeystore` is passed;
  nothing about it lives in the repository, and a build without it is a normal unsigned
  release build.
- **Skipping without secrets.** The workflow's first step turns the job off, in the summary,
  while the secrets are missing.
- **One upload at a time.** A concurrency group on the job, since two edits of the same app
  collide on Play.
- **Android sources cut releases too.** `tools/release.py` no longer ignores
  `composeApp/src/androidMain` and `shared/src/androidMain` when deciding whether `main`
  has changed since the last release branch, so an Android-only fix ships.

## Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| The run summary says *Play upload skipped* | One of the five secrets is missing or empty. The summary names it. |
| `POST …/edits answered 401` | The service account key does not match the account, or the file in `PLAY_SERVICE_ACCOUNT_JSON` is not a service account key. Create a new key and paste the whole JSON. |
| `POST …/edits answered 403: The caller does not have permission` | The service account is not invited to the app in Play Console, or lacks *Release to testing tracks*. Permissions can take a few minutes to apply after inviting. |
| `… answered 404: Package not found` | No app with the package name exists in Play Console yet, or it has never had a release; the first one is created by hand ([One-time setup](#one-time-setup), step 4). |
| `… answered 400: Version code N has already been used` | The upload's version code is not above every earlier one. Let the Release workflow upload (its run number only climbs), or pass a higher `build_number` to a manual run. |
| `… answered 400: … signed with a key that is not the upload key` | The keystore in the secret is not the key Play registered from the first upload. Use the same `upload.jks`, or reset the upload key under App integrity. |
| `… answered 404: Track not found` | `PLAY_TRACK` names a track that does not exist. The first closed track is `alpha`; a custom one goes by its own name. |
| `bundleRelease` fails with R8 *Missing class* | A library references a class nothing ships. `composeApp/build/outputs/mapping/release/missing_rules.txt` holds the `-dontwarn` lines R8 asks for; copy only those into `composeApp/proguard-rules.pro`, with a comment naming the library. CI's Android job catches this on the pull request. |
| The release build crashes where the debug build doesn't (`ClassNotFoundException`, a serializer "not found") | R8 removed or renamed something reached by reflection: a missing keep rule in `composeApp/proguard-rules.pro`. The mapping file turns the stack trace back into names (`retrace` in the SDK's `cmdline-tools`). |
| `bundleRelease` fails with *SDK location not found* | Only on a machine without the Android SDK; the Ubuntu runner has it. Locally, install Android Studio or set `ANDROID_HOME`. |
| The upload succeeds but testers see nothing | Play processes a bundle for minutes to hours, and testers must have accepted the opt-in link. Check the track's page in the console. |
