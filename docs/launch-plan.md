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
   its box in the same pull request and add `Done in #<pr>` under it.
2. Run the checks from [`auth-plan.md`](auth-plan.md#checks-every-item-must-pass) before
   pushing. They still apply: both `strings.xml` files, KDoc that says why, Koin for wiring,
   `docs/sync.md` kept true.
3. **Android is not compiled in CI** (no Android SDK on the runner; see
   [Known limitations](development.md#known-limitations)). Keep Android work small and
   list every Android file you touched in the pull request body so the owner can build it in
   Android Studio.
4. Steps marked **Owner** are console or account work. Agents assume they are done or in
   progress and don't block on them.
5. Manual checks go in the [test plan](#test-plan), under the item's number. An item that
   changes what a person sees adds its checks there.

## At a glance

| # | Item | Milestone | Who | Depends on | Done |
|---|------|-----------|-----|------------|------|
| 1 | Sign-out can't crash the app; fix the secrets deploy line | iOS launch | Agent | — | [x] |
| 2 | Merge accounts only on verified emails | iOS launch | Agent | — | [ ] |
| 3 | Landing page and privacy policy on `gains.gerra.sh` | iOS launch | Agent + Owner | — | [ ] |
| 4 | Contact email alias | iOS launch | Owner | — | [ ] |
| 5 | Google sign-in in production | iOS launch | Owner | 3, 4 | [ ] |
| 6 | Revoke the Apple token when an account is deleted | iOS launch | Agent + Owner | — | [ ] |
| 7 | Submit iOS for App Review | iOS launch | Owner | 1–6, test plan | [ ] |
| 8 | Android package name and Play Console app | Android launch | Owner + Agent | — | [ ] |
| 9 | Android: Sign in with Google | Android launch | Agent + Owner | 8 | [ ] |
| 10 | Server: Apple web sign-in (Services ID) | Android launch | Agent + Owner | — | [ ] |
| 11 | Android: Sign in with Apple | Android launch | Agent | 8, 10 | [ ] |
| 12 | Android: keep the token in the Keystore | Android launch | Agent | — | [ ] |
| 13 | Android: release workflow and Play closed testing | Android launch | Agent + Owner | 8, 9 | [ ] |
| 14 | Publish on Google Play | Android launch | Owner | 9–13, test plan | [ ] |
| 15 | Desktop: Sign in with Google | Desktop (P1) | Agent + Owner | — | [ ] |
| 16 | Desktop: Sign in with Apple | Desktop (P1) | Agent | 10 | [ ] |
| 17 | Desktop: keep the token in the OS keychain | Desktop (P1) | Agent | — | [ ] |
| 18 | Email and password accounts | More sign-in | Agent + Owner | 2, 4 | [ ] |
| 19 | Passkeys | More sign-in | Agent + Owner | 3, 18 | [ ] |

## Owner actions

Carried over from `auth-plan.md` and still open, or needed by the items below:

- [x] Server `secrets/.env`: `GOOGLE_CLIENT_IDS`, `APPLE_CLIENT_IDS`, `JWT_SECRET`, deployed.
- [x] App Store Connect: App Privacy answers.
- [ ] App Store Connect: privacy policy URL set to `https://gains.gerra.sh/privacy` once item 3
      is live.
- [ ] Google Auth Platform in production (item 5). Until then it stays in **Testing**, and
      only accounts under Audience → Test users can sign in with Google.
- [ ] Contact email alias (item 4), then the [contact email table](#contact-email-where-it-is-used).

---

## Work items

### 1. Sign-out can't crash the app; fix the secrets deploy line

- [x] Done

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

- [ ] Done

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
     *For now:* a `mailto:` link to the contact alias (subject "Gains guest list"), so nothing
     is stored on our server; `/privacy#guest-list` covers it. Swapping in a hosted form is a
     one-line change in `site/index.html` plus that privacy paragraph.
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

- [ ] Done

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

**Milestone:** iOS launch. **Who:** Owner. **Depends on:** 1–6, and the test plan's iOS
sections passed on a TestFlight build containing them.

1. App Store Connect: privacy policy URL, support URL (`/support`), screenshots, description,
   and the review notes. Reviewers can use Sign in with Apple, so no demo account is needed.
   Mention that Delete account is in Settings.
2. Submit. When it is approved, publish the store link on the landing page.

### 8. Android package name and Play Console app

- [ ] Done

**Milestone:** Android launch. **Depends on:** nothing.

The application id is `app.gains` today (`composeApp/android.gradle`), which may already be
taken on Play. **Choose before the first upload: Play never lets you change it.**

1. **Owner:** pick the id. `sh.gerra.gains` follows the domain you own. Check that no Play
   listing uses it.
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

- [ ] Done

**Milestone:** Android launch. **Depends on:** 8.

1. **Owner:** Google Cloud, in the same project as the iOS client:
   - an **Android** OAuth client with the new package name and the SHA-1 of **both** the Play
     App Signing key and the upload key (debug keystore too, for local runs);
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

- [ ] Done

**Milestone:** Android launch. **Depends on:** nothing. Also used by item 16.

Android and the desktop have no native Apple sheet. They use Apple's web flow, which only
redirects to an HTTPS URL registered with Apple, so the server has to receive the redirect.

1. **Owner:** developer.apple.com → Identifiers → a **Services ID** (e.g.
   `app.gains.Gains.web`) with Sign in with Apple enabled, primary App ID `app.gains.Gains`,
   domain `api.gains.gerra.sh`, return URL `https://api.gains.gerra.sh/auth/apple/callback`.
   Add the Services ID to `APPLE_CLIENT_IDS`.
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

- [ ] Done

**Milestone:** Android launch. **Depends on:** 8, 10.

1. `AccountKind.APPLE` in `AndroidIdentityProvider`: open
   `/auth/apple/start` in a Custom Tab with an App Link (`https://gains.gerra.sh/auth/done`) or a
   custom scheme as the callback. Receive it in `MainActivity`, check the state, and call
   `/auth/exchange`.
2. This flow ends with our token, not an identity token, so `AccountRepository` needs a second
   entry point that takes a `SignInResponse` directly. Keep one code path for storing the token
   and starting the feed.
3. Cancelling (back out of the Custom Tab) must behave like `SignInCancelledException`: no
   error, and the buttons come back.

### 12. Android: keep the token in the Keystore

- [ ] Done

**Milestone:** Android launch. **Depends on:** nothing.

A `KeystoreTokenVault` in `androidMain`: an AES-GCM key in the Android Keystore encrypts the
token, and the ciphertext lives in private `SharedPreferences`. No new library
(`security-crypto` is deprecated). Register it in the Android Koin module like iOS does with
`KeychainTokenVault`. The migration from the `sync_state` row already happens in `SyncStore`.
Update `docs/sync.md` "What the client does".

### 13. Android: release workflow and Play closed testing

- [ ] Done

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

**Milestone:** Android launch. **Who:** Owner. **Depends on:** 9–13, the 14-day closed test,
and the test plan's Android section.

Apply for production access, then promote the build. Add the store link to the landing page.

### 15. Desktop: Sign in with Google

- [ ] Done

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

- [ ] Done

**Milestone:** Desktop (P1). **Depends on:** 10.

The same loopback listener as item 15, with `/auth/apple/start?redirect=http://127.0.0.1:<port>`
and then `/auth/exchange`. Use the same `AccountRepository` entry point as item 11.

### 17. Desktop: keep the token in the OS keychain

- [ ] Done

**Milestone:** Desktop (P1). **Depends on:** nothing.

**Owner decides:** a small library (e.g. `java-keyring`, which covers macOS Keychain, Windows
Credential Manager and libsecret) or calling the OS tools directly (`security` on macOS,
`secret-tool` on Linux, DPAPI through JNA on Windows). Either way, add a `TokenVault` in
`desktopMain` and fall back to `SqliteTokenVault` when no keychain is available (a headless
Linux box).

### 18. Email and password accounts

- [ ] Done

**Milestone:** More sign-in. **Depends on:** 2 (verified-only merging), 4.

1. **Owner decides:** the email provider for verification and reset mail (e.g. Postmark,
   Amazon SES, or SMTP through the alias's host). Its credentials go in `secrets/.env`.
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
- [ ] The guest list button opens a mail to the alias with the subject "Gains guest list", and
      it arrives in your inbox (needs item 4).
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

### Android (items 9–13)

- [ ] Install from the Play closed test track.
- [ ] Google: the account chooser → signed in. Cancelling is silent.
- [ ] Apple: the Custom Tab → Apple → back in the app, signed in. Backing out is silent.
- [ ] The whole "iOS sign-in" section above, on Android: linking, delete, sync status, 401.
- [ ] Sync between an iPhone and an Android phone on one account, both ways, including a
      photo.
- [ ] Reinstall: no stale token; signing in works.

### Desktop (items 15–17)

- [ ] Google and Apple open the browser and come back signed in. Closing the tab times out
      quietly.
- [ ] A workout logged on the phone appears on the desktop, and the other way round.
- [ ] The token is in the OS keychain, not in the database file.

### Email and password (18), passkeys (19)

- [ ] Sign up → verification email → verified → signed in. Reset works. A wrong password
      is refused, and repeated tries are rate limited.
- [ ] An unverified password account with the same email as a Google account does **not**
      join it.
- [ ] Create a passkey, then sign in with it on the same device, and on a second device through
      iCloud Keychain or Google Password Manager.

---

## After launch

Not needed for going public; kept here so nothing lives elsewhere.

- [ ] More connectors: a `ColumnSpec` and a `match` function each, contributions welcome.
- [ ] Undo for sync overwrites (a `document_version` table, see `docs/sync.md`).
