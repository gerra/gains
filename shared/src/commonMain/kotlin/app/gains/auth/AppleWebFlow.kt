package app.gains.auth

import io.ktor.http.encodeURLParameter
import io.ktor.http.parseQueryString

/** Apple's web sign-in came back with something other than a code for this request. */
class AppleSignInException(message: String) : Exception(message)

/**
 * The app's side of Sign in with Apple without Apple's sheet (docs/sync.md, "Sign in with Apple
 * without Apple's sheet"), for the desktop now and Android next. The server runs the flow with
 * Apple: the app only opens [startUrl] in a browser, waits for the browser to come back to its
 * callback, and reads the one-time code from it with [parseCallback]. The code goes to
 * `POST /auth/exchange` as an [ExchangeCode]; Apple's tokens never reach the app.
 */
object AppleWebFlow {
    /**
     * Where the browser is sent back to on the desktop: the listener the app opened on [port]. The
     * server accepts any port on `127.0.0.1` for this, and nothing else on plain HTTP.
     */
    fun loopbackCallback(port: Int): String {
        require(port in 1..65535) { "Not a port: $port" }
        return "http://127.0.0.1:$port/"
    }

    /**
     * The page the browser opens: our server's `/auth/apple/start`, which redirects to Apple.
     * [serverBaseUrl] has no trailing slash. [state] comes back on the callback unchanged; the
     * server never shows it to Apple.
     */
    fun startUrl(serverBaseUrl: String, callback: String, state: String): String =
        "$serverBaseUrl/auth/apple/start?redirect=${callback.encodeURLParameter()}&state=${state.encodeURLParameter()}"

    /**
     * The one-time code from the callback the server sent the browser to. The state is checked
     * first: any local process can reach a loopback listener, so a callback carrying another state
     * says nothing about this sign-in, not even that it was cancelled. `error=cancelled` (the
     * person closed Apple's page) is a [SignInCancelledException]; any other error, or no code,
     * throws [AppleSignInException].
     */
    fun parseCallback(url: String, expectedState: String): String {
        val query = url.substringBefore('#').substringAfter('?', missingDelimiterValue = "")
        val parameters = parseQueryString(query)
        if (parameters["state"] != expectedState) throw AppleSignInException("Apple sign-in answered a different request.")
        when (val error = parameters["error"]) {
            null -> Unit
            "cancelled" -> throw SignInCancelledException()
            else -> throw AppleSignInException("Apple sign-in failed: $error")
        }
        return parameters["code"]?.takeIf { it.isNotEmpty() } ?: throw AppleSignInException("Apple sign-in returned no code.")
    }
}
