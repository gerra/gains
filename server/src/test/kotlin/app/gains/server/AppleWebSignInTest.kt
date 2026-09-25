package app.gains.server

import app.gains.sync.ExchangeRequest
import app.gains.sync.SignInResponse
import app.gains.sync.SyncApi
import app.gains.sync.SyncException
import app.gains.sync.SyncJson
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sign in with Apple through Apple's web page (docs/launch-plan.md, item 10), the way Android and
 * the desktop will use it: start, Apple's form post back, a one-time code on the app's callback,
 * and the exchange. Apple's page is played by the test, which signs the identity token Apple would
 * post with [FakeProvider]; the verifier is the real one.
 */
class AppleWebSignInTest {
    private val google = FakeProvider(JwksIdentityVerifier.GOOGLE_ISSUERS.first())
    private val apple = FakeProvider(JwksIdentityVerifier.APPLE_ISSUERS.first())
    private var now = Instant.parse("2026-09-25T12:00:00Z")
    private val web = AppleWebSignIn(APPLE_SERVICES_ID, "https://api.gains.gerra.sh/auth/apple/callback", clock = { now })

    private val desktopCallback = "http://127.0.0.1:53682/callback"

    private fun ApplicationTestBuilder.noRedirects(): HttpClient = createClient { followRedirects = false }

    /** What the app's browser sees after `start`: the query of Apple's authorization URL. */
    private suspend fun HttpClient.start(redirect: String = desktopCallback, state: String = "app-state"): Parameters {
        val response = get("/auth/apple/start") {
            parameter("redirect", redirect)
            parameter("state", state)
        }
        assertEquals(HttpStatusCode.Found, response.status, response.bodyAsText())
        val location = Url(response.headers[HttpHeaders.Location]!!)
        assertEquals("appleid.apple.com", location.host)
        assertEquals("/auth/authorize", location.encodedPath)
        return location.parameters
    }

    /** Apple posting back to the callback; [fields] are the form Apple would send. */
    private suspend fun HttpClient.callback(vararg fields: Pair<String, String>): HttpResponse =
        submitForm("/auth/apple/callback", Parameters.build { fields.forEach { (k, v) -> append(k, v) } })

    /** The query the browser is sent back to the app with. */
    private fun HttpResponse.appCallback(expectedBase: String = desktopCallback): Parameters {
        assertEquals(HttpStatusCode.SeeOther, status)
        val location = headers[HttpHeaders.Location]!!
        assertTrue(location.startsWith("$expectedBase?"), location)
        return Url(location).parameters
    }

    private suspend fun HttpClient.exchange(code: String): HttpResponse = post("/auth/exchange") {
        contentType(ContentType.Application.Json)
        setBody(SyncJson.encodeToString(ExchangeRequest.serializer(), ExchangeRequest(code)))
    }

    @Test
    fun theCallbackIssuesACodeThatWorksOnce() = testApplication {
        val appleTokens = FakeAppleTokens(codes = mapOf("apple-code" to "a-1"))
        val services = testServices(google, apple, appleTokens = appleTokens, appleWeb = web)
        application { gainsServer(services) }
        val browser = noRedirects()

        val authorize = browser.start()
        assertEquals("code id_token", authorize["response_type"])
        assertEquals("form_post", authorize["response_mode"])
        assertEquals("name email", authorize["scope"])
        assertEquals(APPLE_SERVICES_ID, authorize["client_id"])
        assertEquals("https://api.gains.gerra.sh/auth/apple/callback", authorize["redirect_uri"])
        assertFalse(authorize["state"] == "app-state", "the app's state never goes to Apple")

        val idToken = apple.token("a-1", APPLE_SERVICES_ID, email = "ada@example.com", emailVerified = "true", nonce = authorize["nonce"])
        val back = browser.callback(
            "state" to authorize["state"]!!,
            "code" to "apple-code",
            "id_token" to idToken,
            "user" to """{"name":{"firstName":"Ada","lastName":"Lovelace"},"email":"ada@example.com"}""",
        ).appCallback()
        assertEquals("app-state", back["state"])
        assertNull(back["error"])
        val code = back["code"]!!

        // Through the app's own client, as Android and the desktop will call it.
        val api = SyncApi(client, baseUrl = "", token = { null })
        val signedIn = api.exchange(code)
        assertEquals("Ada Lovelace", signedIn.user.name)
        assertEquals("ada@example.com", signedIn.user.email)
        assertEquals(listOf(Providers.APPLE), signedIn.user.providers)
        assertEquals(HttpStatusCode.OK, client.get("/auth/me") { header("Authorization", "Bearer ${signedIn.token}") }.status)

        // The sign-in's code became a refresh token issued to the Services ID, for item 6's revoke.
        assertEquals(listOf("apple-code" to APPLE_SERVICES_ID), appleTokens.exchanged)
        assertEquals(listOf("refresh-apple-code" to APPLE_SERVICES_ID), services.store.refreshTokens(signedIn.user.id, Providers.APPLE))

        val again = assertFailsWith<SyncException> { api.exchange(code) }
        assertTrue(again.unauthorized, "a code works once")
    }

