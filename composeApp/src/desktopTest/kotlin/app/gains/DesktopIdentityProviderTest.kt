package app.gains

import app.gains.auth.AccountKind
import app.gains.auth.AuthConfig
import app.gains.auth.AuthNotConfiguredException
import app.gains.auth.SignInCancelledException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The desktop's Google sign-in end to end, with a stand-in browser that follows the redirect to
 * the app's loopback listener and a mock of Google's token endpoint.
 */
class DesktopIdentityProviderTest {
    private val clientId = "95741411455-desktop.apps.googleusercontent.com"
    private val config = AuthConfig(googleClientId = clientId, serverBaseUrl = "https://api.example")

    private var exchanged: Map<String, String?> = emptyMap()
    private val http = HttpClient(MockEngine { request ->
        val form = (request.body as FormDataContent).formData
        exchanged = form.names().associateWith { form[it] }
        respond("""{"id_token":"eyJ.desktop"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    /** The browser: signs in at once and lands on the redirect Google would send it to. */
    private fun browserThatSignsIn(answer: (state: String) -> String): (URI) -> Unit = { uri ->
        val query = parseQueryString(uri.rawQuery)
        val redirect = query["redirect_uri"]!!
        val url = "$redirect/?${answer(query["state"]!!)}"
        thread {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.responseCode
            connection.disconnect()
        }
    }

    @Test
    fun googleComesBackThroughTheLoopbackWithAToken() = runBlocking {
        var opened: URI? = null
        val browser = browserThatSignsIn { state -> "state=${URLEncoder.encode(state, "UTF-8")}&code=4%2F0Ab&scope=email" }
        val provider = DesktopIdentityProvider(config, "GOCSPX-test", http, browse = { opened = it; browser(it) })
        val assertion = provider.signIn(AccountKind.GOOGLE)
        assertEquals("eyJ.desktop", assertion.token)
        assertNull(assertion.name)

        val authorization = parseQueryString(opened!!.rawQuery)
        assertEquals(clientId, authorization["client_id"])
        assertEquals("S256", authorization["code_challenge_method"])
        val redirect = authorization["redirect_uri"]!!
        assertTrue(redirect.matches(Regex("""http://127\.0\.0\.1:\d+""")), redirect)
        assertEquals("4/0Ab", exchanged["code"])
        assertEquals(redirect, exchanged["redirect_uri"])
        assertEquals("GOCSPX-test", exchanged["client_secret"])
    }

    @Test
    fun aRedirectForAnotherRequestIsRefused() {
        val provider = DesktopIdentityProvider(config, "GOCSPX-test", http, browse = browserThatSignsIn { "state=someone-else&code=4%2F0Ab" })
        assertFailsWith<Exception> { runBlocking { provider.signIn(AccountKind.GOOGLE) } }
        assertTrue(exchanged.isEmpty(), "no code is traded")
    }

    /** A closed tab never calls back: the wait ends as a cancel, which the sign-in screen doesn't report. */
    @Test
    fun aTabThatNeverAnswersTimesOutAsACancel() {
        val provider = DesktopIdentityProvider(config, "GOCSPX-test", http, browse = {}, timeout = 200.milliseconds)
        assertFailsWith<SignInCancelledException> { runBlocking { provider.signIn(AccountKind.GOOGLE) } }
    }

    @Test
    fun appleIsNotHereYet() {
        val provider = DesktopIdentityProvider(config, "GOCSPX-test", http, browse = { error("no browser for Apple") })
        assertFailsWith<AuthNotConfiguredException> { runBlocking { provider.signIn(AccountKind.APPLE) } }
    }

    @Test
    fun theConfigComesFromSystemPropertiesAndGoogleNeedsItsSecret() {
        val names = listOf(SERVER_URL, GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET)
        val saved = names.associateWith { System.getProperty(it) }
        try {
            names.forEach { System.clearProperty(it) }
            assertFalse(desktopAuthConfig().syncEnabled)

            System.setProperty(SERVER_URL, "https://api.example/ ")
            System.setProperty(GOOGLE_CLIENT_ID, clientId)
            assertEquals("https://api.example", desktopAuthConfig().serverBaseUrl)
            assertFalse(desktopAuthConfig().googleEnabled, "no secret, no button")

            System.setProperty(GOOGLE_CLIENT_SECRET, "GOCSPX-test")
            assertTrue(desktopAuthConfig().googleEnabled)
            assertFalse(desktopAuthConfig().appleEnabled)
            assertEquals("GOCSPX-test", desktopGoogleClientSecret())
        } finally {
            saved.forEach { (name, value) -> if (value == null) System.clearProperty(name) else System.setProperty(name, value) }
        }
    }
}
