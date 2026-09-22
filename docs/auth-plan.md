# Sign-in and sync: implementation plan

A queue of self-contained work items. **Each item is one branch, one pull request.** An agent
starting from scratch should:

1. Read this whole file, then [`docs/sync.md`](sync.md) (the design these items finish).
2. Take the **first item whose box is unticked** and whose "Depends on" items are ticked.
3. Branch from the latest `main`, implement only that item, tick its box in this file in the
   same pull request, and add a line under it: `Done in #<pr>`.
4. Run the checks below before pushing. Open the pull request titled as the item's heading.

Scope for now: **iOS only**. Android and desktop keep `NoIdentityProvider` and the default,
empty `AuthConfig` (sign-in buttons disabled, no sync). Nothing here may break them.

## Checks every item must pass

These are what CI runs (`.github/workflows/ci.yml`). They run on Linux, including the iOS
compile:

```
./gradlew :shared:desktopTest :composeApp:desktopTest :server:test -Pgains.android=false --no-daemon
./gradlew :shared:compileKotlinIosArm64 :composeApp:compileKotlinIosArm64 -Pgains.android=false --no-daemon
```

- No Mac or Xcode is available to the agent. **Keep iOS work in Kotlin (`iosMain`)** wherever
  possible, since that compiles above. Swift, `project.pbxproj`, `.plist` and `.entitlements`
  edits can't be checked. Keep them minimal, copy the exact formats already in those files,
  and list each one in the pull request body so the owner can check it in Xcode.
- Every new user-facing string goes in **both**
  `composeApp/src/commonMain/composeResources/values/strings.xml` and `values-ru/strings.xml`.
  `LocalizationResourcesTest` fails otherwise.
- Match the surrounding style: KDoc on public classes and functions saying *why*, no new
  frameworks, and Koin for wiring (`shared/.../di/SharedModule.kt`).
- Update `docs/sync.md` wherever an item changes what it describes.

## Where things are

| What | Where |
|---|---|
| Account model, `AuthConfig`, `IdentityProvider`, `AccountRepository` | `shared/src/commonMain/kotlin/app/gains/auth/Account.kt` |
| Koin defaults (`AuthConfig()`, `NoIdentityProvider`) | `shared/src/commonMain/kotlin/app/gains/di/SharedModule.kt` |
| Sync engine / status / controller / store / HTTP API | `shared/src/commonMain/kotlin/app/gains/sync/` |
| `sync_state` table (token and cursor live here today) | `shared/src/commonMain/sqldelight/app/gains/db/Sync.sq` |
| iOS entry point, Koin start, UIKit helpers (`topViewController`) | `composeApp/src/iosMain/kotlin/app/gains/MainViewController.kt` |
| Sign-in screen (first launch gate) | `composeApp/src/commonMain/kotlin/app/gains/ui/screens/SignInScreen.kt` |
| Settings screen (account card at the top) | `composeApp/src/commonMain/kotlin/app/gains/ui/screens/SettingsScreen.kt` |
| App root: shows `SignInScreen` while the account is `null`, starts `SyncController` | `composeApp/src/commonMain/kotlin/app/gains/App.kt` |
| iOS project settings (team, bundle id), read as `$(NAME)` | `iosApp/Configuration/Config.xcconfig` |
| Info.plist, privacy manifest | `iosApp/iosApp/Info.plist`, `iosApp/iosApp/PrivacyInfo.xcprivacy` |
| Server sign-in checks | `server/src/main/kotlin/app/gains/server/Auth.kt` |

Useful facts:
- The server accepts an identity token whose audience is in `GOOGLE_CLIENT_IDS` or
  `APPLE_CLIENT_IDS` (`secrets/.env`). For Apple on iOS the audience is the bundle id
  `app.gains.Gains`.
- `AccountRepository.signIn()` already calls `SyncStore.startFeed()`, which marks every local
  document as changed when the user changes. **Signing in on a device that holds guest data
  already uploads all of it.** No new merge code is needed.
- `AuthConfig.googleEnabled` / `appleEnabled` also need `serverBaseUrl`, and
  `SyncController` runs only when `syncEnabled`.

## Owner actions (not for agents)

These are console work. Agents should assume they're done or in progress and must not block on them.

- [x] Apple: Sign in with Apple capability on the `app.gains.Gains` App ID; App Store profile
      regenerated and `IOS_APP_STORE_PROFILE_BASE64` updated.
- [x] Google Cloud: an **iOS** OAuth client for `app.gains.Gains`:
      `95741411455-2fouqgkjlnault5cvd8lc17n4jekg7ea.apps.googleusercontent.com`. Item 2 writes it into `iosApp/Configuration/Config.xcconfig`.
