package app.gains

import app.gains.auth.AccountKind
import app.gains.auth.AppleWebFlow
import app.gains.auth.AuthConfig
import app.gains.auth.AuthNotConfiguredException
import app.gains.auth.ExchangeCode
import app.gains.auth.GoogleOAuth
import app.gains.auth.IdentityAssertion
import app.gains.auth.IdentityProvider
import app.gains.auth.LoopbackRedirect
import app.gains.auth.SignInCancelledException
import app.gains.auth.SignInProof
import app.gains.resources.Res
import app.gains.resources.browser_sign_in_done
import app.gains.resources.browser_sign_in_done_title
import app.gains.ui.i18n.Texts
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Sign-in on the desktop, in the person's own browser (docs/sync.md, "Signing in"). Both providers
 * send the browser back to a [LoopbackRedirect] on `127.0.0.1`:
 *
 * - Google is [GoogleOAuth]'s PKCE flow with a **Desktop app** client. The code the browser brings
 *   back is traded, with [clientSecret], for an identity token whose audience is
 *   [AuthConfig.googleClientId]. [http] is the app's client, used for the one call to Google's
 *   token endpoint.
 * - Apple is [AppleWebFlow]: our server runs Apple's web flow and sends the browser back with a
 *   one-time code, which `AccountRepository` trades for our token.
 *
 * [page] is the HTML the tab shows once it has handed the app its answer ([donePage]).
 *
 * A browser tab can be closed without the app hearing of it, so the wait gives up after [timeout]
 * and reads as a cancel: the sign-in screen goes quiet again rather than showing an error.
 */
internal class DesktopIdentityProvider(
    private val config: AuthConfig,
    private val clientSecret: String?,
    private val http: HttpClient,
    private val page: suspend () -> String,
    private val browse: (URI) -> Unit = ::openInBrowser,
    private val timeout: Duration = 5.minutes,
) : IdentityProvider {
    override suspend fun signIn(kind: AccountKind): SignInProof = when (kind) {
        AccountKind.GOOGLE -> signInWithGoogle()
        AccountKind.APPLE -> signInWithApple()
        AccountKind.GUEST -> throw AuthNotConfiguredException(kind)
    }

    /** The account chooser in the browser, then the code it redirects with traded for an identity token. The server reads the name from Google's token. */
    private suspend fun signInWithGoogle(): IdentityAssertion {
        val clientId = config.googleClientId?.takeIf { it.isNotBlank() } ?: throw AuthNotConfiguredException(AccountKind.GOOGLE)
        val verifier = GoogleOAuth.base64Url(randomBytes(32))
        val challenge = GoogleOAuth.base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))
        val state = GoogleOAuth.base64Url(randomBytes(32))
        val (redirect, redirectUri) = inBrowser(GoogleOAuth::loopbackRedirectUri) { redirectUri ->
            GoogleOAuth.authorizationUrl(clientId, challenge, state, redirectUri)
        }
        val code = GoogleOAuth.parseCallback(redirect, state)
        return IdentityAssertion(GoogleOAuth.exchange(http, clientId, code, verifier, redirectUri, clientSecret), name = null)
    }

    /**
     * Apple's page through our server, which comes back with a one-time code. Apple sends the name
     * to the server, which keeps it, so there is none to pass along here.
     */
    private suspend fun signInWithApple(): ExchangeCode {
        if (!config.appleEnabled) throw AuthNotConfiguredException(AccountKind.APPLE)
        val server = checkNotNull(config.serverBaseUrl)
        val state = GoogleOAuth.base64Url(randomBytes(32))
        val (redirect, _) = inBrowser(AppleWebFlow::loopbackCallback) { callback -> AppleWebFlow.startUrl(server, callback, state) }
        return ExchangeCode(AppleWebFlow.parseCallback(redirect, state))
    }

    /**
     * Opens a loopback listener, makes the address the browser should come back to from its port
     * with [callback], opens the page [url] builds around it, and waits for the browser to arrive.
     * Returns the URL the browser requested and the callback it was sent to.
     */
    private suspend fun inBrowser(callback: (port: Int) -> String, url: (callback: String) -> String): Pair<String, String> {
        val page = page()
        return withContext(Dispatchers.IO) {
            LoopbackRedirect.open().use { loopback ->
                val redirectUri = callback(loopback.port)
                browse(URI(url(redirectUri)))
                val redirect = withTimeoutOrNull(timeout) { loopback.receive(page) } ?: throw SignInCancelledException()
                redirect to redirectUri
            }
        }
    }
}

