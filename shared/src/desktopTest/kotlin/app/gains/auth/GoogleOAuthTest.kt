package app.gains.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The pieces of Google's PKCE flow that do not need a browser: the URLs, the redirect and the token exchange. */
class GoogleOAuthTest {
    private val clientId = "95741411455-2fouqgkjlnault5cvd8lc17n4jekg7ea.apps.googleusercontent.com"
    private val scheme = "com.googleusercontent.apps.95741411455-2fouqgkjlnault5cvd8lc17n4jekg7ea"

    @Test
    fun theRedirectSchemeIsTheReversedClientId() {
        assertEquals(scheme, GoogleOAuth.redirectScheme(clientId))
        assertEquals("$scheme:/oauth2redirect", GoogleOAuth.redirectUri(clientId))
        assertFailsWith<IllegalArgumentException> { GoogleOAuth.redirectScheme("app.gains.Gains") }
    }

    @Test
    fun theAuthorizationUrlCarriesEveryParameterEncoded() {
        val url = GoogleOAuth.authorizationUrl(clientId, codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", state = "a b&c")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        val query = url.substringAfter('?')
        assertTrue(" " !in query, "spaces are encoded: $query")
        assertTrue("scope=openid%20email%20profile" in query, query)
        assertTrue("redirect_uri=com.googleusercontent.apps.95741411455-2fouqgkjlnault5cvd8lc17n4jekg7ea%3A%2Foauth2redirect" in query, query)
        val parameters = parseQueryString(query)
        assertEquals(clientId, parameters["client_id"])
        assertEquals("$scheme:/oauth2redirect", parameters["redirect_uri"])
        assertEquals("code", parameters["response_type"])
        assertEquals("openid email profile", parameters["scope"])
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", parameters["code_challenge"])
        assertEquals("S256", parameters["code_challenge_method"])
        assertEquals("select_account", parameters["prompt"])
        assertEquals("a b&c", parameters["state"])
    }

    @Test
    fun theCallbackGivesTheCodeOnlyForOurState() {
        val redirect = "$scheme:/oauth2redirect?state=s1&code=4%2F0Ab-xyz&scope=email%20openid"
        assertEquals("4/0Ab-xyz", GoogleOAuth.parseCallback(redirect, "s1"))
        assertEquals("4/0Ab-xyz", GoogleOAuth.parseCallback("$redirect#", "s1"))
        assertFailsWith<GoogleOAuthException> { GoogleOAuth.parseCallback(redirect, "s2") }
        assertFailsWith<GoogleOAuthException> { GoogleOAuth.parseCallback("$scheme:/oauth2redirect?state=s1", "s1") }
        assertFailsWith<GoogleOAuthException> { GoogleOAuth.parseCallback("$scheme:/oauth2redirect", "s1") }
        val denied = assertFailsWith<GoogleOAuthException> { GoogleOAuth.parseCallback("$scheme:/oauth2redirect?error=access_denied&state=s1", "s1") }
        assertTrue("access_denied" in denied.message!!)
    }

    /** RFC 7636, appendix B: the example verifier's bytes and the challenge derived from it. */
    @Test
    fun base64UrlMatchesThePkceExample() {
        val random = intArrayOf(
            116, 24, 223, 180, 151, 153, 224, 37, 79, 250, 96, 125, 216, 173, 187, 186,
            22, 212, 37, 77, 105, 214, 191, 240, 91, 88, 5, 88, 83, 132, 141, 121,
        ).map { it.toByte() }.toByteArray()
        val verifier = GoogleOAuth.base64Url(random)
        assertEquals("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", verifier)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GoogleOAuth.base64Url(digest))
    }

    @Test
    fun theExchangePostsTheCodeAndReturnsTheIdToken() = runBlocking {
        var seen: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            seen = request
            respond(
                """{"access_token":"ya29","expires_in":3599,"id_token":"eyJ.id.token","scope":"openid","token_type":"Bearer"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        assertEquals("eyJ.id.token", GoogleOAuth.exchange(client, clientId, code = "4/0Ab", verifier = "v".repeat(43)))
        val request = seen!!
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://oauth2.googleapis.com/token", request.url.toString())
        val form = (request.body as FormDataContent).formData
        assertEquals("authorization_code", form["grant_type"])
        assertEquals(clientId, form["client_id"])
        assertEquals("4/0Ab", form["code"])
        assertEquals("v".repeat(43), form["code_verifier"])
        assertEquals("$scheme:/oauth2redirect", form["redirect_uri"])
        assertEquals(null, form["client_secret"], "an iOS client has no secret")
    }

    @Test
    fun theLoopbackRedirectIsThePortOn127001() {
        assertEquals("http://127.0.0.1:53682", GoogleOAuth.loopbackRedirectUri(53682))
        assertFailsWith<IllegalArgumentException> { GoogleOAuth.loopbackRedirectUri(0) }
        assertFailsWith<IllegalArgumentException> { GoogleOAuth.loopbackRedirectUri(65536) }
    }

    /** A Desktop app client: the loopback redirect in both calls, and its secret in the exchange. */
    @Test
    fun aDesktopClientUsesTheLoopbackRedirectAndItsSecret() = runBlocking {
        val desktopId = "95741411455-desktop.apps.googleusercontent.com"
        val redirect = GoogleOAuth.loopbackRedirectUri(53682)
        val url = GoogleOAuth.authorizationUrl(desktopId, codeChallenge = "c", state = "s", redirectUri = redirect)
        assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A53682&" in url, url)
        assertEquals(redirect, parseQueryString(url.substringAfter('?'))["redirect_uri"])
        assertEquals("4/0Ab", GoogleOAuth.parseCallback("$redirect/?state=s&code=4%2F0Ab&scope=email", "s"))

        var seen: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            seen = request
            respond("""{"id_token":"eyJ.desktop"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val token = GoogleOAuth.exchange(client, desktopId, "4/0Ab", "v".repeat(43), redirectUri = redirect, clientSecret = "GOCSPX-test")
        assertEquals("eyJ.desktop", token)
        val form = (seen!!.body as FormDataContent).formData
        assertEquals(desktopId, form["client_id"])
        assertEquals(redirect, form["redirect_uri"])
        assertEquals("GOCSPX-test", form["client_secret"])
    }

    @Test
    fun aRefusedExchangeSaysWhy() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(
                """{"error":"invalid_grant","error_description":"Bad Request"}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val error = assertFailsWith<GoogleOAuthException> { GoogleOAuth.exchange(client, clientId, "4/0Ab", "v".repeat(43)) }
        assertTrue("Bad Request" in error.message!!)
    }
}