- [ ] Server `secrets/.env`: `GOOGLE_CLIENT_IDS` includes that iOS client id,
      `APPLE_CLIENT_IDS=app.gains.Gains`, `JWT_SECRET` set; `tools/deploy_server.py secrets`.
- [ ] Google Auth Platform: for now, stays in **Testing** with the owner's accounts listed
      under Audience → Test users. Only those accounts can sign in with Google. Publish to
      production before a public release.
- [ ] App Store Connect: App Privacy answers and privacy policy URL.

---

## 1. iOS: configuration and Sign in with Apple

- [x] Done
  Done in #57

**Depends on:** nothing.

**Goal:** on iOS the Apple button works end to end: sheet → identity token → `POST /auth/apple`
→ signed in → first sync uploads local data. The Google button stays disabled until item 2.

Steps:

1. **Config values.** In `Config.xcconfig` add
   `GAINS_SERVER_URL = https:/$()/api.gains.gerra.sh`. The `$()` is needed because `//` starts
   a comment in xcconfig; add a comment saying so. In `Info.plist` add
   `<key>GainsServerURL</key><string>$(GAINS_SERVER_URL)</string>`.
2. **iOS `AuthConfig`.** In `MainViewController.kt`, register it in the same `module { }` passed to
   `initKoin`. `initKoin` loads platform modules after `sharedModule`, and a later definition
   overrides the shared default.
   - `serverBaseUrl`: read `GainsServerURL` with
     `NSBundle.mainBundle.objectForInfoDictionaryKey`, blank → `null`.
   - `appleServiceId`: `NSBundle.mainBundle.bundleIdentifier`. Update the KDoc of
     `AuthConfig.appleServiceId` to say that on iOS it's the bundle id, the audience of the
     native flow.
3. **Cancellation.** In `Account.kt` add `class SignInCancelledException : Exception()`. Document it
   on `IdentityProvider.signIn` as what to throw when the person closes the sheet. In
   `SignInModel.signIn` (SignInScreen.kt), catch it and show nothing: no error and no `failed`.
4. **`IosIdentityProvider`** (new file `composeApp/src/iosMain/kotlin/app/gains/IosIdentityProvider.kt`),
   written in Kotlin against `platform.AuthenticationServices`:
   - `AccountKind.APPLE`: `ASAuthorizationAppleIDProvider().createRequest()` with
     `requestedScopes = listOf(ASAuthorizationScopeFullName, ASAuthorizationScopeEmail)`, and an
     `ASAuthorizationController` with a delegate and a presentation-context provider that
     returns the key window. Wrap it in `suspendCancellableCoroutine`.
   - On success, turn the `ASAuthorizationAppleIDCredential.identityToken` (`NSData`) into a
     UTF-8 string. The name comes from `fullName` through `NSPersonNameComponentsFormatter`, and
     is null when empty (Apple sends it only the first time). Return `IdentityAssertion(token, name)`.
   - Error code `ASAuthorizationErrorCanceled` → `SignInCancelledException`. Any other error
     → an exception with its message.
   - Keep strong references to the controller and delegate until completion, because UIKit
     holds delegates weakly (see the `IosPhotoPicker` pattern). Delegates must be `NSObject`
     subclasses, and fields can't live in a companion of an Obj-C subclass (see the
     `IMAGE_UTI` note).
   - `AccountKind.GOOGLE` → `throw AuthNotConfiguredException(kind)` for now.
   - Register it: `single<IdentityProvider> { IosIdentityProvider() }` in the iOS module.
5. **Entitlement.** Create `iosApp/iosApp/iosApp.entitlements`, a plist with
   `com.apple.developer.applesignin` = `["Default"]`. Set
   `CODE_SIGN_ENTITLEMENTS = iosApp/iosApp.entitlements;` in **both** target build
   configurations in `project.pbxproj`, next to the existing `INFOPLIST_FILE = iosApp/Info.plist;`
   lines. Add the file to the project's file references and group as well, copying how
   `Info.plist` is listed. `tools/testflight.py` signs manually with the App Store profile,
   which now includes the capability, so nothing there should need to change. Check
   `archive()` to confirm.
6. **Buttons.** Apple's review guidelines require the Apple button to follow the HIG. On the
   sign-in screen, render it as "Sign in with Apple" with the Apple logo, black on light themes
   and white on dark, full width, in the same pill shape. Label the Google one "Sign in with
   Google". Draw the Apple logo as a vector in the style of `ui/components/Logo.kt`, or use
    the `U+F8FF` glyph only if the platform font renders it; it doesn't on Android or desktop,
    so prefer a vector. Hide buttons whose provider is not enabled when the other is enabled.
    Keep the current disabled look when neither is enabled.
