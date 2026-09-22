# Secrets

`secrets/.env` is never in git and never in the CI rsync: it reaches the server only through
`python3 tools/deploy_server.py secrets`, which copies it to `/root/Projects/gains-server/secrets/.env` and
restarts the unit. The server reads it from its working directory at start
([`Config.kt`](../server/src/main/kotlin/app/gains/server/Config.kt)); a variable set in the
environment wins over the file.

| Var | Kind | How to get / notes |
|---|---|---|
| `JWT_SECRET` | random | `python3 -c "import secrets; print(secrets.token_hex(32))"` — signs the tokens the server issues to devices. At least 32 characters; rotating it signs every device out. |
| `GOOGLE_CLIENT_IDS` | static | Comma-separated OAuth client ids whose ID tokens are accepted. Google Cloud console → APIs & Services → Credentials: one **Web application** client (its id is what Android's Credential Manager asks for as `serverClientId`, and the audience of the tokens it returns) and one **iOS** client for the Google Sign-In SDK. Empty disables Google sign-in (the route answers 503). |
| `APPLE_CLIENT_IDS` | static | Comma-separated audiences of Apple identity tokens: the iOS bundle id `app.gains.Gains` for Sign in with Apple on the phone, plus the **Services ID** (developer.apple.com → Identifiers → Services IDs) when the web flow is offered on Android or the desktop. Empty disables Apple sign-in. |
| `GAINS_DATA_DIR` | env-specific | Where `gains-server.db` lives. `/var/lib/gains` in prod (created on first start), `./data` in dev. |
| `PORT` | optional | `5003`; nginx proxies `api.gains.gerra.sh` to it (`deploy/nginx/api.gains.gerra.sh.conf`). |

Nothing else is secret. The Apple `.p8` key is not needed: the server only *verifies* Apple's
tokens against its public keys, it never asks Apple for anything.
