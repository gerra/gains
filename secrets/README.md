# Secrets

`secrets/.env` is never in git and never in the CI rsync: it reaches the server only through
`python3 tools/deploy_server.py secrets`, which copies it to `/root/Projects/gains-server/secrets/.env` and
restarts the unit. The server reads it from its working directory at start
([`Config.kt`](../server/src/main/kotlin/app/gains/server/Config.kt)); a variable set in the
environment wins over the file.

| Var | Kind | How to get / notes |
|---|---|---|
| `JWT_SECRET` | random | `python3 -c "import secrets; print(secrets.token_hex(32))"` — signs the tokens the server issues to devices. At least 32 characters; rotating it signs every device out. |
| `GOOGLE_CLIENT_IDS` | static | Comma-separated OAuth client ids whose ID tokens are accepted. Google Cloud console → APIs & Services → Credentials: one **Web application** client (its id is what Android's Credential Manager asks for as `serverClientId`, and the audience of the tokens it returns) and one **iOS** client, `95741411455-2fouqgkjlnault5cvd8lc17n4jekg7ea.apps.googleusercontent.com` (also `GOOGLE_IOS_CLIENT_ID` in `iosApp/Configuration/Config.xcconfig`): the iOS app signs in through it with OAuth and PKCE (`GoogleOAuth.kt`), so it is the audience of every Google token an iPhone sends. Empty disables Google sign-in (the route answers 503). |
| `APPLE_CLIENT_IDS` | static | Comma-separated audiences of Apple identity tokens: the iOS bundle id `app.gains.Gains` for Sign in with Apple on the phone, plus the **Services ID** (developer.apple.com → Identifiers → Services IDs) when the web flow is offered on Android or the desktop. Empty disables Apple sign-in. |
| `APPLE_KEY_ID` | static | developer.apple.com → Certificates, Identifiers & Profiles → Keys → a key with **Sign in with Apple** enabled, configured for the primary App ID `app.gains.Gains`. This is its 10-character Key ID. With the next two, it lets the server exchange the code an Apple sign-in carries for a refresh token and revoke that token when the account is deleted, which Apple asks for. All three or none: without them sign-in still works, but deleting an account can't remove Gains from the person's Apple ID. |
| `APPLE_TEAM_ID` | static | `V5Y8M5GKZ6`, the team that owns the key (top right of the developer account). |
| `APPLE_PRIVATE_KEY` | static | The contents of that key's `AuthKey_<key id>.p8`, on one line with each newline written as `\n`: `APPLE_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\nMIGT…\n-----END PRIVATE KEY-----"`. Apple lets you download the `.p8` only once; keep it somewhere safe besides this file. A key that doesn't parse, or only some of the three set, stops the server at start: after `deploy_server.py secrets` (which restarts without a smoke test), check that `https://api.gains.gerra.sh/health` answers and that `journalctl -u gains-server` shows `apple revoke on`. |
| `GAINS_DATA_DIR` | env-specific | Where `gains-server.db` lives. `/var/lib/gains` in prod (created on first start), `./data` in dev. |
| `PORT` | optional | `5003`; nginx proxies `api.gains.gerra.sh` to it (`deploy/nginx/api.gains.gerra.sh.conf`). |

Nothing else is secret.