/**
 * The desktop's sign-in settings, as JVM system properties that the Gradle build passes to the
 * app from its own properties (`gains.serverUrl`, `gains.googleDesktopClientId`,
 * `gains.googleDesktopClientSecret`, `gains.appleServicesId`; see docs/development.md), so they
 * change without touching code. Google stays off until both its id and secret are there: the
 * token endpoint refuses a Desktop app client that sends no secret. Apple stays off until the
 * Services ID is there, which the owner sets once the server has `APPLE_SERVICES_ID`: before that
 * `/auth/apple/start` answers 503, and the button would only open an error page.
 */
internal fun desktopAuthConfig(): AuthConfig = AuthConfig(
    serverBaseUrl = systemProperty(SERVER_URL)?.trimEnd('/'),
    googleClientId = systemProperty(GOOGLE_CLIENT_ID)?.takeIf { desktopGoogleClientSecret() != null },
    appleServiceId = systemProperty(APPLE_SERVICES_ID),
)

internal fun desktopGoogleClientSecret(): String? = systemProperty(GOOGLE_CLIENT_SECRET)

internal const val SERVER_URL = "gains.serverUrl"
internal const val GOOGLE_CLIENT_ID = "gains.googleDesktopClientId"
internal const val GOOGLE_CLIENT_SECRET = "gains.googleDesktopClientSecret"
internal const val APPLE_SERVICES_ID = "gains.appleServicesId"

private fun systemProperty(name: String): String? = System.getProperty(name)?.trim()?.ifBlank { null }

/**
 * Opens [uri] in the default browser. AWT's `Desktop` can't on some Linux desktops (no GNOME
 * libraries), where `xdg-open` still can.
 */
private fun openInBrowser(uri: URI) {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(uri)
    } else {
        ProcessBuilder("xdg-open", uri.toString()).start()
    }
}

/**
 * What the browser shows once it has handed the app its answer, in the colors of the app and of
 * site/style.css. The words come through [texts], in the app's language, since the plain
 * `getString` needs a display (see [Texts]).
 */
internal suspend fun donePage(texts: Texts): String {
    val title = escapeHtml(texts.get(Res.string.browser_sign_in_done_title))
    val body = escapeHtml(texts.get(Res.string.browser_sign_in_done))
    return """
        <!doctype html>
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <title>$title</title>
        <style>
        :root { color-scheme: dark light; --bg: #0b0d12; --text: #f2f4f8; --accent: #c8ff4d; }
        @media (prefers-color-scheme: light) { :root { --bg: #f3f4f8; --text: #12141a; --accent: #3e8e00; } }
        body { margin: 0; min-height: 100vh; display: grid; place-content: center; text-align: center;
               background: var(--bg); color: var(--text); font: 16px/1.5 system-ui, sans-serif; padding: 0 16px; }
        h1 { font-size: 22px; margin: 0 0 8px; }
        h1::before { content: ""; display: block; width: 12px; height: 12px; border-radius: 50%; background: var(--accent); margin: 0 auto 16px; }
        </style></head>
        <body><h1>$title</h1><p>$body</p></body></html>
    """.trimIndent()
}

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private val random = SecureRandom()

/** [count] bytes from the system's secure random source, for the PKCE verifier and the states. */
private fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)
