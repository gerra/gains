package app.gains.strava

import io.ktor.client.engine.HttpClientEngine

/** The Ktor engine for this platform: CIO on the desktop, OkHttp on Android, NSURLSession on iOS. */
expect fun platformHttpEngine(): HttpClientEngine
