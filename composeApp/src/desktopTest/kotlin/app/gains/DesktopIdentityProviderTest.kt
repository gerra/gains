package app.gains

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.gains.auth.AccountKind
import app.gains.auth.AppleSignInException
import app.gains.auth.AuthConfig
import app.gains.auth.AuthNotConfiguredException
import app.gains.auth.ExchangeCode
import app.gains.auth.IdentityAssertion
import app.gains.auth.SignInCancelledException
import app.gains.ui.i18n.Texts
import app.gains.ui.i18n.rememberTexts
import app.gains.ui.theme.GainsTheme
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The desktop's browser sign-ins end to end, with a stand-in browser that follows the redirect to
 * the app's loopback listener, and a mock of Google's token endpoint. Our server's side of Apple's
 * web flow has its own tests (`AppleWebSignInTest`).
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
        val provider = DesktopIdentityProvider(config, "GOCSPX-test", http, page = { "<p>done</p>" }, browse = { opened = it; browser(it) })
        val assertion = assertIs<IdentityAssertion>(provider.signIn(AccountKind.GOOGLE))
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
        val provider = DesktopIdentityProvider(config, "GOCSPX-test", http, page = { "<p>done</p>" }, browse = browserThatSignsIn { "state=someone-else&code=4%2F0Ab" })
        assertFailsWith<Exception> { runBlocking { provider.signIn(AccountKind.GOOGLE) } }
        assertTrue(exchanged.isEmpty(), "no code is traded")
    }

    /** A closed tab never calls back: the wait ends as a cancel, which the sign-in screen doesn't report. */
    @Test
    fun aTabThatNeverAnswersTimesOutAsACancel() {
        val provider = DesktopIdentityProvider(config, "GOCSPX-test", http, page = { "<p>done</p>" }, browse = {}, timeout = 200.milliseconds)
        assertFailsWith<SignInCancelledException> { runBlocking { provider.signIn(AccountKind.GOOGLE) } }
    }

    /** The page's words come from the composition's strings, which work without a display, as on CI. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theDonePageSaysTheTabCanBeClosed() = runDesktopComposeUiTest(400, 800) {
        var texts: Texts? = null
        setContent { GainsTheme { texts = rememberTexts() } }
        val page = runBlocking { donePage(checkNotNull(texts)) }
        assertTrue(page.startsWith("<!doctype html>"), page)
        assertTrue("<title>Back to Gains</title>" in page, page)
        assertTrue("You can close this tab and go back to Gains." in page, page)
    }

    private val appleConfig = config.copy(appleServiceId = "app.gains.Gains.web")

    /** The browser for Apple: our server's start page, Apple, and back to the loopback with [answer]. */
    private fun browserThroughOurServer(answer: (state: String) -> String): (URI) -> Unit = { uri ->
        val query = parseQueryString(uri.rawQuery)
        val url = "${query["redirect"]!!}?${answer(query["state"]!!)}"
        thread {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.responseCode
            connection.disconnect()
        }
    }

    @Test
    fun appleComesBackThroughOurServerWithACodeToExchange() = runBlocking {
        var opened: URI? = null
        val browser = browserThroughOurServer { state -> "code=one-time&state=${URLEncoder.encode(state, "UTF-8")}" }
        val provider = DesktopIdentityProvider(appleConfig, null, http, page = { "<p>done</p>" }, browse = { opened = it; browser(it) })
        assertEquals(ExchangeCode("one-time"), provider.signIn(AccountKind.APPLE))

        assertEquals("https://api.example/auth/apple/start", opened!!.toString().substringBefore('?'))
        val redirect = parseQueryString(opened!!.rawQuery)["redirect"]!!
        assertTrue(redirect.matches(Regex("""http://127\.0\.0\.1:\d+/""")), redirect)
        assertTrue(exchanged.isEmpty(), "Google's token endpoint is not involved")
    }

    @Test
    fun closingApplesPageIsACancelAndAFailureIsNot() {
        val cancelled = DesktopIdentityProvider(appleConfig, null, http, page = { "<p>done</p>" }, browse = browserThroughOurServer { "error=cancelled&state=$it" })
        assertFailsWith<SignInCancelledException> { runBlocking { cancelled.signIn(AccountKind.APPLE) } }
        val failed = DesktopIdentityProvider(appleConfig, null, http, page = { "<p>done</p>" }, browse = browserThroughOurServer { "error=failed&state=$it" })
        assertFailsWith<AppleSignInException> { runBlocking { failed.signIn(AccountKind.APPLE) } }
        val someoneElse = DesktopIdentityProvider(appleConfig, null, http, page = { "<p>done</p>" }, browse = browserThroughOurServer { "code=one-time&state=someone-else" })
        assertFailsWith<AppleSignInException> { runBlocking { someoneElse.signIn(AccountKind.APPLE) } }
    }

    @Test
    fun appleStaysOffWithoutTheServicesId() {
        val provider = DesktopIdentityProvider(config, "GOCSPX-test", http, page = { "<p>done</p>" }, browse = { error("no browser for Apple") })
        assertFailsWith<AuthNotConfiguredException> { runBlocking { provider.signIn(AccountKind.APPLE) } }
    }

    @Test
    fun theConfigComesFromSystemPropertiesAndGoogleNeedsItsSecret() {
        val names = listOf(SERVER_URL, GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, APPLE_SERVICES_ID)
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

            System.setProperty(APPLE_SERVICES_ID, " ")
            assertFalse(desktopAuthConfig().appleEnabled, "blank is off")
            System.setProperty(APPLE_SERVICES_ID, "app.gains.Gains.web")
            assertTrue(desktopAuthConfig().appleEnabled)
        } finally {
            saved.forEach { (name, value) -> if (value == null) System.clearProperty(name) else System.setProperty(name, value) }
        }
    }
}
