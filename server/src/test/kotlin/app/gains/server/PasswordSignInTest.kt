package app.gains.server

import app.gains.auth.Account
import app.gains.auth.AccountKind
import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.auth.AuthNotConfiguredException
import app.gains.auth.EmailSignInException
import app.gains.auth.NoIdentityProvider
import app.gains.data.DesktopDriverFactory
import app.gains.data.SettingsRepository
import app.gains.db.GainsDatabase
import app.gains.sync.EmailRequest
import app.gains.sync.EmailTokenRequest
import app.gains.sync.PasswordResetRequest
import app.gains.sync.PasswordSignInRequest
import app.gains.sync.PasswordSignUpRequest
import app.gains.sync.SignInRequest
import app.gains.sync.SignInResponse
import app.gains.sync.SyncApi
import app.gains.sync.SyncJson
import app.gains.sync.SyncStore
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import kotlinx.serialization.KSerializer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Email and password accounts (docs/launch-plan.md, item 18) through the routes: a sign-up is
 * nothing until its mail is answered, the address joins an existing account only once confirmed,
 * a reset link sets the password, and nothing said or timed tells a stranger which addresses
 * have accounts. The mail is [FakeMailer]; nothing here sends one.
 */
class PasswordSignInTest {
    private val google = FakeProvider(JwksIdentityVerifier.GOOGLE_ISSUERS.first())
    private val apple = FakeProvider(JwksIdentityVerifier.APPLE_ISSUERS.first())

