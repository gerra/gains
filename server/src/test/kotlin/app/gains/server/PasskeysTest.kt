package app.gains.server

import app.gains.auth.Account
import app.gains.auth.AccountKind
import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.auth.AuthNotConfiguredException
import app.gains.auth.IdentityProvider
import app.gains.auth.NoIdentityProvider
import app.gains.auth.SignInProof
import app.gains.data.DesktopDriverFactory
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.sync.PasskeyChallenge
import app.gains.sync.PasskeyFinishRequest
import app.gains.sync.SignInRequest
import app.gains.sync.SignInResponse
import app.gains.sync.SyncApi
import app.gains.sync.SyncJson
import app.gains.sync.SyncStore
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Passkeys (docs/launch-plan.md, item 19) through the routes, with [SoftAuthenticator] in place of
 * a phone: one is added to an account that exists and signs back into it, the ceremonies work
 * once and only for whoever started them, and a passkey from elsewhere, or from a deleted
 * account, gets nobody in.
 */
class PasskeysTest {
    private val google = FakeProvider(JwksIdentityVerifier.GOOGLE_ISSUERS.first())
    private val apple = FakeProvider(JwksIdentityVerifier.APPLE_ISSUERS.first())

    private fun services(origins: Set<String> = setOf(ORIGIN), clock: () -> Instant = Instant::now) =
        testServices(google, apple, passkeys = { Passkeys(it, RP_ID, origins, clock) })

    private suspend fun HttpClient.start(path: String, token: String? = null): PasskeyChallenge {
        val response = post(path) { token?.let { header("Authorization", "Bearer $it") } }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SyncJson.decodeFromString(PasskeyChallenge.serializer(), response.bodyAsText())
    }

    private suspend fun HttpClient.finish(path: String, id: String, credential: String, token: String? = null): HttpResponse = post(path) {
        token?.let { header("Authorization", "Bearer $it") }
        contentType(ContentType.Application.Json)
        setBody(SyncJson.encodeToString(PasskeyFinishRequest.serializer(), PasskeyFinishRequest(id, credential)))
    }

