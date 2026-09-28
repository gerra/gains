# Launch plan

Everything left before Gains goes public, in one place: the work items, the owner's console
tasks, where the contact email is used, and the manual test plan. The sign-in queue in
[`docs/auth-plan.md`](auth-plan.md) is finished (items 1–7, release 1.5), and the roadmap in
[`docs/development.md`](development.md#roadmap) points here. When something new comes up, add
it here rather than starting another list.

- [How to use this file](#how-to-use-this-file)
- [At a glance](#at-a-glance)
- [Owner actions](#owner-actions)
- [Work items](#work-items)
- [Contact email: where it is used](#contact-email-where-it-is-used)
- [Test plan](#test-plan)
- [After launch](#after-launch)

## How to use this file

The same rules as `auth-plan.md`:

1. **Each work item is one branch and one pull request.** Take the first unticked item whose
   "Depends on" items are ticked, branch from the latest `main`, implement only that item, tick
   its box in the same pull request and add `Done in #<pr>` under it. Item 33 is the one
   exception: each of its steps is its own pull request, and ticks its own box.
2. Run the checks from [`auth-plan.md`](auth-plan.md#checks-every-item-must-pass) before
   pushing. They still apply: both `strings.xml` files, KDoc that says why, Koin for wiring,
   `docs/sync.md` kept true. A pull request that changes how the app is put together (the root,
   the ScreenModels, the wiring, the modules) keeps `docs/how-it-works.md` true in the same pull
   request.
3. **Android is compiled and linted in CI, and the Xcode project built for the simulator; a
   device is still needed for the test plan.** The `android` job in `ci.yml` builds the debug
   app, runs lint and the shared tests on the Android JVM (item 25); the `ios` job in `ios.yml`
   builds the iOS app for the simulator, unsigned (item 26). List every Android, Swift and Xcode
   project file you touched in the pull request body so the owner knows what to check on a
   device.
4. Steps marked **Owner** are console or account work. Agents assume they are done or in
   progress and don't block on them.
5. Manual checks go in the [test plan](#test-plan), under the item's number. An item that
   changes what a person sees adds its checks there.

## At a glance

| # | Item | Milestone | Who | Depends on | Done |
|---|------|-----------|-----|------------|------|
| 1 | Sign-out can't crash the app; fix the secrets deploy line | iOS launch | Agent | — | [x] |
| 2 | Merge accounts only on verified emails | iOS launch | Agent | — | [x] |
| 3 | Landing page and privacy policy on `gains.gerra.sh` | iOS launch | Agent + Owner | — | [ ] |
| 4 | Contact email alias | iOS launch | Owner | — | [ ] |
| 5 | Google sign-in in production | iOS launch | Owner | 3, 4 | [ ] |
| 6 | Revoke the Apple token when an account is deleted | iOS launch | Agent + Owner | — | [x] |
| 7 | Submit iOS for App Review | iOS launch | Owner | 1–6, 20, test plan | [ ] |
| 8 | Android package name and Play Console app | Android launch | Owner + Agent | — | [ ] |
| 9 | Android: Sign in with Google | Android launch | Agent + Owner | 8 | [x] |
| 10 | Server: Apple web sign-in (Services ID) | Android launch | Agent + Owner | — | [x] |
| 11 | Android: Sign in with Apple | Android launch | Agent + Owner | 8, 10 | [x] |
| 12 | Android: keep the token in the Keystore | Android launch | Agent | — | [x] |
| 13 | Android: release workflow and Play closed testing | Android launch | Agent + Owner | 8, 9 | [x] |
| 14 | Publish on Google Play | Android launch | Owner | 9–13, 20, 21, test plan | [ ] |
| 15 | Desktop: Sign in with Google | Desktop (P1) | Agent + Owner | — | [x] |
| 16 | Desktop: Sign in with Apple | Desktop (P1) | Agent | 10 | [x] |
| 17 | Desktop: keep the token in the OS keychain | Desktop (P1) | Agent | — | [x] |
| 18 | Email and password accounts | More sign-in | Agent + Owner | 2, 4 | [x] |
| 19 | Passkeys | More sign-in | Agent + Owner | 3, 18 | [ ] |
| 20 | Every authenticated route checks that the account still exists | iOS launch | Agent | — | [x] |
| 21 | Android: target API 36 (Android 16) | Android launch | Agent + Owner | — | [x] |
| 22 | Server: run as an unprivileged user, with systemd hardening | Hardening (P1) | Agent + Owner | — | [ ] |
| 23 | Server: rate limits on sign-in, sync and uploads | Hardening (P1) | Agent | — | [x] |
| 24 | Server: the Apple refresh tokens and the database at rest | Hardening (P1) | Agent + Owner | 22 | [ ] |
| 25 | Android in CI: build, lint and the JVM tests | CI (P1) | Agent | — | [x] |
| 26 | iOS in CI: an Xcode simulator build | CI (P1) | Agent + Owner | — | [x] |
| 27 | A dependency modernization pass | Maintenance (P2) | Agent | 25, 26 | [x] |
| 28 | Supply chain: pinned actions and dependency scanning | Maintenance (P2) | Agent + Owner | — | [x] |
| 29 | Supply chain: Dependabot, and Gradle dependency verification where practical | Maintenance (P2) | Agent | 27, 28 | [x] |
| 30 | Android: R8 for release builds | Hardening (P2) | Agent + Owner | 21, 25 | [ ] |
| 31 | Android backup: decide what a backup may carry | Hardening (P2) | Owner + Agent | — | [ ] |
| 32 | Navigation lifecycle: pin its invariants in tests | Maintenance (P1) | Agent | — | [ ] |
| 33 | `App.kt`: move the root's coordination into small, tested pieces | Maintenance (P1) | Agent | 32 | [ ] |
| 34 | ScreenModel actions: one way to launch them and to handle their failures | Maintenance (P1) | Agent | — | [ ] |
| 35 | Explicit dependencies instead of `inject()` defaults | Maintenance (P2) | Agent | 34 | [ ] |
| 36 | Architecture docs back in step with the code | Maintenance (P2) | Agent | — | [ ] |
| 37 | Module boundaries: a wire-protocol module, and when to split features | Maintenance (P2, after launch) | Agent | 27 | [ ] |
| 38 | Android: an app module of its own, then AGP 9 and compileSdk 37 | Maintenance (P2) | Agent + Owner | 25, 26, 27 | [ ] |

**Blockers, P1, P2.** Items 20 and 21 are launch blockers: item 7 (App Review) depends on 20,
and item 14 (Google Play) on 20 and 21. Items 22–31 came out of a production-readiness review
after the sign-in work and don't hold either store back: `Hardening (P1)` and `CI (P1)` are
wanted right after the first launch and can start now, `(P2)` when there is time. Within a
milestone the numbers are the order: security and auth correctness (20, 22–24) before
infrastructure polish (25–29), and API 36 (21) before Android goes public. Items 32–37 came out
of an architecture review of the client and hold nothing back either: they keep the code easy to
change as it grows, and come after the P1 hardening and CI items. 32 goes first because it is the
safety net for 33. Item 38 came out of item 27, which had to stop at AGP 8.x: it holds nothing back
either, but every month on 8.x puts Compose, okhttp and the next androidx releases further out of
reach.

## Owner actions

Carried over from `auth-plan.md` and still open, or needed by the items below:

- [x] Server `secrets/.env`: `GOOGLE_CLIENT_IDS`, `APPLE_CLIENT_IDS`, `JWT_SECRET`, deployed.
- [x] App Store Connect: App Privacy answers.
- [ ] App Store Connect: privacy policy URL set to `https://gains.gerra.sh/privacy` once item 3
      is live.
- [ ] Google Auth Platform in production (item 5). Until then it stays in **Testing**, and
      only accounts under Audience → Test users can sign in with Google.
- [ ] Contact email alias (item 4), then the [contact email table](#contact-email-where-it-is-used).
- [ ] Android Sign in with Apple (item 11): `gains.appleServicesId` for the Android build, in
      `gradle.properties` or the release workflow, the same Services ID as `APPLE_SERVICES_ID`.
- [ ] Android Sign in with Apple (item 11): the SHA-256 fingerprints of the Play App Signing key
      and the upload key (Play Console → App integrity; the debug key's too, from
      `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`)
      into `site/.well-known/assetlinks.json`, then `python3 tools/deploy_server.py nginx` (the
      vhost gained a location for that file) and `python3 tools/deploy_server.py site`.
      `adb shell pm get-app-links app.gains` then says `verified`.
- [ ] Email accounts (item 18): a mail provider's SMTP account and a verified sender address
      into `secrets/.env` (`SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM`;
      [`secrets/README.md`](../secrets/README.md)), `deploy_server.py secrets`, then the form's
      switch in the apps: `gains.passwordSignIn=true` in `gradle.properties`,
      `GAINS_PASSWORD_SIGN_IN` as a repository variable for the Play build, and
      `GAINS_PASSWORD_SIGN_IN = YES` in `Config.xcconfig`. Until then the form stays hidden and
      the server logs `email off` at start.
- [ ] Play upload (item 13): the upload key, the service account and the first closed-testing
      release by hand, then the five `ANDROID_UPLOAD_*` / `PLAY_SERVICE_ACCOUNT_JSON` secrets
      and the `GAINS_GOOGLE_WEB_CLIENT_ID` / `GAINS_APPLE_SERVICES_ID` variables
      ([`docs/play.md`](play.md), "One-time setup"). Until then the Google Play job skips
      itself on every release round.
- [ ] Android 16 (item 21): Android Studio with SDK 36, and an Android 16 device or emulator for
      the test plan's Android 16 section.
- [ ] Server user (item 22): once `/health` answers from `/opt/gains-server`, remove
      `/root/Projects/gains-server` on the box.
- [ ] Refresh-token key (item 24): `REFRESH_TOKEN_KEY` in `secrets/.env`
      (`openssl rand -base64 32`), then `python3 tools/deploy_server.py secrets`; and the backup
      decision under item 24, step 3.
- [ ] iOS CI (item 26): where the macOS job runs, given what its minutes cost. Item 26 put the
      recommended answer in place (pull requests and pushes to `main`, skipping `docs/`,
      `site/`, `server/`, `deploy/` and `tools/`): keep it, or change the `paths` lists in
      `.github/workflows/ios.yml`. Either way, don't make **iOS simulator build** a required
      check.
- [ ] Dependency graph and Dependabot alerts on (item 28): GitHub → Settings → Code security.
  Until the graph is on, GitHub refuses the snapshot, so CI's Dependency review job and the
  Dependency graph workflow fail.
- [ ] Backup decision (item 31), before item 14 if possible.

---

## Work items

### 1. Sign-out can't crash the app; fix the secrets deploy line

- [x] Done
  Done in #75

**Milestone:** iOS launch. **Depends on:** nothing.

1. `SettingsModel.signOut()` runs `accounts.signOut()` in `scope.launch`, and
   `ScreenModel.scope` has no exception handler. `KeychainTokenVault.clear()` throws when
   `SecItemDelete` fails, and an uncaught exception crashes a Kotlin/Native app. Make sign-out
   always finish: in `AccountRepository.signOut()`, clear the account row even if clearing the
   token throws. The token is then an orphan, and `forgetOrphanedToken()` already clears it at the
   next start. Check `deleteAccount()` and the link / relink paths for the same problem.
2. `secrets/.env.example` line 1 says `scripts/deploy_secrets.sh`, which doesn't exist. Change
   it to `python3 tools/deploy_server.py secrets`.

Tests: a desktop test with a vault whose `clear()` throws. `signOut()` must still leave the
account `""`.

### 2. Merge accounts only on verified emails

- [x] Done
  Done in #76

**Milestone:** iOS launch. **Depends on:** nothing.

`Store.signIn` joins a new identity to an existing user whenever the emails match
(`selectUserByEmail`). It doesn't look at the token's `email_verified` claim, so an identity
whose provider hasn't verified the address could take over another person's account. This
becomes a real hole once item 18 lets anyone type an email.

1. In `JwksIdentityVerifier.verify`, read `email_verified`. Google sends a boolean. Apple sends
   `"true"`/`"false"` as a string or a boolean; accept both. Add `emailVerified` to
   `VerifiedIdentity`.
2. In `Store.signIn`, look up `byEmail` only when the email is verified. Keep storing an
   unverified email on the identity, but never merge on it.
3. Compare emails case-insensitively when merging (store them lowercased, or `lower()` in the
   query).

Tests: in `server/src/test`, an unverified Google identity with the same email as an existing
Apple user gets a new user. A verified one joins the existing user.

### 3. Landing page and privacy policy on `gains.gerra.sh`

- [ ] Done
  Agent steps 1–3 and 6 are in #73: `site/`, the `gains.gerra.sh` vhost,
  `deploy_server.py site` / `nginx` and the Deploy site and nginx workflow. Left: the owner's
  steps 4 and 5. Tick this box once `https://gains.gerra.sh/privacy` loads.

**Milestone:** iOS launch. **Depends on:** nothing.

**Why `gains.gerra.sh` redirects to the API today:** the box has no nginx site for it. DNS
points it at the same server, and when no site is marked `default_server`, nginx answers with
the first site it loads, alphabetically. That is `api.gains.gerra.sh`, whose port-80 block
answers `301 https://api.gains.gerra.sh$request_uri`. Over HTTPS, the same thing serves the
API's certificate, which shows a certificate warning.

1. **Site files** in a new `site/` directory: static HTML and CSS, no build step and no
   framework. Match the app's look: the logo from `docs/screenshots/logo.png`, and the dark and
   light themes.
   - `/`: what Gains is, a few screenshots, links to the App Store, Google Play (hidden until
     item 14) and the public TestFlight link from the README, and the guest list.
   - **Guest list (Owner decides first):** what it collects (an email address?), where it is
     stored and who emails it. If it stores emails on our server, it needs a route and a table,
     and the privacy policy must cover it. A link to a form hosted elsewhere avoids both.
     *Decided:* an email field on `/` that posts `{"email"}` to `POST /guest-list` on our own
     server (the `guest_list` table, `migrations/3.sqm`). The site's vhost proxies that one path
     to the server, so the form is same-origin and needs no CORS, and rate-limits it per IP;
     the server answers 204 whether the address is new or already listed, and 503 once the list
     holds 10,000. Without JavaScript the section falls back to the `mailto:` link.
     `/privacy#guest-list` covers it. To read the list on the box:
     `sqlite3 /var/lib/gains/gains-server.db 'SELECT email, created_at FROM guest_list'`.
   - `/privacy`: what a guest keeps on the device, what a signed-in account sends to
     `api.gains.gerra.sh` (the same list as `PrivacyInfo.xcprivacy`), where the server is, how
     long data is kept, how to delete it (Settings → Delete account, plus what item 6 revokes),
     and the contact alias from item 4.
   - `/support`: App Store Connect requires a support URL. A short page with the contact alias
     is enough.
2. **nginx:** `deploy/nginx/gains.gerra.sh.conf` serves `site/` from a directory on the box,
   in the same shape as the API vhost (ACME include, 80 → 443 redirect). *Decided against:* a
   `default.conf` catch-all for unknown hostnames. No other site on the box has one, and once
   `gains.gerra.sh` has its own vhost it no longer falls through to the API.
3. **Deploy:** teach `tools/deploy_server.py nginx` to push every file in `deploy/nginx/`, not
   only the API vhost. Add a `site` command that rsyncs `site/` to the box. Extend
   `deploy.yml`'s paths to `site/**`, or give the site its own workflow. Add tests in
   `tools/test_deploy_server.py` like the existing ones. Add `site` to `IGNORED` in
   `tools/release.py`, so that a site change alone doesn't cut an iOS release.
4. **Owner:** `certbot certonly --nginx -d gains.gerra.sh` (done), then merge: the Deploy site
   and nginx workflow pushes the vhosts, then the pages, and smoke tests `/privacy`.
5. Owner steps after it is live: set the privacy policy URL in App Store Connect, and
   `https://gains.gerra.sh` as the homepage in Google Auth Platform (item 5).
6. Link the site from the README.

### 4. Contact email alias

- [ ] Done

**Milestone:** iOS launch. **Who:** Owner.

1. Pick one address on the domain, e.g. `gains@gerra.sh`, and forward it to your inbox
   (Cloudflare Email Routing and ImprovMX are both free).
2. Put it everywhere the [contact email table](#contact-email-where-it-is-used) says an alias
   works, and tick each row there. Then changing your personal email later means changing one
   forwarding rule.

### 5. Google sign-in in production

- [ ] Done

**Milestone:** iOS launch. **Who:** Owner. **Depends on:** 3, 4.

1. Google Search Console: verify `gerra.sh`.
2. Google Auth Platform → Branding: app name "Gains", logo, homepage `https://gains.gerra.sh`,
   privacy policy `https://gains.gerra.sh/privacy`, authorized domain `gerra.sh`, user support
   email, and developer contact email (see the email table).
3. Audience → **Publish app**. `openid email profile` are not sensitive scopes, so this needs
   brand verification only, with no security review. It usually takes a few days.
4. Check it: an account that is **not** a test user can sign in with Google on iOS
   ([test plan](#test-plan) → 5).

### 6. Revoke the Apple token when an account is deleted

- [x] Done
  Done in #77. The owner's step 1 (the key in `secrets/.env`) switches it on; until then the
  server ignores the codes and logs `apple revoke off` at start.

**Milestone:** iOS launch. **Depends on:** nothing.

Apple asks apps that offer Sign in with Apple to revoke the user's tokens when the account is
deleted, and App Review can reject without it. Revoking needs a refresh token from Apple, and
the only way to get one is to exchange the authorization code at sign-in. The code expires
after five minutes and works once.

1. **Owner:** developer.apple.com → Keys → a new key with **Sign in with Apple** enabled for the
   primary App ID `app.gains.Gains`. Download the `.p8`, which can be downloaded only once. Add
   it to `secrets/.env` as `APPLE_KEY_ID`, `APPLE_TEAM_ID` (`V5Y8M5GKZ6`) and
   `APPLE_PRIVATE_KEY` (the `.p8` contents, with newlines as `\n`), then run
   `deploy_server.py secrets`. Document all three in `secrets/README.md` and
   `secrets/.env.example`, and remove the README's line that says the `.p8` is not needed.
2. **Client:** `IdentityAssertion` gains `authorizationCode: String? = null`. In
   `IosIdentityProvider`'s Apple delegate, read `credential.authorizationCode` (`NSData`, UTF-8)
   into it. `SignInRequest` gains `authorizationCode: String? = null`, and `SyncApi.signIn` sends
   it. Old servers ignore the unknown field.
3. **Server:** an `AppleTokens` class that:
   - builds the client secret as an ES256 JWT (`kid` = key id, `iss` = team id, `sub` = the
     token's audience, i.e. the bundle id for the native flow or the Services ID for item 10,
     `aud` = `https://appleid.apple.com`, expiring within minutes);
   - `exchange(code, clientId)`: `POST https://appleid.apple.com/auth/token`
     (`grant_type=authorization_code`) → `refresh_token`;
   - `revoke(refreshToken, clientId)`: `POST https://appleid.apple.com/auth/revoke`
     (`token_type_hint=refresh_token`).
   On `POST /auth/apple` with a code, exchange it and store the refresh token and its client id
   on the `identity` row (new nullable columns, via a SQLDelight migration). A failed exchange is
   logged, and the sign-in still succeeds. On `DELETE /auth/account`, revoke every Apple
   identity's refresh token **before** deleting the rows. A failed revoke is logged, and the
   deletion still goes ahead: Apple's side must never block deleting our data.
   Keep it behind an interface, like `IdentityVerifier`, so tests don't call Apple.
4. **Existing users** have no stored refresh token. Accept that; their next Apple sign-in stores
   one. Deleting before then removes our data but can't revoke.
5. Update `docs/sync.md`: remove the "Revoking the Sign in with Apple token" bullet from "What is
   deliberately not here", and describe the flow under "Signing in". In
   `site/privacy.html` → "Deleting your account", add the revocation sentence (there's an HTML
   comment marking where).

Tests: server tests with a fake `AppleTokens`: a sign-in with a code stores the refresh token,
account deletion revokes it before deleting, and a failed revoke still deletes.

### 7. Submit iOS for App Review

- [ ] Done

**Milestone:** iOS launch. **Who:** Owner. **Depends on:** 1–6, 20 (a deleted account's token
must be worth nothing before anyone can delete one), and the test plan's iOS sections passed on a
TestFlight build containing them.

1. App Store Connect: privacy policy URL, support URL (`/support`), screenshots, description,
   and the review notes. Reviewers can use Sign in with Apple, so no demo account is needed.
   Mention that Delete account is in Settings.
2. Submit. When it is approved, publish the store link on the landing page.

### 8. Android package name and Play Console app

- [ ] Done
  Agent step 2 is in #80: the owner kept `app.gains`, so `applicationId` doesn't change, and
  nothing in the manifest or the receivers depends on it (the intent actions are plain strings,
  and the notification uses `context.packageName`). Left: the owner's steps 3 and 4. Tick this
  box once the Play Console app exists.

**Milestone:** Android launch. **Depends on:** nothing.

The application id is `app.gains` today (`composeApp/android.gradle`), which may already be
taken on Play. **Choose before the first upload: Play never lets you change it.**

1. **Owner:** pick the id. `sh.gerra.gains` follows the domain you own. Check that no Play
   listing uses it. *Decided:* keep `app.gains`. Play refuses the first upload if the
   id is taken; if that happens, come back here and pick another before retrying.
2. Change `applicationId` in `composeApp/android.gradle`. `namespace` and the Kotlin packages
   can stay `app.gains`, since only the application id is public. Check `AndroidManifest.xml`
   and the notification / receiver code for any hard-coded `app.gains` that means the
   application id.
3. **Owner:** Play Console → create the app, with the store listing, content rating, target
   audience, **Data safety** (the same data as the iOS privacy answers), privacy policy URL,
   and contact email (see the email table). Enroll in Play App Signing.
4. **Owner:** New personal developer accounts must run a **closed test with at least 12
   testers for 14 days in a row** before they can apply for production. Start it as early as
   possible, even before sign-in lands (item 13).

### 9. Android: Sign in with Google

- [x] Done
  Agent steps 2–5 are in #94. The owner's step 1 switches it on: the web client id in
  `gains.googleWebClientId` (`gradle.properties`, compiled into `BuildConfig`) and in the server's
  `GOOGLE_CLIENT_IDS`. Until then the Android Google button stays hidden. Android is not compiled
  in CI: build it in Android Studio and run the test plan's Android section.

**Milestone:** Android launch. **Depends on:** 8.

1. **Owner:** Google Cloud, in the same project as the iOS client:
   - an **Android** OAuth client with the package name `app.gains` and the SHA-1 of **both**
     the Play App Signing key and the upload key (debug keystore too, for local runs);
   - a **Web application** client. Its id is Credential Manager's `serverClientId` and the
     audience of the tokens Android sends.
   Add the web client id to `GOOGLE_CLIENT_IDS` and deploy the secrets.
2. An `AndroidIdentityProvider` in `androidMain` using `androidx.credentials` with
   `GetGoogleIdOption(serverClientId = <web client id>)`. Return the `idToken`.
   `GetCredentialCancellationException` → `SignInCancelledException`. `AccountKind.APPLE`
   throws `AuthNotConfiguredException` until item 11.
3. Android `AuthConfig` in its Koin module: `serverBaseUrl = https://api.gains.gerra.sh` and
   `googleClientId` = the web client id, both from `BuildConfig` or resources, not hard-coded
   in Kotlin.
4. Call `SyncController.requestSync()` on resume (`ProcessLifecycleOwner` or the activity's
   `onResume`), as iOS does on foreground.
5. `docs/sync.md` "Signing in" and "What is deliberately not here": Android Google is done.

### 10. Server: Apple web sign-in (Services ID)

- [x] Done
  Done in #81. The owner's step 1 (`APPLE_SERVICES_ID` in `secrets/.env`) switches it on; until
  then the three routes answer 503 and the server logs `apple web off` at start.

**Milestone:** Android launch. **Depends on:** nothing. Also used by item 16.

Android and the desktop have no native Apple sheet. They use Apple's web flow, which only
redirects to an HTTPS URL registered with Apple, so the server has to receive the redirect.

1. **Owner:** developer.apple.com → Identifiers → a **Services ID** (e.g.
   `app.gains.Gains.web`) with Sign in with Apple enabled, primary App ID `app.gains.Gains`,
   domain `api.gains.gerra.sh`, return URL `https://api.gains.gerra.sh/auth/apple/callback`.
   Set it as `APPLE_SERVICES_ID` in `secrets/.env` (which also makes it an accepted audience)
   and run `deploy_server.py secrets`.
2. `GET /auth/apple/start?redirect=<app callback>&state=<client state>` redirects to
   `https://appleid.apple.com/auth/authorize` with `response_type=code id_token`,
   `response_mode=form_post` (required when asking for name or email), `scope=name email`, the
   Services ID and a server-side `state` binding the app's redirect and state.
3. `POST /auth/apple/callback` (form post) verifies the `id_token` like `/auth/apple` does,
   takes the name from the `user` JSON Apple sends the first time, signs the person in, and
   stores the code's refresh token (item 6's exchange, with the Services ID as client id).
   It then redirects to the app's callback with a **one-time code**, never our bearer token in
   a URL.
4. `POST /auth/exchange {code}` → `{token, user}`, the same response as `/auth/apple`. The code
   works once and expires within a minute.
5. Only allow app callbacks from a fixed list: the Android scheme or App Link from item 11, and
   `http://127.0.0.1:<any port>` for the desktop.

Tests: route tests with a fake verifier. The callback issues a code, the code works once and
expires, and an unlisted redirect is refused.

### 11. Android: Sign in with Apple

- [x] Done
  Done in #95
  Steps 1–3 are in. The owner's step 4 switches it on (`gains.appleServicesId`) and step 5 makes
  the App Link verified; until step 5 the site's `/auth/done` page finishes the sign-in with its
  "Open Gains" button. Android is not compiled in CI: build it in Android Studio and run the test
  plan's Android section.

**Milestone:** Android launch. **Depends on:** 8, 10.

1. `AccountKind.APPLE` in `AndroidIdentityProvider`: open `/auth/apple/start` in a Custom Tab
   with the App Link `https://gains.gerra.sh/auth/done` (`AppleWebFlow.ANDROID_CALLBACK`, the
   one URL the server allows) as the callback. `SignInCallbackActivity` receives it, hands the
   URL to the waiting provider (`WebSignIn`) and returns to `MainActivity`; the provider checks
   the state and returns the code.
2. This flow ends with our token, not an identity token. Item 16 gave `AccountRepository` the
   entry point: the provider returns an `ExchangeCode` (a `SignInProof`, like `IdentityAssertion`)
   and the repository trades it at `/auth/exchange`, then stores the token on the same path.
   `AppleWebFlow.startUrl` and `parseCallback` do the URL work.
3. Cancelling (back out of the Custom Tab) behaves like `SignInCancelledException`: no error, and
   the buttons come back. `MainActivity` resuming with a sign-in still waiting is the cancel.
4. **Owner:** set `gains.appleServicesId` for the Android build (the same Services ID as the
   desktop's, `APPLE_SERVICES_ID` on the server) in `gradle.properties` or the release workflow.
5. **Owner:** put the SHA-256 fingerprints of the Play App Signing key and the upload key (Play
   Console → App integrity; the debug key's too for local builds) into
   `site/.well-known/assetlinks.json` and run `python3 tools/deploy_server.py site`. Then
   `adb shell pm get-app-links app.gains` says `verified`.

### 12. Android: keep the token in the Keystore

- [x] Done
  Done in #82

**Milestone:** Android launch. **Depends on:** nothing.

A `KeystoreTokenVault` in `androidMain`: an AES-GCM key in the Android Keystore encrypts the
token, and the ciphertext lives in private `SharedPreferences`. No new library
(`security-crypto` is deprecated). Register it in the Android Koin module like iOS does with
`KeychainTokenVault`. The migration from the `sync_state` row already happens in `SyncStore`.
Update `docs/sync.md` "What the client does".

### 13. Android: release workflow and Play closed testing

- [x] Done
  Done in #97
  Steps 1–4 are in: `composeApp/android.gradle` stamps the version, the Google Play workflow
  (`.github/workflows/play.yml`, `tools/play.py`) bundles and uploads, and the Release workflow
  calls it next to TestFlight. The owner's step 2 (the upload key) and step 3 (the service
  account, the first release by hand) switch it on through the five secrets in
  [`docs/play.md`](play.md); until they exist the job says so in the run summary and skips
  itself, so TestFlight rounds are unaffected. Android is not compiled in CI: the first upload
  proves the bundle, and a wrong `android.gradle` shows up in Android Studio.

**Milestone:** Android launch. **Depends on:** 8, 9.

1. `versionCode` from the GitHub run number and `versionName` from the same marketing version
   as iOS, the way `tools/testflight.py` does it.
2. Release signing with an upload key from repository secrets (**Owner** creates the key and
   the secrets), then `bundleRelease`.
3. Upload to the Play **closed testing** track through the Play Developer API, using a service
   account (**Owner:** Play Console → API access), from a `tools/play.py` in the style of
   `tools/testflight.py`, with tests. Run it from the release workflow alongside TestFlight.
4. `docs/testflight.md`, or a new `docs/play.md`: how to add testers and where the opt-in link
   is.

### 14. Publish on Google Play

- [ ] Done

**Milestone:** Android launch. **Who:** Owner. **Depends on:** 9–13, 20, 21 (Play takes only
API 36 bundles in production), the 14-day closed test, and the test plan's Android section.

Apply for production access, then promote the build. Add the store link to the landing page.

### 15. Desktop: Sign in with Google

- [x] Done
  Done in #83. The owner's step 1 switches it on: the Desktop app client's id in
  `GOOGLE_CLIENT_IDS` and in `gradle.properties` (`gains.googleDesktopClientId`), and its secret
  in `~/.gradle/gradle.properties` (`gains.googleDesktopClientSecret`). Until both are set the
  desktop's Google button stays hidden.

**Milestone:** Desktop (P1). **Depends on:** nothing.

1. **Owner:** a **Desktop app** OAuth client in the same project. It comes with a client
   secret, which Google documents as not secret for installed apps. Add the client id to
   `GOOGLE_CLIENT_IDS`.
2. Reuse `GoogleOAuth` with a loopback redirect: open a `ServerSocket` on `127.0.0.1:0`, use
   `http://127.0.0.1:<port>` as the redirect URI, open the URL with `Desktop.browse`, read one
   request, answer with a "you can close this tab" page, then `parseCallback` → `exchange`
   (with the client secret). Generalize `GoogleOAuth.authorizationUrl` / `exchange` to take the
   redirect URI instead of deriving it from the iOS scheme, and keep the iOS behavior and tests
   as they are.
3. A `DesktopIdentityProvider` and a desktop `AuthConfig` in `desktopMain`'s Koin module.
   Closing the browser can't be detected, so time out after a few minutes and treat it as a
   cancel.
4. Tests: `GoogleOAuthTest` for the loopback redirect URI.

### 16. Desktop: Sign in with Apple

- [x] Done
  Done in #86. The button shows once `gains.appleServicesId` in `gradle.properties` is set to the
  server's `APPLE_SERVICES_ID` (item 10's owner step); until then it stays hidden.

**Milestone:** Desktop (P1). **Depends on:** 10.

The same loopback listener as item 15, with `/auth/apple/start?redirect=http://127.0.0.1:<port>`
and then `/auth/exchange`. Use the same `AccountRepository` entry point as item 11.

### 17. Desktop: keep the token in the OS keychain

- [x] Done
  Done in #90

**Milestone:** Desktop (P1). **Depends on:** nothing.

**Owner decides:** a small library (e.g. `java-keyring`, which covers macOS Keychain, Windows
Credential Manager and libsecret) or calling the OS tools directly (`security` on macOS,
`secret-tool` on Linux, DPAPI through JNA on Windows). Either way, add a `TokenVault` in
`desktopMain` and fall back to `SqliteTokenVault` when no keychain is available (a headless
Linux box).
*Decided:* the OS tools, with no new dependency: `security` on macOS, `secret-tool` on Linux and
DPAPI through PowerShell (not JNA) on Windows, each behind `Keyring` in `desktopMain` with the
token on standard input or output only. `Keyring.detect()` picks one at start, probing the Linux
Secret Service with an item that never exists, and `main.kt` registers `SqliteTokenVault` when it
finds none. See docs/sync.md, "What the client does".

### 18. Email and password accounts

- [x] Done
  Done in #98
  Steps 2–5 are in. The owner's step 1 switches it on: the mail account in `secrets/.env` and
  the form's switch in each app (the owner action above). Until then the five routes answer 503,
  the server logs `email off`, and the form stays hidden. The mails go out over plain SMTP
  submission (`SmtpMailer`, no library), so any provider works; the confirmation and reset links
  open `site/verify.html` and `site/reset.html`, which post the token back through the site's
  vhost. Android files touched: `AndroidIdentityProvider.kt` (the `EMAIL` branch and the
  `passwordSignIn` field), `composeApp/android.gradle` (`PASSWORD_SIGN_IN`).

**Milestone:** More sign-in. **Depends on:** 2 (verified-only merging), 4.

1. **Owner decides:** the email provider for verification and reset mail (e.g. Postmark,
   Amazon SES, or SMTP through the alias's host). Its credentials go in `secrets/.env`.
   *Decided for now:* whichever it is, the server talks SMTP submission to it (`SMTP_*`,
   `MAIL_FROM`), so the choice is five lines in `secrets/.env` and no code.
2. **Server:** a `password_credential (user_id, email, hash)` table. Hash with Argon2id
   (Bouncy Castle has a pure-Java implementation). Routes: sign up, verify email, sign in,
   request reset, reset. Rate-limit sign-in and reset per email and per IP. Answer "if that
   address has an account, we sent a link" whether or not it exists. A password account joins
   an existing user by email **only after the email is verified** (item 2's rule).
3. **Client:** an email and password form on `SignInScreen` and in the Settings link card, on
   every platform, with strings in both languages. Verification and reset links open
   `gains.gerra.sh` pages (item 3's site) that call the server.
4. Apple guideline 4.8 is already met, since Sign in with Apple is offered.
5. `docs/sync.md` "Signing in": the third way in.

### 19. Passkeys

- [ ] Done

**Milestone:** More sign-in. **Depends on:** 3, 18.

1. **Owner decides:** can a passkey create an account on its own, or is it added from Settings
   to an account that already exists? Adding it from Settings is simpler and avoids accounts
   with no recovery email.
2. **Server:** WebAuthn registration and authentication routes with a JVM library (Yubico
   `java-webauthn-server` or `webauthn4j`), and a `passkey_credential` table. The relying party
   id is `gains.gerra.sh`.
3. **Well-known files** on the site (item 3):
   `/.well-known/apple-app-site-association` with
   `{"webcredentials":{"apps":["V5Y8M5GKZ6.app.gains.Gains"]}}`, and
   `/.well-known/assetlinks.json` with `delegate_permission/common.get_login_creds` for the
   Android package and its signing certificates' SHA-256 fingerprints.
4. **iOS:** the `webcredentials:gains.gerra.sh` associated domain in `iosApp.entitlements`.
   **Owner:** enable Associated Domains on the App ID, regenerate the App Store profile and
   update `IOS_APP_STORE_PROFILE_BASE64`. Then use `ASAuthorizationPlatformPublicKeyCredentialProvider`
   in `IosIdentityProvider`.
5. **Android:** Credential Manager's `CreatePublicKeyCredentialRequest` /
   `GetPublicKeyCredentialOption`.
6. Desktop: out of scope for now.

### 20. Every authenticated route checks that the account still exists

- [x] Done
  Done in #101.

**Milestone:** iOS launch. **Depends on:** nothing. **Launch blocker:** items 7 and 14 depend
on it.

Our tokens are stateless: `SessionTokens` signs the user id into an HS256 JWT that lives 30 days,
and `ApplicationCall.userId()` in `Routes.kt` checks the signature and the expiry, nothing else.
`GET /auth/me` and `POST /auth/refresh` look the user up afterwards and answer 401 when the row is
gone, but `POST /sync/push`, `GET /sync/pull`, `PUT /sync/blobs/{kind}/{id}` and
`GET /sync/blobs/{kind}/{id}` take the id straight from the token, and `Store.push()` and
`putBlob()` don't need a `user` row (`document` and `blob` have no foreign key to it). So after
`DELETE /auth/account` a copy of the token still writes documents and photos under the deleted id
for up to 30 days, and the person's other device, still signed in, keeps pushing there and only
learns it is signed out at its weekly refresh. The id is never handed to anyone else (`user.id` is
`AUTOINCREMENT`), so the rows are dead weight rather than a leak, but data the person asked us to
delete must not come back, and a token for a deleted account must be worth nothing.

1. One place that says who is calling: replace `ApplicationCall.userId()` with a helper that
   verifies the token **and** loads the `user` row, 401 `no such user` when it is gone, and make
   every protected route take the user from it: `GET /auth/me`, `POST /auth/refresh`,
   `DELETE /auth/account` (deleting twice is then a 401, not a second 204) and the four `/sync/*`
   routes. Nothing else calls `SessionTokens.userId`. One primary-key lookup per request on a
   local SQLite file costs nothing worth measuring; don't cache it, a cache reopens the window.
   *Alternative not taken:* Ktor's `ktor-server-auth` bearer provider would make the check
   structural (`authenticate {}` around the routes), but it adds a module for what one helper
   does with eight routes. Revisit if the route count grows.
2. **Decide, and write the decision here:** keep the 30-day stateless token, or add revocation?
   *Recommended:* keep it. With the lookup on every request a deleted account is out at once, and
   rotating `JWT_SECRET` signs everyone out, which covers everything the app offers today (there
   is no "sign out everywhere" button). A `token_version` column on `user`, carried as a claim
   and compared on the same lookup, is the one-line path to per-account revocation when a screen
   wants it; it is listed under [After launch](#after-launch), not here.
   *Decided:* keep the 30-day stateless token, with the lookup on every request.
3. `docs/sync.md` "Signing in", step 4: a request is also refused once its account is gone, so a
   deleted account's tokens stop working at once, on every device. The client already treats a
   401 as "Signed out on the server" (`SyncApi`'s `unauthorized`), so nothing changes in the app.

Tests, in `ServerTest`: after `DELETE /auth/account` with token *a*, every route answers 401 to
*a*: `GET /auth/me`, `POST /auth/refresh`, `DELETE /auth/account`, `POST /sync/push`,
`GET /sync/pull`, `PUT /sync/blobs/session_photo/x` and `GET /sync/blobs/session_photo/x`; and
the store holds no `document` or `blob` row for the old user id afterwards (the push wrote
nothing). A token minted for a user id that never existed gets the same 401.

### 21. Android: target API 36 (Android 16)

- [x] Done
  Done in #102. Steps 1 and 2 are in: `compileSdk` and `targetSdk` are 36 with AGP unchanged,
  what Android 16 changes for Gains is written down under step 2, and the one thing it needed
  in code (the status bar icons) is in `MainActivity`. Android is not compiled in CI: the
  owner's step 3 builds it in Android Studio with SDK 36 and runs the test plan's Android 16
  section, and step 4 checks the target SDK in Play Console on the next round. `play.yml` needs
  nothing: the runner image or AGP supplies the platform.

**Milestone:** Android launch. **Depends on:** nothing. Easier once item 25 is in, which then
builds it; until then the owner builds it in Android Studio (rule 3). **Launch blocker:** item 14
depends on it.

`android-compileSdk` and `android-targetSdk` in `gradle/libs.versions.toml` are 35. Google Play's
target API level requirement is now API 36 for new apps and for updates, so item 14 can't ship a
bundle that targets 35.

1. Set both to `36`, and the requirement line in `docs/development.md` ("SDK 35") with them. AGP
   8.11.1 takes API 36; if it warns or refuses, lift **AGP alone** here, to the smallest version
   that does, and leave the rest of the version catalog to item 27.
2. Read Android 16's behaviour-changes page for apps targeting 36 and write down here what
   touches Gains. Known so far: edge-to-edge is enforced and the opt-out attribute is ignored
   (check `MainActivity`'s insets around the bottom bar and the keyboard, with
   `adjustResize`); predictive back is on by default (Compose's back handling, and
   `SignInCallbackActivity` returning to `MainActivity`); orientation and resizability
   restrictions are ignored on large screens (Gains declares none, so nothing to do); Play's
   16 KB page-size requirement for native libraries (Gains ships none of its own, so confirm
   with `unzip -l` on the bundle that no dependency brings a `.so`); and anything new about
   alarms and `BOOT_COMPLETED` receivers (`AndroidNudgeScheduler`'s `setWindow` with its
   ten-minute window, and `BootReceiver`).
   *Found*, going through [the changes for apps targeting 36](https://developer.android.com/about/versions/16/behavior-changes-16)
   and [the changes for all apps](https://developer.android.com/about/versions/16/behavior-changes-all):
   - **AGP:** 8.11.1 builds against API 36 (8.9.1 is the first that does), so nothing else in the
     version catalog moves.
   - **Edge to edge:** already enforced since the app targets 35; 36 only removes the opt-out,
     which Gains never set. The Compose side already pads for the bars (`statusBarsPadding` on
     the app's column, `navigationBarsPadding` on the bottom bar and the sheets,
     `safeDrawingPadding` on sign-in and onboarding). What it didn't do was colour the bars'
     icons: the manifest's platform theme keeps them light, so in the light theme the clock and
     battery would be white on white. `MainActivity` now sets the icons from the theme chosen in
     Settings on Android 15 and later, through a `systemBars` hook on `App` (the other platforms
     pass nothing). **Check on the device:** the keyboard. With enforced edge to edge,
     `adjustResize` no longer shrinks the window; only `ExercisePickerSheet` pads for the IME.
     If a field in the workout editor or on sign-in ends up under the keyboard, that is a small
     follow-up (an Android-only IME padding, since iOS moves its own view).
   - **Predictive back:** on by default, and `onBackPressed` is no longer called. Gains doesn't
     override it: the navigator's back goes through Compose's `BackHandler`
     (`OnBackPressedDispatcher`, which uses `OnBackInvokedCallback` on 33 and later), and at the
     root the handler is off so the system's back-to-home preview runs. `SignInCallbackActivity`
     finishes in `onCreate` and never sees a back; backing out of the Custom Tab lands on
     `MainActivity`, whose resume is the cancel, as before.
   - **Large screens:** orientation and resizability are ignored at 600 dp and wider. Gains
     declares neither, so nothing changes.
   - **Alarms and boot:** nothing new for inexact alarms or `BOOT_COMPLETED` receivers.
     `setWindow` and `BootReceiver` stay as they are. The fixed-rate change is about
     `scheduleAtFixedRate`, which Gains doesn't use.
   - **Not used by Gains:** the elegant-text-height APIs, health and fitness permissions
     (no `BODY_SENSORS`), the Bluetooth bond changes, the MediaStore version lockdown and
     app-owned photos (the photo picker needs no media permission), and the opt-in parts (Safer
     Intents, the local network permission). The intent-redirection hardening for all apps
     doesn't apply either: no intent is relaunched from another intent's extras.
   - **16 KB pages:** Gains has no native code of its own, and none of its Android dependencies
     is known to bring a `.so` (SQLDelight uses the platform's SQLite, Ktor OkHttp, the rest is
     JVM). The owner confirms it: `unzip -l composeApp/build/outputs/bundle/release/composeApp-release.aab | grep '\.so$'`
     prints nothing.
3. Build (`assembleDebug`, `bundleRelease`) and run the JVM tests against the new SDK. Then, on
   an Android 16 device or emulator, the test plan's Android 16 section: the workout
   notification and its "Skip rest" action, a streak reminder firing (and after a reboot), CSV
   "Open with" and the share sheet, the App Link `https://gains.gerra.sh/auth/done`, Sign in with
   Google (Credential Manager) and with Apple (the Custom Tab), and backup and restore with
   `bmgr` as under item 12.
4. The Google Play workflow: `play.yml` relies on the runner image, or AGP, for the platform.
   Run it by hand (*Run workflow*) and check in Play Console → App bundle explorer that the
   uploaded bundle says target SDK 36. `docs/play.md` gets a line if anything about it changed.

Tests: the existing JVM tests on the new SDK; the manual checks in the test plan.

### 22. Server: run as an unprivileged user, with systemd hardening

- [ ] Done

**Milestone:** Hardening (P1). **Depends on:** nothing. The deploy does all of it; the owner
watches the first one and removes the old tree afterwards.

`deploy/gains-server.service` runs the JVM as `root`, from `/root/Projects/gains-server`, with
`/var/lib/gains` and everything else on the box writable. One bug in the server or in a
dependency is then root on the box that also serves `gains.gerra.sh` and the other sites.
Proportional to one developer and one box: a system user, the standard sandboxing lines, and
nothing that needs a second machine.

1. A dedicated system user: `tools/deploy_server.py install` (already root on the box) creates
   `gains-server` when it is missing (`useradd --system --home-dir /var/lib/gains --shell
   /usr/sbin/nologin`) and makes `/var/lib/gains` `gains-server:gains-server`, mode 750, with a
   `chown -R` of what is in it (the database, its `-wal` and `-shm`). Nothing else on the box
   becomes writable.
2. Move the install out of `/root`, which no other user can read and which `ProtectHome=` hides:
   `HOME` in `deploy_server.py` becomes `/opt/gains-server` (`current/`, `secrets/`, the unit
   copy), and with it the workflow's `remote_path`, the `prepare` and `secrets` commands, the
   unit's `WorkingDirectory` and `ExecStart`, `tools/test_deploy_server.py` and `docs/sync.md`
   "Deploying". `secrets/.env` is installed `root:gains-server` mode 640, so the server reads it
   and nobody else does; `current/` stays root-owned and world-readable (jars are not secret).
   On the first install with the new path, copy `/root/Projects/gains-server/secrets/.env` over
   when the new one is missing, so that deploy needs no laptop step. **Owner:** once `/health`
   answers from the new path, remove `/root/Projects/gains-server`.
3. The unit: `User=gains-server`, `Group=gains-server`, `UMask=0077` (item 24 wants the database
   files 600), `NoNewPrivileges=true`, `PrivateTmp=true`, `ProtectSystem=strict`,
   `ProtectHome=true`, `ProtectKernelTunables=true`, `ProtectKernelModules=true`,
   `ProtectControlGroups=true`, `RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX`,
   `LockPersonality=true`, `RestrictRealtime=true`, `ReadWritePaths=/var/lib/gains`. **Not**
   `MemoryDenyWriteExecute=true`: the JVM's JIT needs memory that is both writable and
   executable. `SystemCallFilter=@system-service` is optional; try it, and drop it if the JVM is
   killed at start. `systemd-analyze security gains-server` before and after is the measure.
4. Verify on the box after the deploy: `systemctl show gains-server -p User`, `/health`, a
   sign-in, a sync and a photo upload from a device (the blob write), `ls -l /var/lib/gains`
   with the files owned by `gains-server`, a journal with no `EACCES` or `Read-only file
   system`, and `sqlite3 /var/lib/gains/gains-server.db` still working as root for the
   guest-list line under item 3. A restart (`deploy_server.py secrets`) and a second deploy both
   come back healthy.

Tests: `tools/test_deploy_server.py` for the new paths, the user step and the secrets copy, in
the style of the existing ones. The manual checks are step 4, repeated in the test plan.

### 23. Server: rate limits on sign-in, sync and uploads

- [x] Done
  Done in #104. Steps 1–4 are in. The numbers chosen: `/auth/` 10 a minute per address with a
  burst of 10; `/sync/` 10 a second with `burst=200 delay=100`, so past the first 100 a request
  is slowed rather than refused and one device syncing in sequence never sees a 429 (checked
  against a stub: 400 pulls in a row all pass, in 30 s); 8 blob requests at a time per address;
  the guest list's zone on `/guest-list` here too; per account, 200,000 feed rows (413) and 2 GB
  of photo bytes (507). The client needed no change: only a 401 signs out, and a refused run
  stays in the change log for the next one (`SyncRoundTripTest`). Merging deploys the vhost
  through the Deploy site and nginx workflow; the test plan's item 23 check is left.

**Milestone:** Hardening (P1). **Depends on:** nothing.

Only `/guest-list` is rate-limited today, and only on the site's vhost
(`gains.gerra.sh.conf`: zone `gains_guest_list`, six requests a minute per address). The API vhost
proxies everything under `/`, so `POST https://api.gains.gerra.sh/guest-list` skips that limit, and
`/auth/*`, `/sync/*` and the blob routes have none. A sign-in token is verified against Google's
or Apple's keys, so an unlimited `/auth/google` is a free way to make us work; an unlimited
`PUT /sync/blobs` is a free way to fill the disk 8 MB at a time.

Keep it at the edge, in nginx, where the address is the real one (nginx terminates TLS, so
`$binary_remote_addr` is the client), plus two ceilings in the server that a stolen token can't
talk its way past:

1. `deploy/nginx/api.gains.gerra.sh.conf`: a zone for `/auth/` (sign-in, exchange, refresh and
   deletion; e.g. 10 a minute per address, `burst=10 nodelay`), a zone for `/sync/` that a first
   sync of a long history still passes (a push is 200 documents a request, a pull 500, and every
   photo is one `PUT`; e.g. 10 a second with `burst=100`), `limit_conn` of a handful per address
   on the blob routes, and `limit_req zone=gains_guest_list` on `/guest-list` here too (zones
   are global to nginx, so the site's zone works in this vhost). `limit_req_status 429` as the
   site's vhost does. Then `python3 tools/deploy_server.py nginx`.
2. Per-user ceilings in the server: a cap on the total blob bytes and on the document count per
   user (say 2 GB and 200,000; write down the numbers chosen), checked in `Store.putBlob` and
   `Store.push` and answered with 507 and 413. Nothing a person reaches; a bound on what one
   account can cost.
3. The client: a 429, 413 or 507 must land as "Couldn't sync" and be retried later, never as
   "Signed out on the server". Check `SyncEngine` and `SyncApi.unauthorized`.
4. `docs/sync.md`: the limits, under "The server" and "Deploying".

Tests: server tests for the two ceilings (a push over the count is refused with the status and
writes nothing; a blob over the byte cap likewise). The nginx limits are checked by hand (test
plan, item 23).

### 24. Server: the Apple refresh tokens and the database at rest

- [ ] Done

**Milestone:** Hardening (P1). **Depends on:** 22 (the file modes assume the unit's `UMask`).

Item 6 keeps each Apple refresh token in plain text in `identity.refresh_token`, in
`/var/lib/gains/gains-server.db`, a file the JVM created as root with the default umask, so
world-readable. A refresh token lets whoever holds it act on that person's Sign in with Apple
grant, so it deserves better than the workout data next to it, and the database itself deserves a
mode that says who may read it.

1. Encrypt the refresh tokens in the application: AES-256-GCM with a key from `secrets/.env`
   (`REFRESH_TOKEN_KEY`, 32 random bytes, base64: `openssl rand -base64 32`), a random nonce per
   row, and a `v1:` prefix on the stored value so a row from before is recognised as plain text
   and re-encrypted at the person's next sign-in (or once at start, in a pass over the table).
   Decrypt in one place, `revokeAppleTokens`. Without the key the server keeps storing plain
   text and logs `refresh token encryption off` at start, like the other optional secrets;
   **Owner** sets it and runs `deploy_server.py secrets`. A key of its own rather than
   `JWT_SECRET`, so signing everyone out (rotating that) doesn't lose the tokens. Document it in
   `secrets/README.md` and `secrets/.env.example`.
2. The files: with item 22's `UMask=0077` new files are 600; `install` also runs `chmod 600` on
   the existing database, `-wal` and `-shm` once. `secrets/.env` is 640 from item 22.
3. Backups. `docs/sync.md` says "back it up by copying the file", and nothing does so today.
   **Owner decides:** rely on the box's own backups, or add a nightly `sqlite3 … ".backup"`
   (safe under WAL) into a 700 directory, kept for a few days, from a systemd timer that
   `deploy_server.py install` puts in place. Any copy that leaves the box is encrypted first
   (`age` or `gpg`), and the Hetzner backup or snapshot setting is checked for encryption at
   rest. Write the decision under "Deploying".
4. `docs/sync.md`: the schema note (`refresh_token` is ciphertext) and the configuration list.

Tests: a round trip of the cipher; a plain-text row from before is still revoked on deletion, and
is ciphertext after the next sign-in; with the key missing the row stays plain text and the log
line says so.

### 25. Android in CI: build, lint and the JVM tests

- [x] Done
  Done in #105, green in #107: the job's first runs found what nothing had compiled before, a
  Groovy misparse of `versionCode` in `android.gradle`, a type-inference cycle in
  `MainActivity`, and okhttp 5.5.0 (through Ktor) asking for compileSdk 37, held at 5.4.0 until
  item 27. Lint passes without a baseline.

**Milestone:** CI (P1). **Depends on:** nothing.

Every pull request runs the shared, desktop and server tests and compiles the iOS klibs, and
none of it touches `androidMain`: a broken Android file is found by the release round (item 13)
or in Android Studio. The Google Play job already shows the `ubuntu-latest` image carries the
Android SDK and that AGP fetches what the image lacks. This item is compilation, lint and
resources only: no emulator, no instrumented or UI tests, no screenshots, no scenario runs.

1. A second job in `ci.yml`, `android`, next to the existing one, which stays exactly as it is
   (`-Pgains.android=false`, so its iOS compile keeps its speed): `setup-java`, `setup-gradle`,
   then `./gradlew :composeApp:assembleDebug :composeApp:lintDebug --no-daemon`, plus the
   Android unit tests where a module has any (`:shared:testDebugUnitTest`,
   `:composeApp:testDebugUnitTest`; don't write new ones for this item). The two sign-in
   properties stay empty, as in a local build.
2. Lint: `lint { abortOnError true }` in `android.gradle`, errors only. If the first run is loud,
   check in a `lint-baseline.xml` next to it and burn it down in ordinary pull requests, not
   here.
3. Once it is green: rule 3 of [How to use this file](#how-to-use-this-file) becomes "Android is
   compiled and linted in CI; a device is still needed for the test plan", the first bullet under
   `docs/development.md` [Known limitations](development.md#known-limitations) goes, and the
   checks in `auth-plan.md` gain the Gradle line. The notes in items 9, 11 and 13 stay as
   history.

Tests: the job itself. A pull request that breaks an `androidMain` file must go red.

### 26. iOS in CI: an Xcode simulator build

- [x] Done
  Done in #106. Steps 1, 2 and 4 are in: `python3 tools/testflight.py build-simulator` (next to
  the archive, so the project and scheme are named once; tests in `tools/test_testflight.py`)
  and the `ios` job on `macos-26`, the TestFlight image, with its Gradle and `~/.konan` caches.
  Two changes from the text below: the job lives in `.github/workflows/ios.yml`, not `ci.yml`,
  because Actions filters paths per workflow, not per job; and the build passes `ARCHS=arm64`,
  so Gradle links the simulator framework for the runner's architecture only rather than a fat
  arm64 + x86_64 one. Step 3 has the recommended answer until the owner says otherwise (the
  owner action above). The `test` job's Linux klib compile stays as it was.

**Milestone:** CI (P1). **Depends on:** nothing.

The klib compile on Linux catches Kotlin that won't build for iOS, and nothing else: Swift,
`project.pbxproj`, `Info.plist`, the entitlements, `Config.xcconfig`, the asset catalogue and the
framework link are first checked by the TestFlight archive, after the release branch is cut.
`auth-plan.md` says those edits "can't be checked"; a simulator build on a macOS runner checks
them, without signing and without booting anything. Compile and link only: no simulator boot, no
XCUITest, no screenshots, no scenario runs.

1. A `tools/ios_build.py` (or a `build-simulator` command in `tools/testflight.py`, whichever
   keeps the paths in one place) that runs
   `xcodebuild build -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug
   -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO`, with tests for its
   argument building like `tools/test_testflight.py`. The generic destination needs no
   simulator device to exist.
2. A job in `ci.yml`, `ios`, on the macOS image the TestFlight workflow archives with, with the
   `setup-java`, `setup-gradle` and `~/.konan` cache steps copied from `testflight.yml`. Keep the
   Linux klib step in the `test` job: it is the fast, cheap signal, and this one is the full one.
3. **Owner decides** where it runs. macOS minutes cost ten times Linux ones on a private
   repository (a public one pays nothing). *Recommended:* on pull requests and pushes to `main`
   with `paths-ignore` for `docs/**`, `site/**`, `server/**`, `deploy/**` and `tools/**`; a
   path-filtered job must not be a required check, or it hangs the merge. Not on the two-hourly
   release branches, which archive anyway.
4. Then the iOS bullet under `docs/development.md` Known limitations says the simulator build
   runs in CI and only archiving needs the signing material, and `auth-plan.md`'s "No Mac or
   Xcode is available to the agent" becomes "CI builds the Xcode project; the agent still can't
   run it".

Tests: the job. A pull request that breaks a Swift file or `Info.plist` must go red.

### 27. A dependency modernization pass

- [x] Done
  Done in #108, one commit per group, each with `:shared:desktopTest`, `:server:test` and the
  `:shared` iOS klib green locally, and the whole pass through the three CI jobs. What moved:
  1. Kotlin 2.4.20 and Compose Multiplatform 1.11.1 (Jetpack Compose 1.11.2 on Android). Not
     1.12.1, the current one: the first CI run showed its Jetpack Compose 1.12 artifacts refuse
     anything below compileSdk 37 and AGP 9.1, so 1.12 waits for the AGP 9 move under step 3.
     Material 3 has had its own version line since 1.9, so it is `compose-material3 = "1.9.0"`,
     the one the 1.11.1 plugin pairs with; Compose no longer publishes `iosX64`, so the Intel
     simulator target is gone from both modules (the CI simulator build was arm64 only already);
     and `runDesktopComposeUiTest` now wraps the test in `runTest`, whose one-minute default cut
     the screenshot walk short, so `:composeApp`'s tests set
     `kotlinx.coroutines.test.default_timeout` to the task's own timeout;
     `material-icons-core` still resolves at 1.7.3, its last release; the resources and desktop
     UI-test artifacts moved from the plugin's deprecated `compose.*` accessors into the
     catalog. `kotlin.native.enableKlibsCrossCompilation` is still needed: without it
     `compileKotlinIosArm64` is skipped on Linux.
  2. Koin 4.2.2, with no code change.
  3. **AGP stays on 8.x: 8.13.2, the last, on Gradle 8.14.5** (Kotlin 2.5 will want 8.14.4 or
     newer). AGP 9 refuses `com.android.application` in a module that also applies the Kotlin
     Multiplatform plugin: `:composeApp` would have to become a KMP library with a new Android
     app module next to it, and `:shared` move to `com.android.kotlin.multiplatform.library`.
     That is item 38. AGP 8.13 supports API 36.1 at most, so the okhttp 5.4.0 pin from
     item 25 stays until then, and so does Compose Multiplatform 1.11 (step 1).
  4. SQLDelight 2.4.0, activity-compose 1.13.0, credentials 1.6.0, browser 1.10.0, googleid
     1.2.1, and on the server logback 1.6.4 and Bouncy Castle 1.86. Ktor 3.6.0, coroutines
     1.11.0, serialization 1.11.0 and kotlinx-datetime 0.8.0 were already the latest stable.
  5. `docs/development.md`: the Android Studio version AGP 8.13 needs, and a note under "Built
     with". The JDK stays 17.
  Left for the owner: the TestFlight round and the Play bundle from the next release branch, and
  the test plan's item 27 smoke on each.

**Milestone:** Maintenance (P2). **Depends on:** 25, 26 (an upgrade CI can't build for Android
or Xcode is a guess).

`gradle/libs.versions.toml` today: Kotlin 2.3.21, Compose Multiplatform 1.7.3, Koin 4.0.4, AGP
8.11.1 (Gradle 8.14.3), SQLDelight 2.3.2, Ktor 3.6.0, coroutines 1.11.0, serialization 1.11.0,
kotlinx-datetime 0.8.0, credentials 1.5.0, activity-compose 1.10.1, browser 1.8.0. Compose
Multiplatform and Koin are several stable releases behind; Ktor, SQLDelight and the kotlinx
libraries are current or a step off. This is one deliberate pass in groups, each group its own
commit with the full check set green before the next, not a bump of everything at once and not
one-off bumps in unrelated pull requests. If a group won't go (an API removal that spreads into
many files), stop it, write down why here and move on: a partial pass that builds beats a full
one that doesn't.

The check set after each group: the CI set (`:shared:desktopTest`, `:composeApp:desktopTest`,
`:server:test`, the iOS klibs), items 25 and 26, then one TestFlight round and one Play bundle
from the release branch and a smoke of the test plan on each (sign in, sync, a photo, an import).

1. **Kotlin and Compose Multiplatform together** (the Compose compiler plugin ships with
   Kotlin): the current Compose Multiplatform stable and the Kotlin it is built with, or newer.
   Watch: `material-icons-core` sits on its own line pinned to 1.7.3 because the icons no longer
   ship with Compose (check it still resolves), the resources API, the `desktopTest` screenshot
   rendering, the iOS release link's memory (`gradle.properties` has the story) and whether
   `kotlin.native.enableKlibsCrossCompilation` is still needed.
2. **Koin** to the current stable 4.x: `SharedModule.kt`, the Android and desktop modules, and
   `koin-android`'s start-up in `GainsApplication`.
3. **AGP** to the current supported generation with the Gradle wrapper it needs, unless item 21
   already lifted it. AGP 9 rewires Kotlin (its own Kotlin support) and drops old DSL: read its
   migration page first, and if it asks for more than a version bump, stay on the latest 8.x and
   write that down here.
4. **Ktor, SQLDelight, coroutines, serialization, kotlinx-datetime, the androidx libraries**: the
   latest patch or minor of each, in one group.
5. `docs/development.md` "Built with", and the JDK requirement if any of it moves.

Tests: the check set above, after each group.

### 28. Supply chain: pinned actions and dependency scanning

- [x] Done
  Done in #111. Each action is pinned to the release its major tag pointed at, so nothing
  changed behaviour; majors are item 29's. CodeQL is a workflow (`codeql.yml`), not the default
  setup: autobuild can't be told which tasks a Kotlin Multiplatform build compiles on Linux, and
  the workflow's own run on the pull request showed the extractor taking Kotlin 2.4.20. Left:
  the owner's dependency graph and Dependabot alerts switches.

**Milestone:** Maintenance (P2). **Depends on:** nothing.

The workflows reference actions by tag (`actions/checkout@v4`, `gradle/actions/setup-gradle@v4`,
`burnett01/rsync-deployments@6.0.0`, …). A tag moves, and a compromised tag on a workflow that
holds the App Store Connect key, the upload keystore, the Play service account or the deploy SSH
key hands them over. The rsync action is the only third-party one, and it gets `DEPLOY_KEY`.

1. Pin every `uses:` to a full commit SHA with the version as a trailing comment
   (`actions/checkout@<sha> # v4.x.y`), the secret-holding workflows first (`release.yml`,
   `testflight.yml`, `play.yml`, `deploy.yml`, `deploy-site.yml`), then `ci.yml`,
   `screenshots.yml` and `release-branch.yml`. Local `uses: ./.github/workflows/…` need nothing.
2. Drop `burnett01/rsync-deployments`: `tools/deploy_server.py` already runs `rsync` over the
   deploy key for `site`, so a `build` command rsyncs `server/build/install/gains-server/` to
   `current/` the same way, with the same `--delete`, and the workflow calls it. One fewer third
   party with the key, and one fewer thing to pin. Tests in `tools/test_deploy_server.py`.
3. The dependency graph: `gradle/actions/dependency-submission` on pushes to `main` (a small
   workflow of its own), so GitHub knows the Gradle dependencies and Dependabot alerts cover
   them (**Owner:** Settings → Code security → Dependabot alerts on). Then
   `actions/dependency-review-action` in `ci.yml` for pull requests.
4. CodeQL: try the default setup for Java and Kotlin. If its Kotlin extractor doesn't take the
   Kotlin in the version catalog (it lags releases), skip it and note that here; for a repository
   this size the graph and the alerts are most of the value.
5. `docs/development.md` "Recipes": a short "Dependencies and actions" note, how a pinned action
   is bumped and where the alerts show.

Tests: `tools/test_deploy_server.py` for step 2; the workflows themselves by a deploy and a
release round.

### 29. Supply chain: Dependabot, and Gradle dependency verification where practical

- [x] Done
  Done in #112. Step 1 is `.github/dependabot.yml`: the actions weekly as one pull request, and
  Gradle weekly in the five groups below. Kotlin and Compose Multiplatform get patches only,
  since a minor of either is the deliberate pass (Compose 1.12 needs AGP 9, item 38); Koin and
  AGP no majors; okhttp stays below 5.5.0 until item 38. Step 2 is not in: Dependabot doesn't
  regenerate `gradle/verification-metadata.xml`, so each of its Gradle pull requests would fail
  CI until someone rewrote the file by hand, which is the case this step said to take it out
  for; and the metadata must cover Google's Maven and the Kotlin/Native toolchain, which the
  agent's environment can't reach, so it could only have been written from a laptop or a CI
  run. The SHA pins from 28 and the grouping here are the proportional version. Step 3 is the
  paragraph under "Dependencies and actions" in `docs/development.md`. Left: the test plan's
  item 29 checks on the first Monday round.

**Milestone:** Maintenance (P2). **Depends on:** 27, 28 (Dependabot before the pass would open a
dozen pull requests the pass then supersedes; the SHAs from 28 are what it keeps current).

1. `.github/dependabot.yml`: `github-actions` weekly (it bumps pinned SHAs and their version
   comments), and `gradle` weekly, grouped so a round is one or two pull requests rather than
   twenty: a `kotlin-compose` group (Kotlin, Compose Multiplatform), `androidx`, `ktor`,
   `kotlinx`, and the rest; majors of Kotlin, Compose, Koin and AGP `ignore`d, since each of those
   is a deliberate pass like item 27. CI, with 25 and 26, is the review. Renovate does the same
   with finer grouping but needs an app install; Dependabot is the default unless the owner
   prefers otherwise.
2. Gradle dependency verification: `./gradlew --write-verification-metadata sha256 help` writes
   `gradle/verification-metadata.xml`, and from then on a changed artifact fails the build. Try
   it with checksums only (no PGP), with the Android variants configured (`gains.android=true`).
   If every Dependabot pull request then needs the file regenerated by hand, or the
   Kotlin/Native toolchain downloads fight it, take it out again and write down why: the pins
   from 28 and the grouping from step 1 are the proportional version for one developer.
3. `docs/development.md`: the note from item 28 gains the two.

Tests: a Dependabot pull request going through CI; a deliberately wrong checksum failing the
build, if step 2 stays.

### 30. Android: R8 for release builds

- [ ] Done

**Milestone:** Hardening (P2). **Depends on:** 21, 25 (a release build in CI to try it on).

`android.gradle` has `minifyEnabled false`, so the Play bundle ships every class of Compose,
Ktor, SQLDelight and Koin unshrunk. R8 makes it smaller and quicker to start and strips names; it
is an optimization and ordinary hygiene, **not** a security boundary (the token is in the
Keystore, not in the code) and not a launch blocker. It becomes one only if turning it on shows a
real release-only bug that was hidden so far.

1. `minifyEnabled true` and `shrinkResources true` on `release`, with
   `proguard-android-optimize.txt` and a `composeApp/proguard-rules.pro`. Keep rules for what
   reflects: kotlinx-serialization (the `@Serializable` classes in `Protocol.kt`, `Documents.kt`
   and the auth requests; the plugin ships consumer rules, check they cover them), Ktor's engine
   and logging (their consumer rules), SQLDelight (none needed), Koin (no reflection with the
   constructor DSL; `koin-android` ships rules), Credential Manager and `googleid` (consumer
   rules). Start with no rules of our own and add only what a crash asks for.
2. `bundleRelease` in the Android CI job too (unsigned), so an R8 failure shows on the pull
   request. Keep obfuscation on and send the mapping file to Play with the bundle
   (`tools/play.py`: `mapping.txt` next to the bundle, uploaded as the deobfuscation file), so
   Play Console stack traces read.
3. On a device with the release build from the closed testing track: the test plan's Android
   section end to end (both providers, sync both ways with a photo, a CSV import through "Open
   with", the workout notification, a reminder) and the Android 16 checks. A
   `ClassNotFoundException` or a serializer "not found" is a missing keep rule.

Tests: the existing tests; the manual pass in step 3.

### 31. Android backup: decide what a backup may carry

- [ ] Done

**Milestone:** Hardening (P2). **Depends on:** nothing. Best decided before item 14: what the
first public build ships decides what a later restore brings back.

`allowBackup="true"` with item 12's rules excludes the token file (`app.gains.sync.xml`) and
nothing else, so Auto Backup to the cloud and device-to-device transfer carry the workout database
(`databases/gains.db`: every session, body weight, programs), the photos under the app's files
and the preferences. That is what a guest wants, since it is their only copy. It is also health
data in Google's cloud under Google's terms, which the privacy policy says today ("Your device's
own backups (iCloud or Google) may include the app's data"); on iOS the Keychain item is
device-only while the database is in the iCloud backup on the same terms. So the current
behaviour is consistent with the policy, and this item is a decision written down rather than a
default nobody chose.

1. **Owner decides**, one of:
   - *Keep it* (recommended): a guest's data survives a lost phone; a signed-in account gets its
     data back from the server anyway, and the token is never in the backup. The policy already
     says so. Nothing changes but this paragraph.
   - *Exclude the database and the photos from cloud backup, keep device-to-device transfer*
     (`<cloud-backup>` excludes them, `<device-transfer>` doesn't): health data never sits in
     Google's cloud, and a new phone still gets it over the cable or Wi-Fi. A guest who loses the
     phone loses the data, and the sign-in screen and the policy must say so.
   - *Exclude both*: only an account keeps its data across phones; a guest starts from nothing.
2. Whatever is chosen: `backup_rules.xml` and `data_extraction_rules.xml` say it (with the
   comments saying why), the privacy policy's sentence matches it, iOS is given the same answer
   (the database file's `isExcludedFromBackup` if the choice is to exclude), and `docs/sync.md`
   "What the client does" names it next to the token file.
3. The test plan: `bmgr backupnow`, uninstall, reinstall, as item 12's check does, and what comes
   back matches the decision.

Tests: the manual check in step 3.

### 32. Navigation lifecycle: pin its invariants in tests

- [ ] Done

**Milestone:** Maintenance (P1). **Depends on:** nothing. Item 33 depends on it.

The app keeps its own back stack instead of AndroidX Navigation and ViewModels, on purpose: one
`Navigator` and `NavEntry` in `ui/nav/Navigation.kt` for all three platforms, `ScreenModel`s
held per entry and per class through `rememberScreenModel` (`ui/ScreenModel.kt`), and each
entry's saved UI state under its id in the root's `SaveableStateHolder` (`App.kt`, whose
`Navigator(onReleased = { stateHolder.removeState(it.id) })` drops it). Keep that design; this
item makes sure item 33 and later work can't change its behaviour without a red test. Nothing
is replaced unless a test here finds a real defect, and then the fix is its own pull request.

`composeApp/src/desktopTest/.../NavigationTest.kt` already covers: a covered screen keeps its
model, `peek`, a popped entry released only once it is off the screen too (or at once when it
wasn't drawn), `replace`, tab roots kept across switches, a model remade when its keys change,
one model per class, `rememberScreenModel` sharing one model between the places an entry is
drawn, and a model outside the navigator living with its composable. Add what is missing, in the
same file and style:

1. **Every entry is released exactly once, and only when gone.** A seeded, fixed sequence of a
   few hundred `push`, `pop`, `replace` and `switchTab` calls, with `attach`/`detach` pairs
   around some of them as the transitions and the swipe back do: afterwards `onReleased` has
   seen each entry that left the stack at most once, never a tab root or an entry still on the
   stack, and every model whose entry was released has an inactive `scope`. The models still
   active are exactly those of the entries on the stack, the tab roots and anything still
   attached. This is the leak check.
2. **An entry drawn twice** (the swipe back draws the previous entry under the current one while
   the transition still hosts it): popped with two hosts, it is released after the second
   `detach`, not the first.
3. **`switchTab` while the leaving screens are still attached:** they are released when they
   detach, not before; switching to the tab already shown drops what was pushed on it and keeps
   its root.
4. **`push` of the screen already on top** adds nothing and makes no entry; entry ids are unique
   for the navigator's life (the saved state is keyed on them).
5. **A change of language** through `rememberScreenModel` (it keys models on
   `LocalAppLanguage`): the entry's model is remade and the old one's `scope` is cancelled, while
   the entry, the stack and the entry's saved state stay. A composition test with a
   `SaveableStateHolder` and `rememberSaveable` shows the saved value survives the language
   change and is gone once the entry is released.
6. **The skip-rest lookup** that `App.kt` does today (`navigator.stack.mapNotNull { it.peek(...) }`):
   a model on a covered entry is found, one on a released entry is not.

Write each test against `Navigator`, `NavEntry` and `rememberScreenModel` only, not against
`App.kt`, so that item 33 moves code around them without touching the tests. If a test shows a
current behaviour that looks wrong, keep the test asserting today's behaviour, mark it with a
comment, and write it down here for a separate fix.

Tests: the above, in `:composeApp:desktopTest`.

### 33. `App.kt`: move the root's coordination into small, tested pieces

- [ ] Done (all steps below ticked)

**Milestone:** Maintenance (P1). **Depends on:** 32 (the lifecycle tests are the net). **One pull
request per step**, in this order, each leaving the app behaving exactly as before.

`composeApp/src/commonMain/kotlin/app/gains/App.kt` is about 590 lines. `App` picks the theme and
the language and owns the navigator and the saved state; `AppBody` then does, in one composable:
seeding the exercise catalogue and starting `SyncController`; opening Import for files shared in
(`IncomingFiles`); gating on the account (`SignInScreen`, with an `AccountLoading` sentinel
account) and on onboarding; working out the program's next day for the "+" menu; keeping the
platform's workout notice in step with the workout in progress, rest countdown included
(`LiveSessionNotifier`); planning the streak reminders (`StreakEngine` → `NudgeScheduler`);
answering the notice's taps (`ResumeRequests`, `SkipRestRequests`, the latter by peeking every
entry for a `SessionEditorModel`); and drawing the top bar, the swipe back, the screen
transition, the workout bar, the bottom bar and the `when` over every `Screen`. The logic in
those `LaunchedEffect`s can only be tested by composing the whole app.

The goal is an `App.kt` that assembles the top-level UI and nothing else, with each piece of
coordination in a small class of its own that has a narrow job, takes what it needs in its
constructor and has unit tests. **Not** one `AppCoordinator` that takes over everything, not a
navigation library, not an MVI framework, and no change to `Navigator`. The pieces live in
`composeApp/src/commonMain` (they use `LiveSessionNotifier`, `NudgeScheduler`, `Navigator` and
the texts, which are the UI module's), in a package of their own such as `app.gains.root`; what is
pure domain logic and not there yet goes to `shared` (as `StreakEngine` already did). No
platform code: the platform hooks stay the interfaces `App` is given today. Names and exact
boundaries are the implementer's call from the code; the steps say what moves.

Things to keep exactly as they are, and check in each step:

- `AppBody` sits inside `InLanguage`'s `key(language)`, so a change of language recomposes it:
  its effects restart (the reminders re-worded, which is wanted; `sync.start` and
  `seedCatalogue` run again, which is harmless). Keep that; if a step wants the sync above the
  language key, that is a behaviour change for its own pull request.
- The navigator and the saved state outlive the language (they sit in `App`), and the sign-in
  and onboarding gates draw nothing until their preference is read, so neither flashes at launch.
- Nothing starts the sync or plans a reminder outside the composition's lifetime: each piece is
  made with `remember` and run from a `LaunchedEffect` in the same place its code runs today.

Steps:

- [ ] **1. The workout notice and its two buttons.** A class (e.g. `LiveSessionNotices`) taking
  `LiveSessionRepository` and `LiveSessionNotifier`, with a `suspend fun run()` that holds
  today's `distinctUntilChanged` / rest-countdown re-post logic, and the handling of
  `ResumeRequests` and `SkipRestRequests` (open the running workout unless its editor is on top;
  let the running editor skip the rest, else `clearRest()`), given the `Navigator`. A clock
  parameter instead of `nowMs()` so tests use virtual time. Tests with a fake notifier and
  repository under `runTest`: the notice follows title and start, a rest is posted with its end
  and re-posted without it once over, a weight change doesn't re-post, a resume pushes the live
  editor once and not when it is already on top, a skip reaches the covered editor (item 32's
  lookup) and falls back to the repository.
- [ ] **2. The streak reminders.** A class (e.g. `StreakReminders`) taking `SessionRepository`,
  `ProgramRepository`, `SettingsRepository`, `NudgeScheduler` and a clock and time zone, with
  `suspend fun run(texts)`; `nudgeWords` goes with it. Tests: reminder off → an empty plan
  (which cancels); on → the plan from `StreakEngine` with its words; a week already trained →
  empty; a change of texts re-words the plan.
- [ ] **3. What the root shows.** One small state holder that turns the account and the
  onboarding preference into `Loading`, `SignIn`, `Onboarding` or `Main`, replacing the
  `AccountLoading` sentinel and the four early `return`s; the incoming-files rule (push Import
  unless it is on top) next to it or in a two-line helper, whichever reads better. The next
  program day for the "+" menu (`UpNext`) becomes a plain function over the program state, the
  links and the texts, tested on its own. Tests: each state from the flows' values, including
  "not read yet" never showing sign-in.
- [ ] **4. The chrome and the routes out of the file.** `TopBar`, `BottomNav`, `LiveSessionBar`,
  `IconCircle` and `Tab.icon` into `ui/nav/` (e.g. `AppChrome.kt`); `ScreenContent` and
  `ScreenBody`'s `when` over `Screen` into e.g. `ui/nav/Routes.kt`. A pure move: no behaviour,
  wording or look changes, and the screenshot tests unchanged.
- [ ] **5. Finish.** `App.kt` now holds `App` and `AppBody` assembling the theme, the language,
  the gates, the pieces above and the chrome; no repository flow is combined in it and no rule
  is decided in it. Aim for well under 200 lines, but the measure is that every remaining line
  is assembly. `docs/how-it-works.md` "Modules" names the pieces in a sentence (rule 2), and
  `auth-plan.md`'s "Where things are" row for `App.kt` is left alone (it is history).

Every step: the existing desktop tests and item 32's pass unchanged, the screenshot tests too,
and the pull request lists the test plan checks it touched (below, item 33).

Tests: per step, as above.

### 34. ScreenModel actions: one way to launch them and to handle their failures

- [ ] Done

**Milestone:** Maintenance (P1). **Depends on:** nothing. Touches the same model files as
item 35, so don't run the two at once.

`ScreenModel.scope` is `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` with no
exception handler, and the models call `scope.launch` about fifty times. An exception nobody
catches there goes to the platform: on iOS an uncaught Kotlin/Native exception ends the app,
Android crashes, and the desktop prints it. Item 1 fixed the one case found then (sign-out) at
its source, not the pattern. Each model that does catch does it its own way: `SignInModel.run`
and `EmailSignIn` catch the expected sign-in exceptions, rethrow `CancellationException` and
turn anything else into a `failed` flag; `AccountDeletion` in `SettingsScreen.kt` does the same;
`ImportModel.load` and `commit` catch `Exception`, which also swallows `CancellationException`
and writes an error into a model that is being cleared; the rest catch nothing.

1. **Inventory**, written into the pull request: every `scope.launch` under
   `ui/screens/`, sorted into *actions the person started that can fail visibly* (sign-in, link,
   delete account, import, save a program, pick a photo, …), *background writes that should never
   fail in normal use* (persisting an edit, toggling a set), and *collections* (flows into
   state, which should be `stateIn`/`collect` and rarely fail).
2. **One small helper in `ui/ScreenModel.kt`**, e.g. `protected fun launchAction(onFailure:
   (Throwable) -> Unit = {}, block: suspend CoroutineScope.() -> Unit): Job`, which: rethrows
   `CancellationException`; lets the caller map the failures it expects (domain exceptions like
   `SignInCancelledException`, `AuthNotConfiguredException`, `CsvFormatException`) inside
   `block` as today; catches `Exception` (never `Throwable`: an `Error` stays a crash) as
   unexpected, reports it, and calls `onFailure` so the screen can show its failure state. Plus a
   `CoroutineExceptionHandler` on `scope` as the last line for a plain `launch` that escapes: it
   reports and keeps the app alive in a release build.
3. **Reporting**, one place: a small `ErrorReporter` (an interface in `shared` with a default
   that logs through the platform's log: `println`/stderr on the desktop, `NSLog` on iOS,
   `android.util.Log` on Android; no new library) bound in Koin. Tests bind one that records,
   and fails the test when an unexpected exception reaches it, so the handler hides nothing in
   tests.
4. **Move the models onto it**: `SignInModel.run`, `EmailSignIn`, `AccountDeletion` and the
   Settings link flow share the helper (the shared try/catch `auth-plan.md` item 3 asked for);
   `ImportModel` stops swallowing cancellation; each action from step 1 gets a visible failure
   where its screen already has a place for one, and the rest report only. No new UI and no new
   strings unless a screen had no way at all to say an action failed; then one line, in both
   `strings.xml`.
5. `docs/how-it-works.md`: one sentence on how a model's actions fail, next to the
   `ScreenModel` paragraph.

Not an MVI framework, no sealed intent classes, no `Result` wrapping of every repository.

Tests: in `:composeApp:desktopTest`, with `Dispatchers.setMain` and a test reporter: an
action whose repository throws shows the failure state, reports once and doesn't crash;
cancelling a model mid-action (its entry released) reports nothing and writes no state; an
expected domain exception (a cancelled sign-in) is not reported; an `Error` is not caught.
The existing `SignInModelTest`, `EmailSignInTest` and `AccountDeletionTest` keep passing.

### 35. Explicit dependencies instead of `inject()` defaults

- [ ] Done

**Milestone:** Maintenance (P2). **Depends on:** 34 (same files; its helper settles first). Not
a launch blocker, and no change of DI framework: Koin stays.

`ui/ScreenModel.kt` has `inject<T>() = KoinPlatform.getKoin().get(T::class)`, and the
ScreenModels use it as default constructor arguments (`class HomeModel(..., sessions:
SessionRepository = inject())`), so a screen's composable makes its model with only the
arguments that aren't repositories, and a test gets the real Koin graph unless it passes every
dependency by hand. `App.kt` calls `remember { inject<…>() }` eight times, and `SkipRestReceiver`
once. The constructors already list their dependencies; what hides them is the default.

1. Drop the `= inject()` defaults from every ScreenModel constructor. Each screen composable
   passes them where it makes the model (`rememberScreenModel { HomeModel(texts, inject(),
   inject()) }`), so the composable is the one factory boundary and the model knows nothing of
   Koin. If that reads worse than a Koin `factory` per model with `parametersOf(texts, …)`, the
   pull request may choose that instead and say why; either way no model class calls Koin.
2. `inject()` stays for composables only: its KDoc says so, and `KoinPlatform` appears in the
   platform entry points (`GainsApplication`, `MainViewController`, `main.kt`),
   `SkipRestReceiver` (a `BroadcastReceiver` has no constructor of ours; keep it, or make it a
   `KoinComponent`) and the Android `Context` lookups in `platform/Language.kt` and `Motion.kt`,
   and nowhere else. `App.kt`'s own lookups go into the constructors of item 33's pieces, made in
   one place in `App`; if 33 is not done yet, leave `App.kt` to it.
3. Tests that build a model get simpler: they pass fakes or the test database's repositories
   directly (`SignInModelTest`, `SyncUiTest`, `AccountDeletionTest` and any other test that
   builds a model). Don't rewrite tests that already work through Koin unless they touch a
   changed constructor.
4. `docs/how-it-works.md`'s Koin paragraph: Koin wires the graph at the entry points and the
   screens take what they need from it; models are plain classes.

Tests: all existing tests; `grep -rn "= inject()" composeApp/src` returns nothing.

### 36. Architecture docs back in step with the code

- [ ] Done

**Milestone:** Maintenance (P2). **Depends on:** nothing. Items 33–35 and 37 then keep the docs
true in their own pull requests (rule 2).

The docs have drifted from what the code does. Found so far:

- `docs/how-it-works.md`, "Accounts and sync": "Today that is Sign in with Apple and with Google
  on iOS … Android and the desktop still run as guests." All three platforms have Apple and
  Google (items 9, 11, 15, 16), and email and password once the server has a mail account
  (item 18); the first paragraph says "Continue with Google / Apple" only.
- `docs/how-it-works.md`, the modules table: the server is "sign-in with Google or Apple identity
  tokens" (it also runs Apple's web flow and email accounts), and the diagram's server box and
  the table should match `docs/sync.md`'s description of the server.
- `README.md`, "Local first": "Sign in with Apple on iOS syncs it".
- `docs/development.md`, Roadmap "Done so far": only the iOS sign-in; Android and desktop
  sign-in and email accounts are done too (Known limitations already says so).

1. Read `docs/how-it-works.md`, `README.md`, `docs/development.md` and `docs/sync.md` against the
   code (`AuthConfig`, the platform `IdentityProvider`s, `Routes.kt`, `SyncController`, the Koin
   modules, `ScreenModel`/`Navigator`) and fix every statement about platforms, providers,
   modules and the app's structure that is no longer true, including the ones above. Correct
   what is wrong; don't restyle or rewrite what is right.
2. The mermaid diagram in `how-it-works.md`: the sign-in and sync arrows as they are (all three
   platforms, the server's web flow for Apple off iOS), nothing more detailed than it is now.
3. `auth-plan.md` is a finished plan: leave it as history, except a line at the top saying its
   "Where things are" table was true at release 1.5.
4. No new documents.

Tests: none; `docs/` changes skip the iOS job. Links checked by opening the rendered files.

### 37. Module boundaries: a wire-protocol module, and when to split features

- [ ] Done

**Milestone:** Maintenance (P2, after launch). **Depends on:** 27 (both rewrite build files).
Nothing is split unless this item's measurements say the graph gets better.

Three modules today: `shared` (KMP: domain, import, SQLDelight, the sync client and the wire
format), `composeApp` (the UI and the entry points) and `server` (Ktor, JVM). That is the right
size for the project now. One boundary is worth a look: `server` depends on all of `:shared`
(`implementation(project(":shared"))`) to reuse the wire format, and `shared`'s `commonMain`
carries `api` dependencies on Koin, Ktor client and kotlinx-datetime, and its JVM target the
SQLDelight SQLite driver and Ktor CIO client, so the server's classpath holds the app's client
database, DI container and HTTP client. What the server actually imports from it: the request and
response classes in `sync/Protocol.kt`, `SyncJson`, `SyncKinds`, `PhotoDoc` from `Documents.kt`,
`AppleWebFlow`'s constants, and `SyncApi.PAGE` and `SyncApi.HEADER_UPDATED_AT` from the client
class itself.

1. **Measure first:** the server's `runtimeClasspath` (`./gradlew :server:dependencies
   --configuration runtimeClasspath`) and the size of `build/install/gains-server/lib`; and
   whether a change to a client-only file in `shared` (a repository, the SQLDelight schema)
   recompiles and re-tests the server.
2. **Decide, and write the decision here.** If the server carries a real amount of client code
   and rebuilds for client changes: a small `:protocol` KMP module (`commonMain` only,
   kotlinx-serialization and nothing else) holding `Protocol.kt`, `SyncJson`, `SyncKinds`, the
   document shapes the server reads, the Apple web-flow constants and the protocol constants
   moved out of `SyncApi`; `shared` and `server` both depend on it and `server` no longer on
   `shared`. Package names stay, so the move is imports only. If the gain is small, say so and
   stop.
3. **Feature modules: don't create any now.** Write in `docs/development.md` when splitting
   `composeApp` or `shared` into feature modules becomes worth it, so the question is answered
   next time by checking, not by taste: a feature with its own clear owner or reuse outside this
   app; builds where a change in one feature recompiles unrelated ones for a noticeable time;
   a dependency cycle between packages that a module boundary would forbid; tests that need a
   large unrelated graph (the whole database and Koin) to test one feature. One of those, seen
   concretely, is the trigger; screen count alone is not.
4. `docs/how-it-works.md`'s modules table and `docs/sync.md` "The server", if step 2 adds the
   module (rule 2).

Tests: if the module is added, every test set in CI unchanged and green, and the server's
classpath from step 1 measured again in the pull request.

### 38. Android: an app module of its own, then AGP 9 and compileSdk 37

- [ ] Done

**Milestone:** Maintenance (P2). **Depends on:** 25, 26 (CI builds Android and Xcode, which is
how this item is checked), 27 (it left AGP on 8.x for this). Items 30 and 31 edit the Android
application config this item moves; whichever lands second follows it to `androidApp/`.

Item 27 had to stop at AGP 8.13.2. AGP 9 no longer accepts `com.android.application` in a module
that also applies the Kotlin Multiplatform plugin, and `:composeApp` has both. Staying on 8.x
holds back more than AGP. compileSdk 37 needs AGP 9.1.1 or newer (8.13 tops out at API 36.1).
Compose Multiplatform 1.12's Android artifacts (Jetpack Compose 1.12) refuse to build with less
than AGP 9.1 and compileSdk 37, which is why item 27 stopped at 1.11.1. And okhttp stays pinned
at 5.4.0, the last release that builds against API 36. When this was written (September 2026),
AGP 9.4.0 was current: Gradle 9.6.0 or newer, API 37, Android Studio Quail 4 (2026.1.4) or newer.
The AGP 9 opt-outs (`android.newDsl=false`, `android.builtInKotlin=false`) might keep today's
layout building for a while, but they are removed in AGP 10, so they only postpone this item.

**The shape: a thin app module, everything else where it is.** Almost all the Android code in
`composeApp/src/androidMain` uses `internal` declarations of `:composeApp` (`App`, the screen
models, `AndroidIdentityProvider`, `WebSignIn`), and `platform/` holds `actual`s. `internal`
isn't visible from another module, and an `actual` must stay with its `expect`. So the Kotlin, the
manifest and the resources stay in `:composeApp`, which becomes an Android library, and the new
module holds only what must belong to an application.

1. **`:androidApp`** (`com.android.application`, `androidApp/build.gradle.kts` in the Kotlin DSL,
   no Kotlin sources), depending on `:composeApp`. It takes everything application-only from
   `composeApp/android.gradle`:
   - `applicationId "app.gains"`, unchanged, so Play and devices see the same app. `namespace`
     must differ from the library's, which stays `app.gains` (`app.gains.android`, say).
   - `versionCode`, and `versionName` from `MARKETING_VERSION`.
   - The upload signing config, the build types and `packaging`.
   - `lint` with `abortOnError`, plus `checkDependencies true` so the library's code is still
     linted.
   - A minimal `src/main/AndroidManifest.xml`. The library's manifest keeps the `<application>`,
     the activities, the receivers and the permissions. Its relative names (`.MainActivity`)
     resolve against the library's `app.gains` and merge into the app's.

   `settings.gradle.kts` includes `:androidApp` only when `gains.android` is on
   (`providers.gradleProperty`), so `-Pgains.android=false` (the `test` CI job, the Xcode build
   phase, a Mac without an Android SDK) never sees it.
2. **`:composeApp` and `:shared` become KMP libraries** on
   `com.android.kotlin.multiplatform.library`. `androidTarget { }` and the `com.android.*` plugin
   go. The Android target is configured in `kotlin { android { namespace; compileSdk; minSdk } }`
   (`androidLibrary { }` is the older, deprecated name). Keep the conditional wiring:
   - The plugin is applied only when Android is on, and its block lives in each module's Groovy
     `android.gradle`, applied **before** the `kotlin { }` block that names `androidMain`. The
     Kotlin DSL can't mention AGP types when AGP isn't on the classpath. Check that Groovy
     resolves `kotlin { android { } }` to the target. If it doesn't, the target can be found by
     name in `kotlin.targets`.
   - `:composeApp` needs `androidResources { enable = true }`, which is off by default in the
     new plugin: the notification strings, drawables and layout use `R`, and the Compose
     resources ship as Android assets.
   - `:shared` needs `withHostTest { }`, so `commonTest` keeps running on the Android JVM. The
     task becomes `:shared:testAndroidHostTest`, not `testDebugUnitTest`.
   - The JVM target is set on the module's `KotlinJvmCompile` tasks (17, as now).
   - The okhttp constraint in `shared/build.gradle.kts` goes.
3. **`BuildConfig` goes.** The KMP library plugin has no variants and no `BuildConfig`, and
   `androidAuthConfig()` reads four fields from it. They become string resources instead:
   - Empty defaults in `composeApp/src/androidMain/res/values/sign_in_config.xml`
     (`translatable="false"`).
   - `:androidApp` overrides them with `resValue` from the same Gradle properties
     (`gains.serverUrl`, `gains.googleWebClientId`, `gains.appleServicesId`,
     `gains.passwordSignIn`). `buildFeatures { resValues true }` is set explicitly, since AGP 9
     changed the defaults.
   - `androidAuthConfig(context)` reads `R.string.…`. An app resource overrides a library's with
     the same name, so the library code needs no knowledge of the app.
   - `docs/development.md` "Android" and the Play workflow's variables keep their property names.
4. **Versions**, once 1–3 build on AGP 8.13 (the move and the bump in separate commits, so a
   failure says which one broke):
   - AGP 9.4 or the current 9.x, the Gradle wrapper it asks for (9.6 or newer), and a look at
     the build for Gradle 9 removals.
   - `compileSdk 37`. `targetSdk` stays at 36: raising it opts the app into Android 17's
     runtime behaviour, which is its own item, as item 21 was for 36.
   - Compose Multiplatform 1.12.x with the Material 3 version its plugin names.
   - The okhttp pin dropped from the catalog.
5. **Paths:**
   - `.github/workflows/ci.yml`: the `android` job becomes `:androidApp:assembleDebug
     :androidApp:lintDebug :shared:testAndroidHostTest`, and the lint report path moves.
   - `tools/play.py` and `tools/test_play.py`: `ANDROID_GRADLE` becomes the app module's build
     file, `BUNDLE` becomes `androidApp/build/outputs/bundle/release/androidApp-release.aab`,
     and the task is `:androidApp:bundleRelease`.
   - `docs/play.md`, `docs/development.md` (the commands, the Android Studio run configuration,
     the Android Studio version, "Built with"), `README.md`, the checks in `auth-plan.md`, and
     this file's items 30 and 31 where they name `composeApp/android.gradle`.
   - `docs/how-it-works.md`'s modules table gains the fourth module.
6. **Owner:**
   - Android Studio Quail 4 or newer.
   - Once CI is green, one release round to the closed testing track. Play must take the bundle
     as an update: same application id, same upload key, a higher `versionCode`.
   - On a phone that has the previous closed-testing build, the update installs over it with
     the workouts, the sign-in and the settings still there.

Tests: the three CI jobs on the pull request. `:shared`'s Android host tests run the same
`commonTest` count as `testDebugUnitTest` did (compare the two reports). The merged manifest of a
debug build (`androidApp/build/intermediates/merged_manifests/…`) has `package="app.gains"`, the
three receivers, both activities and the App Link filter. The four sign-in values reach the app:
with `-Pgains.serverUrl=…` set, the generated `resValues` carries it. The manual checks are in the
test plan, "Android app module (item 38)".

---

## Contact email: where it is used

**The repository holds no personal email address.** Keep it that way: this table lists
**places**, never the address. When the email changes, work down this table.

| Place | Field | Alias works? | Set to alias |
|---|---|---|---|
| App Store Connect → App Information / App Review Information | Review contact email | Yes | [ ] |
| App Store Connect → TestFlight → Test Information | Feedback email (testers see it) | Yes | [ ] |
| Apple Developer / App Store Connect account | The Apple ID itself | No: change the Apple ID | — |
| Google Auth Platform → Branding | User support email (shown when signing in) | Only a Google account or a **Google Group** you manage | [ ] |
| Google Auth Platform → Branding | Developer contact email | Yes | [ ] |
| Google Cloud / Search Console | Owner account | No: it's the Google account | — |
| Play Console → store listing | Contact email (public, required) | Yes | [ ] |
| Play Console | Developer account | No: it's the Google account | — |
| `gains.gerra.sh/privacy` and `/support` (item 3) | Contact line, in `site/` (`gains@gerra.sh`, also the guest list) | Yes | [x] |
| Server, certbot | Let's Encrypt notices (`certbot update_account --email …`) | Yes | [ ] |
| Email provider (item 18) | Sender and account | Yes | [ ] |

For the Google user support email, a Google Group whose only member is you can change members
later without touching the consent screen.

Changing the email on the **same** Google or Apple account doesn't affect sign-in: Gains keys
accounts on the provider's subject, not the email. Moving to a **different** Google account
creates a new Gains user.

---

## Test plan

Manual checks on a real device. Release **1.5** on TestFlight has everything up to
`auth-plan.md` item 7. Tick a box when it passes on the build named in its section. When a
build fails a check, open an issue and link it next to the box.

### Before you start

- [ ] Your Google account is a test user (until item 5).
- [ ] Server smoke test: `curl https://api.gains.gerra.sh/health` → `{"status":"ok"}`.
- [ ] `POST /auth/apple` and `POST /auth/google` with body `{"token":"x"}` → **401**. A **503**
      means that provider's ids are missing from `secrets/.env`.
- [ ] Two devices, or one device plus a second install, for the sync checks.

### iOS sign-in (release 1.5)

**Sign in with Apple** (auth-plan 1)
- [ ] On a fresh install, the buttons read "Sign in with Apple" (black in light mode, white in
      dark) and "Sign in with Google".
- [ ] Apple sheet → Continue → you're in the app, and Settings shows your name and email.
- [ ] With "Hide My Email", the relay address shows.
- [ ] Closing the sheet shows no error and leaves you on the sign-in screen.
- [ ] Sign out and sign in again: the name is kept, although Apple sends it only the first
      time.

**Sign in with Google** (auth-plan 2)
- [ ] "Gains wants to use google.com" → Google's account chooser → back in the app, signed in
      with your name and email.
- [ ] Cancelling at the prompt, and inside the sheet, is silent.
- [ ] A Google account that is not a test user gets Google's "access blocked" page, then the
      app's "sign-in failed" line. (After item 5, it signs in.)
- [ ] Apple, with your real email (not a relay), and Google with the same email are one
      account: data from device A shows up on device B.

**Link from a guest account** (auth-plan 3)
- [ ] As a guest with some workouts, Settings shows Link Apple / Link Google and the "will be
      uploaded and merged" line.
- [ ] Linking turns the card into the account without navigating, shows "Syncing…" then
      "Synced", and the workouts appear on the second device.
- [ ] Cancelling leaves you a guest with everything in place.
- [ ] Linking into an account that already has workouts ends with both sets.

**Delete account** (auth-plan 4)
- [ ] The dialog wording is right, and Cancel does nothing.
- [ ] In airplane mode, Delete shows an error line and you stay signed in.
- [ ] Online, Delete goes back to the sign-in screen, and the local workouts are still there as
      a guest.
- [ ] Signing in again gives a fresh account, and everything on the device uploads.
- [ ] The other device signed into the deleted account shows "Signed out on the server".

**Sync status and "Sync now"** (auth-plan 5)
- [ ] Log a set: "· 1 change waiting", then "Synced just now".
- [ ] In airplane mode, "Sync now" → "Couldn't sync". Back online, "Sync now" recovers.
- [ ] Kill and relaunch: "Synced X min ago" is still there.
- [ ] Edit on device B, then bring device A to the foreground: the change arrives without a
      tap.
- [ ] A 401 (delete the account from the other device, or rotate `JWT_SECRET`, which signs every
      device out) shows "Signed out on the server. Sign in again." with buttons, and signing in
      clears it.
- [ ] In Russian, 1, 2, 5 and 21 changes read correctly.
- [ ] "Sync now" is disabled while a sync runs.

**Keychain** (auth-plan 6)
- [ ] Sign in, delete the app, reinstall: the sign-in screen shows, and signing in works.
- [ ] Optional: set the device clock 8 days ahead and bring the app to the foreground. The
      token refreshes and the sync still succeeds.

**Privacy texts** (auth-plan 7)
- [ ] The Settings data note reads right in English and Russian.
- [ ] The upload of 1.5 brought no privacy-manifest (ITMS-91xxx) email.
- [ ] The App Store Connect privacy answers match `PrivacyInfo.xcprivacy`.

### Launch items

**1. Sign-out**
- [ ] Sign out from Settings works, and so does signing back in.

**2. Verified-email merging**
- [ ] Apple and Google with the same verified email are still one account (repeat the check
      above).

**3. Site**
- [ ] `http://gains.gerra.sh` → `https://gains.gerra.sh`, with no redirect to the API and no
      certificate warning.
- [ ] `/privacy` and `/support` load, and the store and TestFlight links work.
- [ ] `api.gains.gerra.sh` still works.
- [ ] Leaving an email in the guest list form says "You're on the list", and the address is in
      `guest_list` on the box (the `sqlite3` line under item 3). A second try with the same
      address says the same and adds no row; `nope` is refused.
- [ ] The pages look right on a phone, in dark and light.
- [ ] A missing page (`/nope`) shows the site's own "Nothing here" page.

**5. Google in production**
- [ ] An account that was never a test user signs in with Google.
- [ ] The consent screen shows the app name, logo and links.

**6. Apple revocation**
- [ ] Sign in with Apple on a build containing item 6, then Delete account. Afterwards, iPhone
      Settings → Apple Account → Sign in with Apple no longer lists Gains.
- [ ] Deleting while Apple is unreachable still deletes the account (server log shows the
      failed revoke).

**10. Apple web sign-in** (once `APPLE_SERVICES_ID` is deployed)
- [ ] `journalctl -u gains-server` shows `apple web on`.
- [ ] Open `https://api.gains.gerra.sh/auth/apple/start?redirect=http://127.0.0.1:1234/&state=x`
      in a desktop browser: Apple's page asks to sign in to Gains. Finishing it lands on
      `http://127.0.0.1:1234/?code=…&state=x` (the page itself fails to load, which is fine).
- [ ] Within a minute, `curl -X POST https://api.gains.gerra.sh/auth/exchange -H 'Content-Type: application/json' -d '{"code":"<code>"}'`
      answers `{token, user}` with your iPhone account's user id. The same call again → 401.
- [ ] Cancelling on Apple's page lands on `…?error=cancelled&state=x`.
- [ ] `redirect=https://example.com/` → 400.

**20. Deleted-account tokens**
- [ ] Take a token (the `POST /auth/exchange` answer in the item 10 check works), then Delete
      account from a device signed into that account. `curl -H 'Authorization: Bearer <token>'
      https://api.gains.gerra.sh/auth/me` → **401**, and so do `GET /sync/pull?since=0`,
      `POST /sync/push` with `{"documents":[]}` and `PUT /sync/blobs/session_photo/x`.
- [ ] The other device signed into the deleted account shows "Signed out on the server" on its
      very next sync (the "Delete account" check above), not only at its weekly refresh.

**22–24. Server hardening**
- [ ] Item 22, after the deploy: `systemctl show gains-server -p User` says `gains-server`;
      `/health` answers; a sign-in, a sync and a photo upload work from a device;
      `ls -l /var/lib/gains` shows `gains-server` owning the files, mode 600; `journalctl -u
      gains-server` has no permission error; `systemd-analyze security gains-server` scores
      better than before the item.
- [ ] Item 23: twenty quick `POST /auth/google` with `{"token":"x"}` end in **429** after the
      eleventh, and twenty to `api.gains.gerra.sh/guest-list` after the sixth. A first sync of a long
      history with photos still completes, and a throttled device shows "Couldn't sync", not
      "Signed out on the server".
- [ ] Item 24: `sqlite3 /var/lib/gains/gains-server.db 'SELECT refresh_token FROM identity'`
      shows `v1:` ciphertext after an Apple sign-in, and Delete account still removes Gains from
      the Apple ID (the item 6 check).

### Android (items 9–13)

- [ ] Install from the Play closed test track.
- [ ] Item 13: with the Play secrets unset, a Release run's *Google Play* job is green and its
      summary says *Play upload skipped* naming the secrets; TestFlight still ships.
- [ ] Item 13: with them set, the same run's summary says *Uploaded Gains <version> (<run
      number>)*, and Play Console → Closed testing lists that version code, with the version
      name equal to the TestFlight version of the same run. The track's opt-in link installs it.
- [ ] Item 13: the installed app's Google and Apple buttons show (the two repository variables
      reached the build), and signing in works.
- [ ] Google: the account chooser → signed in. Cancelling is silent.
- [ ] Item 9: with `gains.googleWebClientId` empty, only the guest button shows and the app
      still works as a guest.
- [ ] Item 9: with it set, "Sign in with Google" opens Play services' account chooser listing
      every Google account on the phone; picking one lands in the app signed in with your name
      and email. Settings then shows the account card.
- [ ] Item 9: swiping the chooser away shows no error, and the buttons come back.
- [ ] Item 9: a phone with no Google account shows the "sign-in failed" line rather than a crash.
- [ ] Item 9: log a set on the iPhone, then bring the Android app back to the front: the set
      arrives without "Sync now".
- [ ] Apple: the Custom Tab → Apple → back in the app, signed in. Backing out is silent.
- [ ] Item 11: with `gains.appleServicesId` empty, the Apple button is hidden; with it set, it
      shows. `https://gains.gerra.sh/.well-known/assetlinks.json` loads as JSON, and
      `adb shell pm get-app-links app.gains` says `verified` for `gains.gerra.sh`.
- [ ] Item 11: "Sign in with Apple" opens Apple's page in a Custom Tab; finishing it returns to
      the app with no second copy of it (the back button leaves the app rather than showing the
      tab again), signed in with your name and email. Settings then shows the account card.
- [ ] Item 11: backing out of the Custom Tab, and "Cancel" on Apple's page, both show no error,
      and the buttons come back.
- [ ] Item 11: on a debug build (its key not in `assetlinks.json`), finishing lands on the site's
      "Almost there" page, and "Open Gains" returns to the app signed in.
- [ ] Item 11: the same Apple ID on the iPhone and on Android is one account (the workouts sync).
- [ ] The whole "iOS sign-in" section above, on Android: linking, delete, sync status, 401.
- [ ] Sync between an iPhone and an Android phone on one account, both ways, including a
      photo.
- [ ] Reinstall: no stale token; signing in works.
- [ ] Item 12: once signed in, `adb shell run-as app.gains cat shared_prefs/app.gains.sync.xml`
      shows a Base64 blob, not the token, and `sync_state` in `databases/gains.db` has no
      `token` row. Force-stop and reopen: still signed in, and "Sync now" works.
- [ ] Item 12: `adb shell bmgr backupnow app.gains`, uninstall, reinstall and let the backup
      restore: the workouts are back, and the account card says "Signed out on the server"
      until you sign in again. No crash.

### Android 16, R8 and backup (items 21, 30, 31)

On an Android 16 device or emulator, with a build containing item 21.

- [ ] Item 21: the app draws edge to edge with nothing hidden under the status or navigation
      bars, the keyboard doesn't cover the field being typed in, and the back gesture previews
      and lands where it should, including from the Sign in with Apple tab back into the app.
- [ ] Item 21: the status bar's clock and icons read in both themes: dark on Settings → Theme →
      Light, light on Dark, and they switch at once when the theme is changed.
- [ ] Item 21: the workout notification shows, follows the sets, and "Skip rest" works; a streak
      reminder fires at its time, and again after a reboot.
- [ ] Item 21: "Open with" for a CSV from Files and the share sheet open the import preview; the
      App Link and both sign-in providers work (the item 11 checks); `bmgr backupnow`, uninstall,
      reinstall restores (the item 12 check).
- [ ] Item 21: Play Console → App bundle explorer shows target SDK 36 for the uploaded bundle.
- [ ] Item 30: the release build from the closed testing track passes the Android section above
      end to end, with no crash on sign-in, sync, import or the notification (a missing keep
      rule shows there first).
- [ ] Item 31: after `bmgr backupnow`, uninstall and reinstall, what comes back matches the
      decision written under item 31, and the privacy policy's backup sentence matches it too.

### Architecture (items 32–37)

No new screens; these catch a refactor that changed behaviour. On one phone and the desktop, with
a build containing the step.

- [ ] Item 33 (each step): a fresh install shows the sign-in screen with no flash of the app
      first, then onboarding, then Home; an existing install opens straight on Home.
- [ ] Item 33, steps 1 and 4: start a workout, go to another tab: the workout bar shows and
      "Resume" opens it; on a phone the notice follows the sets, a rest shows its countdown,
      "Skip rest" ends it from the tray both with the editor open and with it covered, and a tap
      on the notice opens the workout.
- [ ] Item 33, step 2: with the streak reminder on, a reminder is scheduled in the chosen
      language, and switching the language re-words it; switching it off cancels it.
- [ ] Item 33, step 3: "Open with" / share a CSV opens the import preview once; the "+" menu
      offers the program's next day.
- [ ] Item 33: open Settings from a scrolled History, switch the language, go back: History is
      where it was left, and the tabs keep their screens.
- [ ] Item 34: in airplane mode, a sign-in, a link from Settings and Delete account each show
      their error line and the app stays up; a broken CSV shows the import error, not a crash.

### Desktop (items 15–17)

- [ ] Google and Apple open the browser and come back signed in. Closing the tab times out
      quietly.
- [ ] Item 16: with `gains.appleServicesId` set, "Sign in with Apple" opens Apple's page through
      `api.gains.gerra.sh/auth/apple/start`; finishing it shows the "Back to Gains" tab and the
      app is signed in with your name and email. The same Apple ID as on the iPhone is the same
      account: its workouts arrive.
- [ ] Item 16: "Cancel" on Apple's page brings the tab back and the app shows no error.
- [ ] Item 16: without `gains.appleServicesId` the Apple button is not there.
- [ ] A workout logged on the phone appears on the desktop, and the other way round.
- [ ] Item 17: once signed in, `sqlite3 ~/.gains/gains.db "SELECT key FROM sync_state"` lists no
      `token` row. On macOS, Keychain Access shows a `Gains` item with account `token`, and
      `security find-generic-password -s app.gains.sync -a token -w` prints the token without a
      prompt. On Linux, `secret-tool lookup service app.gains.sync account token` prints it. On
      Windows, `~/.gains/token.dpapi` holds a Base64 blob, not the token.
- [ ] Item 17: quit and reopen: still signed in, and "Sync now" works. Sign out: the item above
      is gone.
- [ ] Item 17: a build from before this item that is signed in (the `token` row is in
      `sync_state`) opens with this build still signed in, and the row is gone.
- [ ] Item 17: delete `~/.gains/gains.db` and open the app: the sign-in screen shows, and the
      keyring item is gone afterwards.
- [ ] Item 17: on Linux with `secret-tool` missing (or `DBUS_SESSION_BUS_ADDRESS` unset), signing
      in still works and the `token` row is in `sync_state`.

### Email and password (18), passkeys (19)

- [ ] Item 18: with `SMTP_*` deployed, `journalctl -u gains-server` shows `email on`, and with
      the switch set the welcome screen shows "Sign in with email" under the provider buttons,
      and a guest's Settings card "Continue with email".
- [ ] Item 18: "New here? Create an account" → address and password → "Check your inbox";
      the mail arrives from `MAIL_FROM` within a minute; its link opens
      `https://gains.gerra.sh/verify?token=…`, "Confirm my email" says done, and the same link
      again says expired. Signing in before confirming says "Confirm your address first".
- [ ] Item 18: sign in with the address and password: the account card shows the address, and
      a workout logged on another device arrives. Sign out and in again works.
- [ ] Item 18: a wrong password says "Wrong email or password"; ten wrong tries in a row say
      "Too many tries", and so does the right password until a quarter of an hour has passed.
- [ ] Item 18: "Forgot password?" → "If that address has an account…" whether or not it has
      one; the mail's link opens `/reset?token=…`, a 5-character password is refused there, an
      8-character one is set, the old one no longer signs in, the new one does, and the link
      used again says expired.
- [ ] Item 18: sign up with the same address as your Google account (verified): after
      confirming, signing in with the password lands in the same account (its workouts show).
      Before confirming, it is no account at all: `sqlite3 …/gains-server.db 'SELECT email,
      user_id FROM password_credential'` shows `user_id` empty.
- [ ] Item 18: delete the account from an email sign-in: the row above is gone, the password
      no longer signs in, and signing up again with the address works.
- [ ] Item 18: with the switch off on a build, no email button anywhere; with `SMTP_*` unset on
      the server but the switch on, the form says "Email sign-in is not configured yet".
- [ ] An unverified password account with the same email as a Google account does **not**
      join it.
- [ ] Create a passkey, then sign in with it on the same device, and on a second device through
      iCloud Keychain or Google Password Manager.

### Dependency pass (item 27)

On the first TestFlight build and Play bundle after item 27, on a device each:

- [ ] Sign in, log a set, and see it arrive on the other device.
- [ ] Attach a photo to a workout; it shows on the other device.
- [ ] Import `samples/liftoff-export.csv`: the preview and the imported workouts look right.
- [ ] Click through every tab, a date and a time picker, a dropdown and a bottom sheet in dark
      and light: nothing looks different from the build before, apart from Compose's own polish.
- [ ] Android: Sign in with Google (Credential Manager) and with Apple (the Custom Tab) both
      still finish.

### Dependabot (item 29)

On the first Monday after item 29 is on `main`:

- [ ] Insights → Dependency graph → Dependabot lists both `github-actions` and `gradle` as
      checked, with no error on either.
- [ ] Its pull requests come grouped (one for the actions, at most one per Gradle group), and
      none proposes a Kotlin or Compose minor, a Koin or AGP major, or okhttp 5.5.0 or newer.
- [ ] CI runs on them and goes green, or goes red for a reason in the bump itself.

### Android app module (item 38)

On the first closed-testing build from `:androidApp`:

- [ ] Play Console takes the bundle as an update of `app.gains`, with no new-app or key warnings.
- [ ] On a phone with the previous closed-testing build, the update installs over it: workouts,
      sign-in and settings are still there.
- [ ] Sign in with Google and with Apple (the App Link comes back to the app), sync a photo,
      import a CSV through "Open with".
- [ ] The workout notification (with "Skip rest"), a streak reminder, and the reminders after a
      reboot.
- [ ] The launcher icon and name, and the app in Russian (Settings → Language).

---

## After launch

Not needed for going public; kept here so nothing lives elsewhere.

- [ ] More connectors: a `ColumnSpec` and a `match` function each, contributions welcome.
- [ ] Undo for sync overwrites (a `document_version` table, see `docs/sync.md`).
- [ ] Sign out everywhere: a `token_version` column on `user`, carried as a claim and compared on
      item 20's lookup. Item 20 decided that the existence check is enough for launch.