    private suspend fun <T> HttpClient.postJson(path: String, serializer: KSerializer<T>, body: T): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        setBody(SyncJson.encodeToString(serializer, body))
    }

    private suspend fun HttpClient.signUp(email: String, password: String) = postJson("/auth/password/signup", PasswordSignUpRequest.serializer(), PasswordSignUpRequest(email, password)).status
    private suspend fun HttpClient.verify(token: String) = postJson("/auth/password/verify", EmailTokenRequest.serializer(), EmailTokenRequest(token)).status
    private suspend fun HttpClient.signIn(email: String, password: String) = postJson("/auth/password/signin", PasswordSignInRequest.serializer(), PasswordSignInRequest(email, password))
    private suspend fun HttpClient.requestReset(email: String) = postJson("/auth/password/reset-request", EmailRequest.serializer(), EmailRequest(email)).status
    private suspend fun HttpClient.reset(token: String, password: String) = postJson("/auth/password/reset", PasswordResetRequest.serializer(), PasswordResetRequest(token, password)).status

    private suspend fun HttpResponse.signedIn(): SignInResponse {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return SyncJson.decodeFromString(SignInResponse.serializer(), bodyAsText())
    }

    private suspend fun HttpClient.signInWith(provider: String, token: String): SignInResponse =
        postJson("/auth/$provider", SignInRequest.serializer(), SignInRequest(token)).signedIn()

    @Test
    fun aSignUpIsAnAccountOnceItsMailIsAnswered() = testApplication {
        val mailer = FakeMailer()
        val services = testServices(google, apple, passwords = { testPasswords(it, mailer) })
        application { gainsServer(services) }

        assertEquals(HttpStatusCode.NoContent, client.signUp("Ada@Example.com", "correct horse"))
        assertEquals("ada@example.com", mailer.sent.single().to, "mailed to the address as typed, lowercased")
        val (page, token) = mailer.lastLink()
        assertEquals("verify", page)
        assertNull(services.store.credential("ada@example.com")!!.userId, "no user until confirmed")

        // Right password, unconfirmed address: told to confirm. Wrong password: told nothing more than that.
        assertEquals(HttpStatusCode.Forbidden, client.signIn("ada@example.com", "correct horse").status)
        assertEquals(HttpStatusCode.Unauthorized, client.signIn("ada@example.com", "wrong").status)

        assertEquals(HttpStatusCode.NoContent, client.verify(token))
        assertEquals(HttpStatusCode.BadRequest, client.verify(token), "a link works once")
        assertEquals(HttpStatusCode.BadRequest, client.verify("not-a-token"))

        val signedIn = client.signIn(" ADA@example.com ", "correct horse").signedIn()
        assertEquals("ada@example.com", signedIn.user.email)
        assertEquals(listOf("password"), signedIn.user.providers)
        val me = client.get("/auth/me") { header("Authorization", "Bearer ${signedIn.token}") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(HttpStatusCode.Unauthorized, client.signIn("ada@example.com", "wrong").status)
        assertEquals(HttpStatusCode.Unauthorized, client.signIn("nobody@example.com", "correct horse").status)
    }

    @Test
    fun theAddressJoinsAnExistingAccountOnlyOnceConfirmedAndOnlyAVerifiedOne() = testApplication {
        val mailer = FakeMailer()
        val services = testServices(google, apple, passwords = { testPasswords(it, mailer) })
        application { gainsServer(services) }

        val viaGoogle = client.signInWith("google", google.token("g-1", GOOGLE_AUDIENCE, email = "same@x.y", name = "Ada", emailVerified = true))
        client.signUp("same@x.y", "correct horse")
        assertEquals(listOf("google"), services.store.user(viaGoogle.user.id)!!.providers, "a sign-up alone joins nothing")
        client.verify(mailer.lastLink().second)
        val viaPassword = client.signIn("same@x.y", "correct horse").signedIn()
        assertEquals(viaGoogle.user.id, viaPassword.user.id)
        assertEquals(listOf("google", "password"), viaPassword.user.providers)
        assertEquals("Ada", viaPassword.user.name)

        // The other way round: an Apple identity whose provider verified the address joins the email account.
        client.signUp("solo@x.y", "correct horse")
        client.verify(mailer.lastLink().second)
        val emailFirst = client.signIn("solo@x.y", "correct horse").signedIn()
        val thenApple = client.signInWith("apple", apple.token("a-1", APPLE_AUDIENCE, email = "Solo@x.y", emailVerified = "true"))
        assertEquals(emailFirst.user.id, thenApple.user.id)
        assertEquals(listOf("apple", "password"), thenApple.user.providers)

        // An identity whose provider never verified the address is not joined, in either direction.
        val squatter = client.signInWith("google", google.token("g-2", GOOGLE_AUDIENCE, email = "claimed@x.y", emailVerified = false))
        client.signUp("claimed@x.y", "correct horse")
        client.verify(mailer.lastLink().second)
        val owner = client.signIn("claimed@x.y", "correct horse").signedIn()
        assertNotEquals(squatter.user.id, owner.user.id)
    }

    @Test
    fun aResetLinkSetsThePasswordAndConfirmsTheAddress() = testApplication {
        val mailer = FakeMailer()
        val services = testServices(google, apple, passwords = { testPasswords(it, mailer) })
        application { gainsServer(services) }

        client.signUp("ada@example.com", "correct horse")
        client.verify(mailer.lastLink().second)
        assertEquals(HttpStatusCode.NoContent, client.requestReset("nobody@example.com"))
        assertEquals(1, mailer.sent.size, "an unknown address gets the same answer and no mail")
        assertEquals(HttpStatusCode.NoContent, client.requestReset("ada@example.com"))
        val (page, token) = mailer.lastLink()
        assertEquals("reset", page)
        assertEquals(HttpStatusCode.BadRequest, client.reset(token, "short"), "the password rule holds, and the link is still good")
        assertEquals(HttpStatusCode.NoContent, client.reset(token, "battery staple"))
        assertEquals(HttpStatusCode.BadRequest, client.reset(token, "battery staple"), "a link works once")
        assertEquals(HttpStatusCode.Unauthorized, client.signIn("ada@example.com", "correct horse").status)
        client.signIn("ada@example.com", "battery staple").signedIn()

        // A sign-up whose confirmation mail was lost: the reset link proves the address just as well.
        client.signUp("grace@example.com", "correct horse")
        client.requestReset("grace@example.com")
        client.reset(mailer.lastLink().second, "battery staple")
        assertEquals(listOf("password"), client.signIn("grace@example.com", "battery staple").signedIn().user.providers)
    }

    @Test
    fun signingUpAgainReplacesAnUnconfirmedPasswordAndTellsAConfirmedOwner() = testApplication {
        val mailer = FakeMailer()
        val services = testServices(google, apple, passwords = { testPasswords(it, mailer) })
        application { gainsServer(services) }

        client.signUp("ada@example.com", "first try")
        val stale = mailer.lastLink().second
        client.signUp("ada@example.com", "second try")
        client.verify(mailer.lastLink().second)
        assertEquals(HttpStatusCode.Unauthorized, client.signIn("ada@example.com", "first try").status)
        client.signIn("ada@example.com", "second try").signedIn()
        assertEquals(HttpStatusCode.BadRequest, client.verify(stale), "the earlier mail's link died with the new one")

        // Confirmed: the password is not touched, the owner hears about it, and the caller learns nothing.
        assertEquals(HttpStatusCode.NoContent, client.signUp("ada@example.com", "third try"))
        assertEquals("You already have a Gains account", mailer.sent.last().subject)
        assertEquals("reset", mailer.lastLink().first)
        assertEquals(HttpStatusCode.Unauthorized, client.signIn("ada@example.com", "third try").status)
        client.signIn("ada@example.com", "second try").signedIn()
    }

    @Test
    fun badInputAndTooManyTriesAreRefused() = testApplication {
        val mailer = FakeMailer()
        val services = testServices(google, apple, passwords = { testPasswords(it, mailer, perEmail = 3) })
        application { gainsServer(services) }

        assertEquals(HttpStatusCode.BadRequest, client.signUp("not an address", "correct horse"))
        assertEquals(HttpStatusCode.BadRequest, client.signUp("ada@example.com", "short"))
        assertEquals(HttpStatusCode.BadRequest, client.signUp("ada@example.com", "x".repeat(200)))
        assertEquals(0, mailer.sent.size)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/auth/password/signup", EmailRequest.serializer(), EmailRequest("ada@example.com")).status)

        // Three tries per address, then a wait, whoever is asking; the right password no longer gets in either.
        client.signUp("ada@example.com", "correct horse")
        client.verify(mailer.lastLink().second)
        for (i in 1..2) assertEquals(HttpStatusCode.Unauthorized, client.signIn("ada@example.com", "guess $i").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.signIn("ada@example.com", "correct horse").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.requestReset("ada@example.com"))
        client.signIn("grace@example.com", "guess").let { assertEquals(HttpStatusCode.Unauthorized, it.status, "another address is not held back") }

        // A mail that can't go out is a 503, not a silent nothing.
        mailer.failing = true
        assertEquals(HttpStatusCode.ServiceUnavailable, client.signUp("linus@example.com", "correct horse"))
    }

    @Test
    fun withoutAMailAccountTheRoutesAreOff() = testApplication {
        application { gainsServer(testServices(google, apple)) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.signUp("ada@example.com", "correct horse"))
        assertEquals(HttpStatusCode.ServiceUnavailable, client.signIn("ada@example.com", "correct horse").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.verify("x"))
    }

    @Test
    fun linksExpire() = testApplication {
        var now = Instant.parse("2026-09-28T10:00:00Z")
        val mailer = FakeMailer()
        val services = testServices(google, apple, passwords = { testPasswords(it, mailer, clock = { now }) })
        application { gainsServer(services) }

        client.signUp("ada@example.com", "correct horse")
        val verify = mailer.lastLink().second
        now += PasswordSignIn.VERIFY_TTL.plusSeconds(1)
        assertEquals(HttpStatusCode.BadRequest, client.verify(verify))
        client.signUp("ada@example.com", "correct horse")
        client.verify(mailer.lastLink().second)
        client.requestReset("ada@example.com")
        val reset = mailer.lastLink().second
        now += PasswordSignIn.RESET_TTL.plusSeconds(1)
        assertEquals(HttpStatusCode.BadRequest, client.reset(reset, "battery staple"))
    }

    @Test
    fun deletingTheAccountRemovesTheCredentialAndItsLinks() = testApplication {
        val mailer = FakeMailer()
        val services = testServices(google, apple, passwords = { testPasswords(it, mailer) })
        application { gainsServer(services) }

        client.signUp("ada@example.com", "correct horse")
        client.verify(mailer.lastLink().second)
        val signedIn = client.signIn("ada@example.com", "correct horse").signedIn()
        client.requestReset("ada@example.com")
        val reset = mailer.lastLink().second
        assertEquals(HttpStatusCode.NoContent, client.delete("/auth/account") { header("Authorization", "Bearer ${signedIn.token}") }.status)
        assertNull(services.store.credential("ada@example.com"))
        assertEquals(HttpStatusCode.Unauthorized, client.signIn("ada@example.com", "correct horse").status)
        assertEquals(HttpStatusCode.BadRequest, client.reset(reset, "battery staple"))

        // The same person can start again from nothing.
        client.signUp("ada@example.com", "correct horse")
        client.verify(mailer.lastLink().second)
        val fresh = client.signIn("ada@example.com", "correct horse").signedIn()
        assertNotEquals(signedIn.user.id, fresh.user.id)
    }

    @Test
    fun theAppSignsUpAndInThroughItsOwnRepository() = testApplication {
        val mailer = FakeMailer()
        val services = testServices(google, apple, passwords = { testPasswords(it, mailer) })
        application { gainsServer(services) }
        val db = GainsDatabase(DesktopDriverFactory(file = null).createDriver())
        val settings = SettingsRepository(db, Dispatchers.Unconfined)
        val store = SyncStore(db, Dispatchers.Unconfined)
        val api = SyncApi(client, baseUrl = "", token = { store.token() })
        val config = AuthConfig(serverBaseUrl = "https://api.example", passwordSignIn = true)
        val accounts = AccountRepository(settings, config, api, store, NoIdentityProvider)

        assertFailsWith<AuthNotConfiguredException> { AccountRepository(settings, config.copy(passwordSignIn = false), api, store, NoIdentityProvider).signUpWithEmail("ada@example.com", "correct horse") }
        assertEquals(EmailSignInException.Reason.INVALID, assertFailsWith<EmailSignInException> { accounts.signUpWithEmail("ada@example.com", "short") }.reason)
        accounts.signUpWithEmail("ada@example.com", "correct horse")
        assertEquals(EmailSignInException.Reason.NOT_CONFIRMED, assertFailsWith<EmailSignInException> { accounts.signInWithEmail("ada@example.com", "correct horse") }.reason)
        client.verify(mailer.lastLink().second)
        assertEquals(EmailSignInException.Reason.WRONG_CREDENTIALS, assertFailsWith<EmailSignInException> { accounts.signInWithEmail("ada@example.com", "wrong") }.reason)
        assertNull(accounts.observeAccount().first())

        accounts.signInWithEmail("ada@example.com", "correct horse")
        assertEquals(Account(AccountKind.EMAIL, null, "ada@example.com"), accounts.observeAccount().first())
        assertTrue(store.token() != null)
        assertEquals("ada@example.com", api.me().email)
        accounts.requestPasswordReset("ada@example.com")
        assertEquals("reset", mailer.lastLink().first)
    }
}