    private suspend fun HttpClient.signInWithGoogle(subject: String, email: String): SignInResponse {
        val response = post("/auth/google") {
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(SignInRequest.serializer(), SignInRequest(google.token(subject, GOOGLE_AUDIENCE, email = email, name = "Ada", emailVerified = true))))
        }
        return SyncJson.decodeFromString(SignInResponse.serializer(), response.bodyAsText())
    }

    /** Adds a passkey made by [device] to the account behind [token]. */
    private suspend fun HttpClient.addPasskey(device: SoftAuthenticator, token: String): HttpResponse {
        val challenge = start("/auth/passkey/register/start", token)
        return finish("/auth/passkey/register/finish", challenge.id, device.create(challenge.options), token)
    }

    private suspend fun HttpClient.signInWithPasskey(device: SoftAuthenticator, passkey: SoftAuthenticator.Passkey = device.passkeys.last()): HttpResponse {
        val challenge = start("/auth/passkey/signin/start")
        return finish("/auth/passkey/signin/finish", challenge.id, device.get(challenge.options, passkey))
    }

    @Test
    fun aPasskeyAddedToAnAccountSignsBackIntoIt() = testApplication {
        val services = services()
        application { gainsServer(services) }
        val phone = SoftAuthenticator(ORIGIN)
        val ada = client.signInWithGoogle("g-1", "ada@example.com")

        val challenge = client.start("/auth/passkey/register/start", ada.token)
        val options = SyncJson.parseToJsonElement(challenge.options).jsonObject
        assertEquals(RP_ID, options.getValue("rp").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("ada@example.com", options.getValue("user").jsonObject.getValue("name").jsonPrimitive.content, "the passkey list shows the address")
        assertEquals("required", options.getValue("authenticatorSelection").jsonObject.getValue("residentKey").jsonPrimitive.content)
        assertEquals(HttpStatusCode.NoContent, client.finish("/auth/passkey/register/finish", challenge.id, phone.create(challenge.options), ada.token).status)

        val signedIn = client.signInWithPasskey(phone)
        assertEquals(HttpStatusCode.OK, signedIn.status, signedIn.bodyAsText())
        val response = SyncJson.decodeFromString(SignInResponse.serializer(), signedIn.bodyAsText())
        assertEquals(ada.user.id, response.user.id)
        assertEquals(listOf("google"), response.user.providers, "a passkey is not an identity: it leads into the account it was added to")
        assertEquals(ada.user.id, services.tokens.userId(response.token))
        assertEquals(1L, services.store.passkey(services.store.passkeyIds(ada.user.id).single())!!.signCount, "the counter is kept")

        // A second device: same user handle, and the first passkey excluded so it isn't made twice.
        val laptop = SoftAuthenticator(ORIGIN)
        val second = client.start("/auth/passkey/register/start", ada.token)
        val excluded = SyncJson.parseToJsonElement(second.options).jsonObject.getValue("excludeCredentials").jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(services.store.passkeyIds(ada.user.id), excluded)
        assertEquals(HttpStatusCode.NoContent, client.finish("/auth/passkey/register/finish", second.id, laptop.create(second.options), ada.token).status)
        assertTrue(phone.passkeys.single().userHandle.contentEquals(laptop.passkeys.single().userHandle))
        assertEquals(HttpStatusCode.OK, client.signInWithPasskey(laptop).status)
    }

    @Test
    fun eachCeremonyWorksOnceAndOnlyForWhoeverStartedIt() = testApplication {
        val services = services()
        application { gainsServer(services) }
        val phone = SoftAuthenticator(ORIGIN)
        val ada = client.signInWithGoogle("g-1", "ada@example.com")
        val grace = client.signInWithGoogle("g-2", "grace@example.com")

        assertEquals(HttpStatusCode.Unauthorized, client.post("/auth/passkey/register/start").status, "adding one needs an account")

        // Ada's ceremony can't put a passkey on Grace's account.
        val adas = client.start("/auth/passkey/register/start", ada.token)
        val made = phone.create(adas.options)
        assertEquals(HttpStatusCode.BadRequest, client.finish("/auth/passkey/register/finish", adas.id, made, grace.token).status)
        assertEquals(HttpStatusCode.BadRequest, client.finish("/auth/passkey/register/finish", adas.id, made, ada.token).status, "and the attempt used it up")
        assertEquals(emptyList(), services.store.passkeyIds(grace.user.id))
        assertEquals(emptyList(), services.store.passkeyIds(ada.user.id))

        assertEquals(HttpStatusCode.NoContent, client.addPasskey(phone, ada.token).status)
        assertEquals(HttpStatusCode.BadRequest, client.finish("/auth/passkey/register/finish", "no-such-id", "{}", ada.token).status)

        // A sign-in answer is good for its own challenge, once.
        val first = client.start("/auth/passkey/signin/start")
        val second = client.start("/auth/passkey/signin/start")
        val answer = phone.get(first.options)
        assertEquals(HttpStatusCode.Unauthorized, client.finish("/auth/passkey/signin/finish", second.id, answer).status, "signed another challenge")
        assertEquals(HttpStatusCode.OK, client.finish("/auth/passkey/signin/finish", first.id, answer).status)
        assertEquals(HttpStatusCode.Unauthorized, client.finish("/auth/passkey/signin/finish", first.id, answer).status, "a ceremony works once")
        assertEquals(HttpStatusCode.BadRequest, client.finish("/auth/passkey/signin/finish", client.start("/auth/passkey/signin/start").id, "not json").status)
    }

    @Test
    fun aPasskeyFromElsewhereGetsNobodyIn() = testApplication {
        val services = services()
        application { gainsServer(services) }
        val ada = client.signInWithGoogle("g-1", "ada@example.com")

        // Made on a page that isn't ours: its client data names another origin.
        val phishing = SoftAuthenticator("https://gains.example.evil")
        assertEquals(HttpStatusCode.BadRequest, client.addPasskey(phishing, ada.token).status)
        val phone = SoftAuthenticator(ORIGIN)
        assertEquals(HttpStatusCode.NoContent, client.addPasskey(phone, ada.token).status)
        phone.origin = "https://gains.example.evil"
        assertEquals(HttpStatusCode.Unauthorized, client.signInWithPasskey(phone).status)
        phone.origin = ORIGIN

        // A passkey the server never stored, for the same relying party.
        val stranger = SoftAuthenticator(ORIGIN)
        stranger.create(client.start("/auth/passkey/register/start", ada.token).options)
        assertEquals(HttpStatusCode.Unauthorized, client.signInWithPasskey(stranger).status)

        // A copy of the key: its counter falls behind the one the server last saw.
        assertEquals(HttpStatusCode.OK, client.signInWithPasskey(phone).status)
        assertEquals(HttpStatusCode.OK, client.signInWithPasskey(phone).status)
        phone.passkeys.last().counter = 0
        assertEquals(HttpStatusCode.Unauthorized, client.signInWithPasskey(phone).status)
    }

    @Test
    fun deletingTheAccountRemovesItsPasskeys() = testApplication {
        val services = services()
        application { gainsServer(services) }
        val phone = SoftAuthenticator(ORIGIN)
        val ada = client.signInWithGoogle("g-1", "ada@example.com")
        assertEquals(HttpStatusCode.NoContent, client.addPasskey(phone, ada.token).status)

        assertEquals(HttpStatusCode.NoContent, client.delete("/auth/account") { header("Authorization", "Bearer ${ada.token}") }.status)
        assertEquals(emptyList(), services.store.passkeyIds(ada.user.id))
        assertEquals(HttpStatusCode.Unauthorized, client.signInWithPasskey(phone).status, "the passkey left on the phone leads nowhere")
    }

    @Test
    fun theAndroidAppsOriginIsAcceptedOnceConfigured() = testApplication {
        val android = "android:apk-key-hash:$APK_KEY_HASH"
        val services = services(origins = setOf(ORIGIN, android))
        application { gainsServer(services) }
        val ada = client.signInWithGoogle("g-1", "ada@example.com")
        val phone = SoftAuthenticator(android)
        assertEquals(HttpStatusCode.NoContent, client.addPasskey(phone, ada.token).status)
        assertEquals(HttpStatusCode.OK, client.signInWithPasskey(phone).status)

        val config = Config.fromEnvironment(
            mapOf("JWT_SECRET" to "x".repeat(32), "GAINS_SITE_URL" to "https://gains.gerra.sh/", "PASSKEY_ORIGINS" to " $android , "),
            workingDir = kotlin.io.path.createTempDirectory().toFile(),
        )
        assertEquals("gains.gerra.sh", config.passkeyRpId)
        assertEquals(setOf("https://gains.gerra.sh", android), config.allPasskeyOrigins)
    }

    @Test
    fun ceremoniesExpireAndAnonymousStartsAreLimited() = testApplication {
        var now = Instant.parse("2026-09-28T12:00:00Z")
        val services = services(clock = { now })
        application { gainsServer(services) }
        val phone = SoftAuthenticator(ORIGIN)
        val ada = client.signInWithGoogle("g-1", "ada@example.com")

        val late = client.start("/auth/passkey/register/start", ada.token)
        now += Passkeys.TTL
        assertEquals(HttpStatusCode.BadRequest, client.finish("/auth/passkey/register/finish", late.id, phone.create(late.options), ada.token).status)

        var status = HttpStatusCode.OK
        repeat(40) { status = client.post("/auth/passkey/signin/start").status }
        assertEquals(HttpStatusCode.TooManyRequests, status)
    }

    @Test
    fun withoutPasskeysTheRoutesAreOff() = testApplication {
        application { gainsServer(testServices(google, apple)) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/auth/passkey/signin/start").status)
    }

    @Test
    fun theAppAddsAPasskeyAndSignsInWithItThroughItsOwnRepository() = testApplication {
        val services = services()
        application { gainsServer(services) }
        val phone = SoftAuthenticator(ORIGIN)
        val sheets = object : IdentityProvider {
            override suspend fun signIn(kind: AccountKind): SignInProof = throw AuthNotConfiguredException(kind)
            override suspend fun createPasskey(options: String) = phone.create(options)
            override suspend fun getPasskey(options: String) = phone.get(options)
        }
        val db = GainsDatabase(DesktopDriverFactory(file = null).createDriver())
        val settings = SettingsRepository(db, Dispatchers.Unconfined)
        val store = SyncStore(db, Dispatchers.Unconfined)
        val api = SyncApi(client, baseUrl = "", token = { store.token() })
        val config = AuthConfig(serverBaseUrl = "https://api.example", passkeys = true)
        val accounts = AccountRepository(settings, config, api, store, sheets)

        assertFailsWith<AuthNotConfiguredException> { AccountRepository(settings, config.copy(passkeys = false), api, store, sheets).signInWithPasskey() }
        assertFailsWith<AuthNotConfiguredException> { AccountRepository(settings, config, api, store, NoIdentityProvider).signInWithPasskey() }

        // Signed in some other way first, since a passkey is only ever added to an account.
        val ada = client.signInWithGoogle("g-1", "ada@example.com")
        store.setToken(ada.token)
        accounts.addPasskey()
        accounts.signOut()
        assertEquals(null, accounts.observeAccount().first())

        accounts.signInWithPasskey()
        assertEquals(Account(AccountKind.PASSKEY, "Ada", "ada@example.com"), accounts.observeAccount().first())
        assertEquals(ada.user.id, api.me().id)
    }

    private companion object {
        const val RP_ID = "gains.example"
        const val ORIGIN = "https://gains.example"
        const val APK_KEY_HASH = "ZFJ5hn1ZbM3ga7t0Fu0WqYk0ek1wAmfjmsQgIuLG0yM"
    }
}
