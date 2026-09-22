package app.gains.sync

import io.ktor.client.HttpClient

/** A Ktor client on the platform's own engine: OkHttp on Android, Darwin on iOS, CIO on the desktop. */
expect fun createHttpClient(): HttpClient