    @Test
    fun theSameAppleIdOnThePhoneAndOnTheWebIsOneUser() = testApplication {
        val services = testServices(google, apple, appleWeb = web)
        application { gainsServer(services) }
        val browser = noRedirects()

        val authorize = browser.start()
        val back = browser.callback(
            "state" to authorize["state"]!!,
            "id_token" to apple.token("a-1", APPLE_SERVICES_ID, nonce = authorize["nonce"]),
        ).appCallback()
        val viaWeb = SyncJson.decodeFromString(SignInResponse.serializer(), client.exchange(back["code"]!!).bodyAsText())
        val viaPhone = SyncApi(client, baseUrl = "", token = { null })
            .signIn(app.gains.auth.AccountKind.APPLE, apple.token("a-1", APPLE_AUDIENCE), name = null)
        assertEquals(viaPhone.user.id, viaWeb.user.id, "Apple's subject is the same for the bundle id and the Services ID")
    }

    @Test
    fun aCodeExpiresAfterAMinute() = testApplication {
        val services = testServices(google, apple, appleWeb = web)
        application { gainsServer(services) }
        val browser = noRedirects()

        val authorize = browser.start()
        val back = browser.callback(
            "state" to authorize["state"]!!,
            "id_token" to apple.token("a-1", APPLE_SERVICES_ID, nonce = authorize["nonce"]),
        ).appCallback()
        now = now.plus(AppleWebSignIn.CODE_TTL).plusSeconds(1)
        assertEquals(HttpStatusCode.Unauthorized, client.exchange(back["code"]!!).status)
        assertEquals(HttpStatusCode.Unauthorized, client.exchange("never-issued").status)
    }

    @Test
    fun onlyTheAppsCallbacksAreAccepted() = testApplication {
        application { gainsServer(testServices(google, apple, appleWeb = web)) }
        val browser = noRedirects()

        for (redirect in listOf(
            "https://evil.example/auth/done",
            "https://gains.gerra.sh/auth/done/../../steal",
            "https://gains.gerra.sh/auth/doneX",
            "http://gains.gerra.sh/auth/done",
            "http://localhost:8080/callback",
            "http://127.0.0.1.evil.example/callback",
            "http://user@127.0.0.1:8080/callback",
            "https://127.0.0.1:8080/callback",
            "http://127.0.0.1/callback",
            "javascript:alert(1)",
            "",
        )) {
            val response = browser.get("/auth/apple/start") {
                parameter("redirect", redirect)
                parameter("state", "s")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, redirect)
        }
        browser.start(redirect = "https://gains.gerra.sh/auth/done")
        browser.start(redirect = "http://127.0.0.1:1234/")
        val noState = browser.get("/auth/apple/start") { parameter("redirect", desktopCallback) }
        assertEquals(HttpStatusCode.BadRequest, noState.status)
    }

    @Test
    fun aTokenFromAnotherSignInOrClientIsRefused() = testApplication {
        val services = testServices(google, apple, appleWeb = web)
        application { gainsServer(services) }
        val browser = noRedirects()

        val wrongNonce = browser.start()
        val replayed = browser.callback(
            "state" to wrongNonce["state"]!!,
            "id_token" to apple.token("a-1", APPLE_SERVICES_ID, nonce = "from-another-sign-in"),
        ).appCallback()
        assertEquals("failed", replayed["error"])
        assertNull(replayed["code"])

        // A token from the phone's native flow: right nonce, but issued to the bundle id.
        val wrongAudience = browser.start()
        val native = browser.callback(
            "state" to wrongAudience["state"]!!,
            "id_token" to apple.token("a-1", APPLE_AUDIENCE, nonce = wrongAudience["nonce"]),
        ).appCallback()
        assertEquals("failed", native["error"])

        val badToken = browser.start()
        val garbage = browser.callback("state" to badToken["state"]!!, "id_token" to "not-a-jwt").appCallback()
        assertEquals("failed", garbage["error"])
        assertEquals("app-state", garbage["state"])
    }

