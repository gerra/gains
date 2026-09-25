package app.gains.server

import app.gains.auth.AccountKind
import app.gains.sync.SignInRequest
import app.gains.sync.SignInResponse
import app.gains.sync.SyncApi
import app.gains.sync.SyncJson
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Revoking Sign in with Apple when an account is deleted (docs/launch-plan.md, item 6): the code
 * an Apple sign-in carries becomes a stored refresh token, and deleting the account revokes it
 * before the rows go. Apple itself is [FakeAppleTokens]; nothing here calls it.
 */
class AppleRevocationTest {
    private val google = FakeProvider(JwksIdentityVerifier.GOOGLE_ISSUERS.first())
    private val apple = FakeProvider(JwksIdentityVerifier.APPLE_ISSUERS.first())

    private suspend fun HttpClient.signIn(provider: String, token: String, code: String? = null): SignInResponse {
        val response = post("/auth/$provider") {
            contentType(ContentType.Application.Json)
            setBody(SyncJson.encodeToString(SignInRequest.serializer(), SignInRequest(token, authorizationCode = code)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return SyncJson.decodeFromString(SignInResponse.serializer(), response.bodyAsText())
    }

    private suspend fun HttpClient.deleteAccount(token: String) {
        assertEquals(HttpStatusCode.NoContent, delete("/auth/account") { header("Authorization", "Bearer $token") }.status)
        assertEquals(HttpStatusCode.Unauthorized, get("/auth/me") { header("Authorization", "Bearer $token") }.status)
    }

    @Test
    fun theCodeBecomesARefreshTokenThatDeletingRevokesFirst() = testApplication {
        val appleTokens = FakeAppleTokens(codes = mapOf("code-1" to "a-1"))
        val services = testServices(google, apple, appleTokens = appleTokens)
        application { gainsServer(services) }

        // Through the app's own client, so the code rides the wire format the app sends.
        val api = SyncApi(client, baseUrl = "", token = { null })
        val signedIn = api.signIn(AccountKind.APPLE, apple.token("a-1", APPLE_AUDIENCE), name = null, authorizationCode = "code-1")
        assertEquals(listOf("code-1" to APPLE_AUDIENCE), appleTokens.exchanged, "exchanged for the token's audience")
        assertEquals(listOf("refresh-code-1" to APPLE_AUDIENCE), services.store.refreshTokens(signedIn.user.id, Providers.APPLE))

        var userWhenRevoked: Any? = null
        appleTokens.onRevoke = { userWhenRevoked = services.store.user(signedIn.user.id) }
        client.deleteAccount(signedIn.token)
        assertEquals(listOf("refresh-code-1" to APPLE_AUDIENCE), appleTokens.revoked)
        assertNotNull(userWhenRevoked, "revoked before the rows were deleted")
    }

    @Test
    fun aLaterSignInReplacesTheTokenAndALinkedGoogleIdentityIsLeftAlone() = testApplication {
        val appleTokens = FakeAppleTokens(codes = mapOf("code-1" to "a-1", "code-2" to "a-1", "code-g" to "g-1"))
        val services = testServices(google, apple, appleTokens = appleTokens)
        application { gainsServer(services) }

        client.signIn("apple", apple.token("a-1", APPLE_AUDIENCE, email = "same@x.y", emailVerified = "true"), code = "code-1")
        val again = client.signIn("apple", apple.token("a-1", APPLE_AUDIENCE), code = "code-2")
        // A code on the Google route is not Apple's to exchange.
        val viaGoogle = client.signIn("google", google.token("g-1", GOOGLE_AUDIENCE, email = "same@x.y", emailVerified = true), code = "code-g")
        assertEquals(again.user.id, viaGoogle.user.id)
        assertEquals(listOf("code-1", "code-2"), appleTokens.exchanged.map { it.first })
        assertEquals(listOf("refresh-code-2" to APPLE_AUDIENCE), services.store.refreshTokens(again.user.id, Providers.APPLE))

        client.deleteAccount(viaGoogle.token)
        assertEquals(listOf("refresh-code-2" to APPLE_AUDIENCE), appleTokens.revoked)
    }

    @Test
    fun aFailedRevokeStillDeletesTheAccount() = testApplication {
        val appleTokens = FakeAppleTokens(codes = mapOf("code-1" to "a-1"), revokeFails = true)
        val services = testServices(google, apple, appleTokens = appleTokens)
        application { gainsServer(services) }

        val signedIn = client.signIn("apple", apple.token("a-1", APPLE_AUDIENCE), code = "code-1")
        client.deleteAccount(signedIn.token)
        assertEquals(null, services.store.user(signedIn.user.id))
        assertEquals(emptyList(), services.store.refreshTokens(signedIn.user.id, Providers.APPLE))
    }

    @Test
    fun aCodeThatFailsOrBelongsToSomeoneElseStillSignsInAndStoresNothing() = testApplication {
        // "stolen" is a real code, but Apple says it was issued to a-2, not to the person signing in.
        val appleTokens = FakeAppleTokens(codes = mapOf("stolen" to "a-2"))
        val services = testServices(google, apple, appleTokens = appleTokens)
        application { gainsServer(services) }

        val expired = client.signIn("apple", apple.token("a-1", APPLE_AUDIENCE), code = "expired")
        val stolen = client.signIn("apple", apple.token("a-1", APPLE_AUDIENCE), code = "stolen")
        assertEquals(expired.user.id, stolen.user.id)
        assertEquals(2, appleTokens.exchanged.size)
        assertEquals(emptyList(), services.store.refreshTokens(stolen.user.id, Providers.APPLE))

        client.deleteAccount(stolen.token)
        assertTrue(appleTokens.revoked.isEmpty(), "nothing stored, nothing revoked")
    }

    @Test
    fun withoutAKeyCodesAreIgnoredAndDeletingWorks() = testApplication {
        val services = testServices(google, apple)
        application { gainsServer(services) }

        val signedIn = client.signIn("apple", apple.token("a-1", APPLE_AUDIENCE), code = "code-1")
        assertEquals(emptyList(), services.store.refreshTokens(signedIn.user.id, Providers.APPLE))
        client.deleteAccount(signedIn.token)
    }
}