7. Update `docs/sync.md` → "What is deliberately not here" → "Native sign-in buttons". Say that
   Apple is done on iOS and Google is next.

Tests: a desktop unit test for anything pure you add. The iOS code is checked by the
`compileKotlinIosArm64` task. Say in the pull request that it needs a device run.

## 2. iOS: Sign in with Google

- [x] Done
  Done in #61

**Depends on:** 1.

**Approach: no Google SDK.** Do OAuth 2.0 with PKCE through `ASWebAuthenticationSession`, which
is what GoogleSignIn-iOS does internally. That avoids adding an SPM package to `project.pbxproj`
and a Swift bridge, and it all stays in Kotlin, where it compiles in CI. Google allows custom-scheme
redirects for **iOS** OAuth clients, and they need no client secret.

Steps:

1. **Config.** In `Config.xcconfig` add
   `GOOGLE_IOS_CLIENT_ID = 95741411455-2fouqgkjlnault5cvd8lc17n4jekg7ea.apps.googleusercontent.com`, with a comment that it's the iOS OAuth client from the
   Google Cloud console and isn't a secret (it ships inside every app).
   Its redirect scheme is `com.googleusercontent.apps.95741411455-2fouqgkjlnault5cvd8lc17n4jekg7ea`. In `Info.plist`
   add `<key>GainsGoogleClientID</key><string>$(GOOGLE_IOS_CLIENT_ID)</string>`. In the iOS
   `AuthConfig`, set `googleClientId` from it, blank → `null`, so the button stays disabled
   until it's filled in.
2. **Pure OAuth pieces in `shared/src/commonMain/kotlin/app/gains/auth/GoogleOAuth.kt`**, testable
   on desktop:
   - `redirectScheme(clientId)`: `"<prefix>.apps.googleusercontent.com"` →
     `"com.googleusercontent.apps.<prefix>"`. Redirect URI: `"$scheme:/oauth2redirect"`.
   - `authorizationUrl(clientId, codeChallenge, state)`: `https://accounts.google.com/o/oauth2/v2/auth`
     with `response_type=code`, `scope=openid email profile`, `code_challenge_method=S256`,
     `prompt=select_account`, the redirect URI and `state`. Everything must be URL-encoded.
   - `parseCallback(url, expectedState)`: return the `code`, or throw on `error=`, a missing
     code or a `state` that doesn't match.
   - `suspend fun exchange(client: HttpClient, clientId, code, verifier)`: form POST to
     `https://oauth2.googleapis.com/token` (`grant_type=authorization_code`, `client_id`,
     `code`, `code_verifier`, `redirect_uri`) → the `id_token` from the JSON. Use the
     `HttpClient` already in Koin (`createHttpClient()`).
   - Tests in `shared/src/desktopTest/.../auth/GoogleOAuthTest.kt` for the URL, the scheme and
     callback parsing. Also test `exchange` if you add `ktor-client-mock` to the catalog and
     to `desktopTest` dependencies.
3. **PKCE on iOS.** In `IosIdentityProvider`, build the verifier from 32 bytes of
   `SecRandomCopyBytes`, base64url-encoded without padding. Build the challenge from the
   base64url SHA-256 of the verifier, via `CC_SHA256` from `platform.CoreCrypto`. `state`
   comes from the same random source.
4. **`AccountKind.GOOGLE`** in `IosIdentityProvider`: start `ASWebAuthenticationSession(url,
   callbackURLScheme = redirectScheme, completionHandler)` with the same presentation-context
   provider as Apple and `prefersEphemeralWebBrowserSession = false`. Keep a strong reference.
   - On the callback: `parseCallback` → `exchange` → `IdentityAssertion(idToken, null)`. The
     server reads the name from the Google token.
   - `ASWebAuthenticationSessionErrorCodeCanceledLogin` → `SignInCancelledException`.
   - No `CFBundleURLTypes` entry is needed, because `ASWebAuthenticationSession` catches the
     scheme itself.
5. Update `docs/sync.md` "Signing in", step 1. It says "the Google Sign-In SDK on iOS"; change it
   to this flow and say why. Also update `secrets/README.md` for `GOOGLE_CLIENT_IDS`: the iOS
   client id is the audience of these tokens.

## 3. Settings: link Google / Apple from a guest account

- [x] Done
  Done in #63

