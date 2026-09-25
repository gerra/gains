# Secrets

`secrets/.env` is never in git and never in the CI rsync: it reaches the server only through
`python3 tools/deploy_server.py secrets`, which copies it to `/root/Projects/gains-server/secrets/.env` and
restarts the unit. The server reads it from its working directory at start
([`Config.kt`](../server/src/main/kotlin/app/gains/server/Config.kt)); a variable set in the
environment wins over the file.

| Var | Kind | How to get / notes |
|---|---|---|
| `JWT_SECRET` | random | `python3 -c "import secrets; print(secrets.token_hex(32))"` — signs the tokens the server issues to devices. At least 32 characters; rotating it signs every device out. |
| `GOOGLE_CLIENT_IDS` | static | Comma-separated OAuth client ids whose ID tokens are accepted. Google Cloud console → APIs & Services → Credentials: one **Web application** client (its id is what Android's Credential Manager asks for as `serverClientId`, and the audience of the tokens it returns) and one **iOS** client, `95741411455-2fouqgkjlnault5cvd8lc17n4jekg7ea.apps.googleusercontent.com` (also `GOOGLE_IOS_CLIENT_ID` in `iosApp/Configuration/Config.xcconfig`): the iOS app signs in through it with OAuth and PKCE (`GoogleOAuth.kt`), so it is the audience of every Google token an iPhone sends. And one **Desktop app** client, the desktop's `gains.googleDesktopClientId` (docs/development.md, "Desktop"), the audience of the tokens the desktop app sends. Empty disables Google sign-in (the route answers 503). |
| `APPLE_CLIENT_IDS` | static | Comma-separated audiences of Apple identity tokens: the iOS bundle id `app.gains.Gains` for Sign in with Apple on the phone, The Services ID doesn't need to be listed here: `APPLE_SERVICES_ID` adds it. Empty (and no Services ID) disables Apple sign-in. |
| `APPLE_SERVICES_ID` | static | The **Services ID** for Sign in with Apple on Android and the desktop, e.g. `app.gains.Gains.web`: developer.apple.com → Identifiers → Services IDs → a new one with Sign in with Apple enabled, primary App ID `app.gains.Gains`, domain `api.gains.gerra.sh`, return URL `https://api.gains.gerra.sh/auth/apple/callback`. It is the web flow's client id and is accepted as an identity token audience. Empty turns the web flow off (`/auth/apple/start` answers 503; `journalctl -u gains-server` shows `apple web off`). |
| `APPLE_KEY_ID` | static | developer.apple.com → Certificates, Identifiers & Profiles → Keys → a key with **Sign in with Apple** enabled, configured for the primary App ID `app.gains.Gains`. This is its 10-character Key ID. With the next two, it lets the server exchange the code an Apple sign-in carries for a refresh token and revoke that token when the account is deleted, which Apple asks for. All three or none: without them sign-in still works, but deleting an account can't remove Gains from the person's Apple ID. |
| `APPLE_TEAM_ID` | static | `V5Y8M5GKZ6`, the team that owns the key (top right of the developer account). |
| `APPLE_PRIVATE_KEY` | static | The contents of that key's `AuthKey_<key id>.p8`, on one line with each newline written as `\n`: `APPLE_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\nMIGT…\n-----END PRIVATE KEY-----"`. Apple lets you download the `.p8` only once; keep it somewhere safe besides this file. A key that doesn't parse, or only some of the three set, stops the server at start: after `deploy_server.py secrets` (which restarts without a smoke test), check that `https://api.gains.gerra.sh/health` answers and that `journalctl -u gains-server` shows `apple revoke on`. |
| `GAINS_PUBLIC_URL` | optional | `https://api.gains.gerra.sh`, where the internet reaches the server. Only the Apple web flow uses it, for its return URL, which must match the one registered on the Services ID. |
| `GAINS_DATA_DIR` | env-specific | Where `gains-server.db` lives. `/var/lib/gains` in prod (created on first start), `./data` in dev. |
| `PORT` | optional | `5003`; nginx proxies `api.gains.gerra.sh` to it (`deploy/nginx/api.gains.gerra.sh.conf`). |

Nothing else is secret.