    @Test
    fun cancellingOnApplesPageIsACancelNotAFailure() = testApplication {
        application { gainsServer(testServices(google, apple, appleWeb = web)) }
        val browser = noRedirects()

        val authorize = browser.start(redirect = "https://gains.gerra.sh/auth/done")
        val back = browser.callback("state" to authorize["state"]!!, "error" to "user_cancelled_authorize")
            .appCallback(expectedBase = "https://gains.gerra.sh/auth/done")
        assertEquals("cancelled", back["error"])
        assertEquals("app-state", back["state"])
    }

    @Test
    fun aStateWorksOnceAndExpires() = testApplication {
        application { gainsServer(testServices(google, apple, appleWeb = web)) }
        val browser = noRedirects()

        val authorize = browser.start()
        val form = arrayOf("state" to authorize["state"]!!, "id_token" to apple.token("a-1", APPLE_SERVICES_ID, nonce = authorize["nonce"]))
        browser.callback(*form).appCallback()
        assertEquals(HttpStatusCode.BadRequest, browser.callback(*form).status, "a state finishes one sign-in")
        assertEquals(HttpStatusCode.BadRequest, browser.callback("state" to "made-up").status)

        val late = browser.start()
        now = now.plus(AppleWebSignIn.STATE_TTL).plusSeconds(1)
        val response = browser.callback("state" to late["state"]!!, "id_token" to apple.token("a-1", APPLE_SERVICES_ID, nonce = late["nonce"]))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun startIsCappedSoItCantBeFlooded() {
        val small = AppleWebSignIn(APPLE_SERVICES_ID, "https://x/cb", clock = { now }, maxPending = 2)
        assertTrue(small.start(desktopCallback, "1") != null)
        assertTrue(small.start(desktopCallback, "2") != null)
        assertNull(small.start(desktopCallback, "3"))
        now = now.plus(AppleWebSignIn.STATE_TTL).plusSeconds(1)
        assertTrue(small.start(desktopCallback, "4") != null, "expired ones make room")
    }

    @Test
    fun withoutAServicesIdTheWebFlowIsOff() = testApplication {
        application { gainsServer(testServices(google, apple)) }
        val browser = noRedirects()
        val start = browser.get("/auth/apple/start") {
            parameter("redirect", desktopCallback)
            parameter("state", "s")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, start.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.exchange("x").status)
    }

    @Test
    fun theNameComesFromApplesUserField() {
        assertEquals("Ada Lovelace", AppleWebSignIn.nameFromUserJson("""{"name":{"firstName":"Ada","lastName":"Lovelace"}}"""))
        assertEquals("Ada", AppleWebSignIn.nameFromUserJson("""{"name":{"firstName":" Ada ","lastName":""}}"""))
        assertNull(AppleWebSignIn.nameFromUserJson("""{"email":"a@b.c"}"""))
        assertNull(AppleWebSignIn.nameFromUserJson("not json"))
        assertNull(AppleWebSignIn.nameFromUserJson(null))
    }

    @Test
    fun theServicesIdIsAlwaysAnAcceptedAudience() {
        val nowhere = java.io.File("/nonexistent")
        val base = mapOf("JWT_SECRET" to "x".repeat(32), "APPLE_CLIENT_IDS" to "app.gains.Gains")
        val off = Config.fromEnvironment(base, nowhere)
        assertNull(off.appleServicesId)
        assertEquals("https://api.gains.gerra.sh", off.publicUrl)

        val on = Config.fromEnvironment(base + mapOf("APPLE_SERVICES_ID" to " app.gains.Gains.web ", "GAINS_PUBLIC_URL" to "http://localhost:5003/"), nowhere)
        assertEquals("app.gains.Gains.web", on.appleServicesId)
        assertEquals(listOf("app.gains.Gains", "app.gains.Gains.web"), on.appleClientIds)
        assertEquals("http://localhost:5003", on.publicUrl)

        val listedTwice = Config.fromEnvironment(base + mapOf("APPLE_CLIENT_IDS" to "app.gains.Gains,app.gains.Gains.web", "APPLE_SERVICES_ID" to "app.gains.Gains.web"), nowhere)
        assertEquals(listOf("app.gains.Gains", "app.gains.Gains.web"), listedTwice.appleClientIds)
    }
}