**Depends on:** 1. Item 2 isn't required, since each button follows its provider's `*Enabled`
flag.

**Goal:** a guest can sign in straight from Settings, and everything on the device goes up to the
account. Today the guest's "Sign in" button calls `signOut()`, which drops them on the welcome
screen; after a cancel they're stranded there.

Steps:

1. In `SettingsModel`, add `linkGoogle()` / `linkApple()` calling
   `accounts.signInWithGoogle()` / `signInWithApple()` directly, with **no** sign-out first. Use
   the same error handling as `SignInModel`: ignore `SignInCancelledException`, show
   `AuthNotConfiguredException` as "not configured", show anything else as `sign_in_failed`.
   Also add a `linking` flag while one is in flight. Pull the shared try/catch into one helper,
   e.g. in `ui/ScreenModel.kt` or next to `SignInModel`, rather than copying it.
2. In the account card, for a guest, replace the "Sign in" `TextButton` with the enabled
   provider buttons ("Link Apple" / "Link Google", styled like item 1's buttons but smaller).
   Add a line of copy under them: *"Your workouts on this device will be uploaded to the account
   and merged with anything already there."* When no provider is enabled, keep today's
   `sign_in_not_configured_note`.
3. For a signed-in account, keep "Sign out".
4. On success the card updates by itself through `observeAccount()`, and `SyncController` starts
   the first sync because `signedIn` becomes true. Check that this happens and that nothing else
   is needed. Don't navigate anywhere.
5. Tests: a desktop test with a fake `IdentityProvider` and a fake server if practical; see
   `server/src/test/.../SyncRoundTripTest.kt` for signing in through real routes. At minimum,
   test that linking from a guest keeps local sessions and marks them pending
   (`SyncStore.observePendingCount() > 0`) without ever setting the account to `""`.

## 4. Settings: delete account

- [x] Done

**Depends on:** nothing (it's visible only to a signed-in account). It's required by App Store
guideline 5.1.1(v) for apps that offer account creation.

Steps:

1. In the account card of a signed-in (non-guest) account, add a destructive "Delete account"
   text button below "Sign out".
2. Show a confirmation dialog, reusing the `confirmDelete` / `AlertDialog` pattern already in
   `SettingsScreen.kt`:
   - Title: "Delete your account?"
   - Body: "Your account and everything synced to it are removed from the server. Workouts on
     this device stay here."
   - Buttons: destructive "Delete", and "Cancel".
3. `SettingsModel.deleteAccount()` calls `accounts.deleteAccount()`. While it runs, disable the
   buttons. If it fails (offline, 5xx), show an error line in the card and **stay signed in**;
   `AccountRepository.deleteAccount` already signs out only after the call succeeds. On success
   the app goes back to the sign-in screen, as `signOut()` does today.
4. In `AccountRepository.deleteAccount()`, after the server call, also reset the feed so a later
   sign-in to a new account re-uploads everything. Clear `KEY_USER` and the cursor in
   `SyncStore`, with a new `forgetFeed()`, and test it in `SyncStoreTest`.
5. Out of scope, but note it in `docs/sync.md` → "What is deliberately not here": Apple
   recommends revoking the Sign in with Apple token on deletion (`POST
   https://appleid.apple.com/auth/revoke`). That needs the authorization code from the client
   and a `.p8` key on the server.

## 5. Settings: sync status and "Sync now"

- [ ] Done

**Depends on:** nothing, but it's only visible when `AuthConfig.syncEnabled` and signed in.

Steps:

1. **Persist the last success.** In `SyncEngine.sync()`, after a successful run, store its
   time in `sync_state` (`SyncStore.setLastSyncedAt` / `lastSyncedAt`, key
   `last_synced_at`), so the status still shows after a restart. `SyncStatus` itself stays
   in memory.
2. **Model.** `SettingsModel` combines `SyncEngine.status`, `SyncStore.observePendingCount()`
   and the stored last-synced time into one `SyncUi` value.
3. **UI**, in the signed-in account card, as one line plus a "Sync now" text button:
   - Running → "Syncing…"
   - Done or idle with a stored time → "Synced 5 min ago". Reuse the relative-time wording
     already used elsewhere (search `ui/i18n` and `ui/Time.kt` before writing new wording).
   - Pending > 0 and not running → append "· 3 changes waiting". Use a plural resource in both
     languages; Russian needs `one/few/many/other` (`russianPluralsCoverEveryQuantity`).
   - Failed → "Couldn't sync. Tap Sync now to retry." in the error colour.
   - Failed with `signedOut == true` (a 401): "Signed out on the server. Sign in again." with
     the item 3 link buttons. Today nothing reacts to a 401; this is where it surfaces.
   - "Sync now" → `SyncController.requestSync()`, disabled while running.
4. **Foreground trigger.** `docs/sync.md` says a sync runs every time the app comes to the
   foreground, but nothing calls `requestSync()` today. In `App.kt`, call it on each resume.
   Use the Compose Multiplatform lifecycle (`LocalLifecycleOwner` + `Lifecycle.Event.ON_RESUME`)
   if the lifecycle artifact is already on the classpath through Compose. Otherwise, on iOS use
   `UIApplicationWillEnterForegroundNotification` in `MainViewController.kt`, calling into the
   controller from Koin. Don't add a new library just for this.
5. Tests: a desktop test for the `SyncUi` mapping. A `ScreenshotTest` run is fine but not
   required; the screenshots workflow regenerates PNGs.

## 6. Keep the token in the iOS Keychain

- [ ] Done

**Depends on:** nothing. Merge it before any public release.

**Goal:** the bearer token leaves the `sync_state` table on iOS. `token_issued_at`, the
cursor and the user id stay in SQLite, since they aren't secrets.

Steps:

1. In `shared/.../sync/`, add an `interface TokenVault { suspend fun get(): String?; suspend fun
   set(token: String); suspend fun clear() }` with KDoc on why.
   - `SqliteTokenVault(db)` uses today's `KEY_TOKEN` row; it stays the default for Android and
     desktop, and for tests.
   - `KeychainTokenVault` goes in `shared/src/iosMain`: `SecItemAdd` / `SecItemCopyMatching` /
     `SecItemUpdate` / `SecItemDelete` with `kSecClassGenericPassword`, service `app.gains.sync`,
     account `token`, and `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, so a background
     sync after unlock still works and the token never goes into backups. CF bridging in
     Kotlin/Native needs `CFBridgingRetain` / `CFDictionaryCreateMutable`; keep that in one
     private helper.
2. `SyncStore.token()` / `setToken()` / `clearToken()` delegate to the vault, injected through
   the constructor. The Koin default is `SqliteTokenVault`. The iOS module in
   `MainViewController.kt` overrides it with `KeychainTokenVault`.
3. **Migration.** When the vault is empty and `sync_state` still has a `token` row, move it
   into the vault and delete the row. Do this once, the first time the token is read.
4. **Reinstall.** Keychain items survive deleting the app, but the database doesn't. At start,
   if the stored account is `null` or a guest and the vault holds a token, clear the vault.
5. Tests: `SyncStoreTest` with an in-memory fake vault covers the migration and
   `clearToken`. The Keychain code is checked by `compileKotlinIosArm64`.
6. `docs/sync.md` → "What the client does": replace the sentence about the Keychain being "the
   noted follow-up" with what the code now does. Android's Keystore is still to do.

## 7. Info.plist and privacy manifest now that data leaves the device

- [ ] Done

**Depends on:** nothing.

1. `iosApp/iosApp/Info.plist`: rewrite the comment above `ITSAppUsesNonExemptEncryption`. The
   app now talks to its sync server over HTTPS, using only the encryption iOS provides (exempt),
   so the value stays `false`. Don't change the value.
2. `iosApp/iosApp/PrivacyInfo.xcprivacy`: the header comment says "collects nothing". Rewrite it:
   a guest's data stays on the device, and a signed-in account syncs to `api.gains.gerra.sh`. Fill
   in `NSPrivacyCollectedDataTypes` to match the App Store Connect answers. Each entry is
   `Linked = true`, `Tracking = false`, purpose `NSPrivacyCollectedDataTypePurposeAppFunctionality`:
   - `NSPrivacyCollectedDataTypeEmailAddress`
   - `NSPrivacyCollectedDataTypeName`
   - `NSPrivacyCollectedDataTypeFitness`
   - `NSPrivacyCollectedDataTypeHealth` (body weight)
   - `NSPrivacyCollectedDataTypePhotosorVideos`
   - `NSPrivacyCollectedDataTypeOtherUserContent`
   - `NSPrivacyCollectedDataTypeUserID`

   Leave `NSPrivacyAccessedAPITypes` as it is.
3. `strings.xml` `data_note` (both languages) still says "Nothing leaves the device until sync
   exists and you sign in". Reword it: nothing leaves the device for a guest, and a signed-in
   account syncs to the server. Check `sign_in_not_configured_note` and `guest_note_coming_soon`
   too; they're still correct on Android, so keep them, but make sure iOS no longer shows them
   once sign-in is configured.
